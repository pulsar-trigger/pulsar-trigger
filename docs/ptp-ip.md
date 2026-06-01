# Canon Wi-Fi PTP transport (PTP/IP)

Pulsar's fifth transport: drive a Canon EOS body over Wi-Fi using the
PTP-over-TCP-IP protocol (ISO 15740). The EOS R-series exposes this on its
**"Remote Control (EOS Utility)"** Wi-Fi mode — the same channel EOS Utility
uses on a laptop. The op set is identical to USB PTP (Pulsar shares the same
`PtpClient`); only the wire underneath differs.

Its reason to exist: the **EOS R**, which has no BLE shutter and whose CCAPI
doesn't activate. PTP/IP is the only wireless control path Canon ever shipped
for that body. The transport also works on every EOS body that supports
"Remote Control (EOS Utility)" mode (RP / R5 / R6 / R6 II / R7 / R8 / R10 /
R50, plus pre-R DSLRs from the EOS Utility era).

## Architecture

```
PulsarViewModel ──┬── _ptpIpTransport  (StateFlow<PtpIpTransport?>)
                  │   _ptpIpConnecting / _ptpIpAwaitingConfirm / _ptpIpError
                  │   ptpIpDiscovery   (NsdManager mDNS browser)
                  │   ptpIpClientGuid  (SharedPrefs — camera remembers us)
                  │
PtpIpTransport ── PtpClient ── PtpIpWire ── 2× TCP sockets (port 15740)
                  ▲
USB PtpTransport ──┘   (same PtpClient over BulkPtpWire instead)
```

The big architectural win is that `PtpClient` is wire-agnostic: every Canon
op method (`initiateCapture`, `canonRemoteRelease{On,Off}`, `canonSetRemoteMode`,
`canonSetEventMode`, `getDevicePropValue`, `canonGetViewFinderData`, …) calls
`PtpWire.transact()` and works unchanged on top of either `BulkPtpWire` (USB)
or `PtpIpWire` (Wi-Fi). Adding a new Canon op needs zero PTP/IP-specific code.

### Files

| File | Purpose |
|---|---|
| `ptp/PtpWire.kt` | Shared abstraction. One `suspend transact()` method; both wires implement it. |
| `ptp/BulkPtpWire.kt` | USB-bulk container framing (PIMA 15740) — the original wire, used by `PtpTransport`. |
| `ptp/PtpIpWire.kt` | PTP/IP packet framing (ISO 15740 + Canon practice). Owns the two TCP sockets; static `connect()` factory runs the four-message init handshake. |
| `ptp/PtpIpDiscovery.kt` | mDNS browser over `_ptp._tcp.local` via Android's `NsdManager`. Surfaces resolved `PtpIpCamera(name, host, port)` instances as a `StateFlow`. |
| `ptp/PtpIpTransport.kt` | Implements `CameraTransport`. Same capability detection as `PtpTransport` (USB) — `supportsBulb` / `supportsLiveView` / `supportsLensInfo` / `supportsBatteryReadout` all derived from `GetDeviceInfo`. |
| `viewmodel/PulsarViewModel.kt` | `connectPtpIp` / `disconnectPtpIp` / mutual-exclusion / `ptpIpClientGuid()` persistence. |
| `ui/screens/TransportSetupScreen.kt` | `PtpIpSetup` composable — discovered cameras list, "Confirm on camera" surface during handshake, error Toast on failure. |

## Discovery

mDNS browse on `_ptp._tcp.local`:

- `PtpIpDiscovery.start()` starts an `NsdManager.discoverServices(...)` lifecycle
  tied to the setup screen (`DisposableEffect`). `onServiceFound` triggers a
  per-service `resolveService`; resolved entries land in the `cameras` flow as
  `PtpIpCamera(name, host, port)`.
- Canon's mDNS service name follows the body's nickname (often the model + a
  4-digit suffix, e.g. "Canon EOS R MS-1234").
- The setup screen subscribes to the flow and renders each camera as a
  tap-to-connect card.
- Manual-IP fallback isn't implemented yet — if mDNS is blocked (e.g. some
  enterprise APs disable multicast), the camera won't appear. Open issue.

## Connect flow

PTP/IP requires a two-channel handshake before any PTP transaction is allowed.
Both channels are TCP on port 15740.

1. **User taps a discovered camera.** `vm.connectPtpIp(camera)` enqueues.
2. **Mutual exclusion** — other transports torn down first (BLE-ESP / CCAPI / USB
   PTP / Canon BLE / simulator). Scan stops.
3. **Command channel** — `Socket.connect(host, 15740)`. Send `Init Command Request`:
   ```
   guid(16) + utf16(friendlyName) + protocolVersion(4)
   ```
   The 16-byte client GUID is generated once and persisted in
   `pulsar_ptpip.client_guid` SharedPrefs so the camera only prompts on
   first connect — subsequent reconnects with the same GUID are silent.
4. **Camera confirmation prompt.** The body shows a "Connect this device?"
   dialog. The viewmodel surfaces `_ptpIpAwaitingConfirm = true` and the UI
   renders the "Confirm on camera" strip. **The user taps Connect on the
   body.** Camera replies with `Init Command Ack` (connection number +
   responder GUID + name) or `Init Fail` (rejected).
5. **Event channel** — second `Socket.connect(host, 15740)`. Send
   `Init Event Request(connNo)`; receive `Init Event Ack`. Both sockets stay
   open for the life of the session.
6. **`OpenSession(1)`** — first PTP op over the new wire.
7. **Canon PC-remote setup** — `SetRemoteMode(1)` + `SetEventMode(1)` so the
   body accepts `RemoteRelease` ops. Same as USB PTP.
8. **`GetDeviceInfo`** — populates capability flags (`supportsBulb` etc.) +
   the device label.
9. Done — `_ptpIpTransport.value` flips non-null; UI navigates to the trigger
   tab; `recordLastConnection` saves `name|host|port` for reconnect.

## Wire format primer

PTP/IP wraps every command/data/response in an outer 8-byte envelope:

```
[length:u32][packetType:u32][payload…]
```

Packet types Pulsar uses (ISO 15740):

| Type | Name | Purpose |
|---|---|---|
| 1 | `INIT_CMD_REQ` | First packet from initiator on command socket. |
| 2 | `INIT_CMD_ACK` | Camera's accept response — assigns connection number. |
| 3 | `INIT_EVENT_REQ` | First packet from initiator on event socket. |
| 4 | `INIT_EVENT_ACK` | Camera's accept on event socket. |
| 5 | `INIT_FAIL` | Camera rejected the connection. |
| 6 | `OP_REQUEST` | Op-code request: `dataPhase(4) + opcode(2) + txid(4) + params(N×4)`. |
| 7 | `OP_RESPONSE` | Final response: `rc(2) + txid(4) + params(N×4)`. |
| 8 | `EVENT` | Async event from camera (not consumed yet — Phase 4). |
| 9 | `START_DATA` | First packet of a data phase: `txid(4) + totalLen(8)`. |
| 10 | `DATA` | Mid-stream data: `txid(4) + bytes`. |
| 12 | `END_DATA` | Last packet of a data phase: `txid(4) + bytes`. |

The `PtpClient.Response` returned by `PtpWire.transact()` has the exact same
shape (rc + params + data) regardless of which wire produced it.

## Auto-reconnect (shipped v0.315 / v0.316)

If the Wi-Fi link drops, the run-loop **pauses** via `awaitCanonReady`
(parity with CCAPI) and the viewmodel re-runs the handshake against the same
`PtpIpTransport` instance using `PtpIpTransport.reopen()` — a 3 / 5 / 10 / 30 s
backoff. The outer transport reference stays valid, so the captured `transport`
in `runCanonBulb` etc. keeps working after the swap (`wire` / `client` /
`deviceInfo` are mutated in-place). On reconnect success the runner resumes
mid-flow; after 4 failed attempts the run ends with `STOPPED` and the
`reconnect_failed` error surfaces in the UI.

## Compatible bodies

Every EOS body with **"Remote Control (EOS Utility)" Wi-Fi mode** works:

- EOS R, RP, R5, R6, R6 II, R7, R8, R10, R50, Ra, R5 C.
- Pre-R bodies that supported EOS Utility over Wi-Fi (5D Mark IV, 6D Mark II,
  7D Mark II, 90D, 80D, 77D, 800D, etc.) — same protocol, same handshake.

The **EOS R** is the headline use case: this transport is the only wireless
control path on that body (BLE has no shutter; CCAPI doesn't activate).

## Honest caveats

- **Camera Wi-Fi is power-hungry.** Body battery drain doubles vs. USB PTP.
  Plan for a USB-C dummy-battery passthrough on long sessions.
- **mDNS discovery requires multicast on the LAN.** Some enterprise / hotel
  Wi-Fi blocks it. Workaround for now: use the camera's own Wi-Fi AP mode
  (camera as access point) so multicast works over the direct link.
- **First-connect prompt is per-client-GUID.** Pulsar persists its GUID so the
  prompt only happens once per body. If you wipe app data the GUID changes
  and the camera re-prompts.
- **PC-remote mode locks the camera's dial** — same caveat as USB PTP.

## Phases

Phase 1 (v0.305 — shipped): discovery + handshake + connect, with `GetDeviceInfo`
populating capability flags + label.

Phase 2 (v0.307–v0.313 — shipped): shutter (`fireShutter` → `InitiateCapture`
or `canonRemoteRelease{On,Off}`, `startBulb` / `stopBulb`). EOS R quirks
discovered + worked around: `vendorExtensionId = 6` over Wi-Fi (vs. 11 over USB
— gated on manufacturer string), and `mode = 2` rejected with `DEVICE_BUSY`
forcing `mode = 3` on the wire. AF toggle is therefore cosmetic on this
transport and hidden via `supportsAfToggle = false`.

Phase 3 (v0.314 — shipped): lens info (`getLensInfo` → `PROP_CANON_LENS_NAME`),
battery (`readBatteryPercent` → `PROP_BATTERY_LEVEL`, 30s poll), live view
(`startLiveView` / `stopLiveView` / `getLiveViewFrame` → `PROP_CANON_EVF_OUTPUT`
+ `canonGetViewFinderData`), and focus drive (`driveFocus` → `canonDriveLens`)
for the Star Focus wizard. Shared `decodePtpString` + `extractJpeg` extracted
to `PtpDataHelpers.kt`.

Phase 5 (v0.315–v0.316 — shipped): idle auto-reconnect (3 / 5 / 10 / 30 s
backoff) and mid-flow pause-and-resume via `PtpIpTransport.reopen()` which
swaps wire/client under the same transport reference. `awaitCanonReady`
extended for PTP/IP. Diagnostics integration via the shared `CanonBleLog`
ring + `CrashPersister` that survives JVM-kill crashes by dumping the log to
`filesDir` on `Thread.setDefaultUncaughtExceptionHandler`.

## References

- ISO 15740 (PIMA 15740) — Picture Transfer Protocol, including PTP-over-IP.
- `featherbear/eos-ptp` — JavaScript reference implementation of Canon's
  PTP/IP handshake. Confirmed the connection-number / GUID echo behaviour.
- `JulianSchroden/cine_remote` — Flutter/Dart implementation covering the
  R-series video workflow over PTP/IP.
- `tools/ptpip_test.py` — Pulsar's own protocol-validation script, in-repo.
  Dependency-free Python; useful for diagnosing camera-side issues
  independent of the app.

Sister doc: [docs/ptp.md](ptp.md) — USB PTP transport (same `PtpClient`,
different wire).
