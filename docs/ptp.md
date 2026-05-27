# USB PTP transport

Pulsar's third transport: drive a Canon body (or any PTP-capable camera) directly over USB-C from the phone. No ESP32 in between, no Wi-Fi handshake, no CCAPI activation — just a USB-C cable from the phone's host port to the camera. Built on Android's `UsbManager` host API + a hand-rolled PTP-over-USB client.

The PTP transport's reason to exist: Canon's EOS R doesn't support CCAPI even on the latest firmware, and there is no PC activation path that fixes it. PTP is the only phone-side automation Pulsar can offer on that body. As a bonus it's also the lowest-latency transport overall — bulk USB writes are ~10-30 ms vs CCAPI's ~100-200 ms HTTP RTT — and the camera draws ~5 %/h instead of ~30 %/h, because the body's Wi-Fi radio stays off.

## What works (Phase 1–6 shipped)

| Feature | Status | Notes |
|---|---|---|
| Connect / disconnect | ✅ | Auto-bonds via USB permission dialog; reconnects on cable replug |
| Single-shot capture | ✅ | `InitiateCapture` (PTP op `0x100E`) — drives Timelapse |
| Bulb exposure | ✅ | Canon `RemoteReleaseOn` / `Off` (ops `0x9128` / `0x9129`) |
| Per-shot AF control | ✅ | Mode 2 (no AF) vs mode 3 (with AF) on RemoteRelease |
| Bulb shutter-speed auto-select | ✅ (best effort) | `SetDevicePropValue(0xD102, 0x000C)` at flow start; logs on failure |
| Battery readout | ✅ | PTP `GetDevicePropValue(0x5001)`, polled every 30 s |
| Lens info | ✅ | Canon `LensName` property `0xD157`; focal length parsed from name |
| All wizard modes (Intervalometer, Astro, DarkFrame, Ramp, Timelapse) | ✅ | Routed through `runCanonBulb` / `runCanonTimelapse` / `runCanonRamp` |
| Live view / Star Focus | ✅ | Canon `GetViewFinderData` (op `0x9153`) + `DriveLens` (op `0x9155`) + `SetDevicePropValue(EvfOutput 0xD1B0)`; `StarFocusScreen` reads from whichever Canon transport is active |
| Mid-cable-pull recovery | ⚠️ | Banner shows, transport tears down cleanly. *Not* a true resume — user must restart the flow after replug. Future work. |

## Tested bodies

- **Canon EOS R** — primary motivation; CCAPI does not activate on this body. PTP works for all modes.
- **Canon EOS RP** — also works on PTP. Eduardo's CCAPI body, validated both transports against the same hardware.

Other Canon EOS bodies + non-Canon PTP cameras (Nikon, Sony, Fuji) *should* work for basic capture (`InitiateCapture` is PIMA-standard) but the bulb path uses Canon-vendor operations `0x9128` / `0x9129`, so a different vendor would need its own bulb plumbing.

## Architecture

```
PulsarViewModel ──┬── _ptpTransport (StateFlow<PtpTransport?>)
                  │
                  └── ptpDiscovery (PtpDiscovery)
                       └── cameras: StateFlow<List<UsbDevice>>

PtpTransport ──── PtpClient ──── UsbDeviceConnection (Android)
                                  + bulk-in / bulk-out endpoints
```

### Files

| File | Purpose |
|---|---|
| `transport/ptp/PtpClient.kt` | Wire-protocol helper. PIMA-15740 + USB Still-Image class framing. Generic `transact()` plus typed helpers for the ops Pulsar uses. |
| `transport/ptp/PtpTransport.kt` | Implements `CameraTransport`. Owns the bond between PTP and Pulsar's wizards — fireShutter / startBulb / stopBulb / setShutterMode / supportsX flags. |
| `transport/ptp/PtpDiscovery.kt` | `BroadcastReceiver` on `USB_DEVICE_ATTACHED` / `DETACHED`. Filters for PTP-class devices (USB class 0x06, subclass 0x01, protocol 0x01). |
| `transport/ptp/UsbPermission.kt` | One-shot helper that suspends until the user grants the system USB-permission dialog. |
| `viewmodel/PulsarViewModel.kt` | `connectPtp` / `disconnectPtp` / mutual-exclusion logic / battery polling job / auto-reconnect on cable replug / `_ptpReconnecting` banner state. |
| `ui/screens/ScanScreen.kt` | "USB cameras" section + tap-to-connect cards; PTP error Toast on the `ptpError` flow. |
| `ui/screens/MainMenuScreen.kt` | `PtpBanner` on the Trigger tab parallel to `CanonBulbBanner`. |

## Discovery

`PtpDiscovery` registers a `BroadcastReceiver` for `UsbManager.ACTION_USB_DEVICE_ATTACHED` and `ACTION_USB_DEVICE_DETACHED`. On each event it re-enumerates `usb.deviceList` and filters for devices that expose an interface matching:

- Class: `0x06` (Still Image)
- Subclass: `0x01`
- Protocol: `0x01`

That's the standard PIMA-15740-over-USB descriptor. Canon, Nikon, Sony, Fuji etc all use it.

The filter is interface-level, not device-level, because some cameras present multiple interfaces (PTP + mass storage). Pulsar only claims the PTP interface, leaves the rest alone.

No vendor-ID filter — the test in `PtpProbe.findCameraDevice` does a friendly "is this a known camera vendor?" check for the probe UI, but the production transport accepts any device with a PTP interface. Better to show one Canon you don't recognize than to hide it because the vendor ID changed.

## Connect flow

1. **User taps a USB camera card** on the scan screen. `vm.connectPtp(device)` enqueues on the viewmodel scope.
2. **USB permission** — `requestUsbPermission(...)` shows the Android system dialog. User taps Allow. If denied: `_ptpError = "permission_denied"` → Toast.
3. **Open device + claim interface** — `PtpTransport.openOn(ctx, device)` opens the `UsbDeviceConnection`, claims the PTP interface, finds the bulk-IN and bulk-OUT endpoints.
4. **GetDeviceInfo** — first PTP request. Pulls the device's vendor extension ID, model name, supported operations, supported device properties. Drives `supportsBulb` / `supportsLensInfo` / `supportsBatteryReadout` capability flags.
5. **OpenSession** — PTP op `0x1002` with session ID 1. Camera now expects further ops on this session.
6. **Canon-specific PC-remote setup** — if `vendorExtensionId == 11` (Canon EOS):
   - `SetRemoteMode(1)` (op `0x9114`) — enables PC remote-control mode on the body. The body's menu / dial controls lock until disconnect.
   - `EventMode(1)` (op `0x9115`) — enables the event channel.
7. **Battery polling job** spins up — `transport.readBatteryPercent()` every 30 s, feeds `_status.batteryPct`.
8. **Mutual exclusion** — any active BLE / CCAPI session is torn down before step 1. Auto-reconnect target is recorded as `(vendorId, productId)`.

## Disconnect flow

`disconnectPtp(clearAutoReconnect: Boolean = true)`:

1. Cancel the battery polling job.
2. `transport.release()` — sends `SetRemoteMode(0)` (releases PC-remote control on Canon), `CloseSession`, releases the USB interface, closes the connection.
3. `_ptpTransport.value = null`.
4. If `clearAutoReconnect == true` (user-initiated disconnect), wipe `lastPtpAutoReconnect` and `_ptpReconnecting`. If `false` (cable yanked), preserve both so the next attach event triggers auto-reconnect.

## Capture path (where wizards meet the wire)

The wizards build a `FlowStep`, save it via `vm.saveFlowSteps(...)`, and `vm.startFlow()` walks the list. For each step, `executeFlowStep` dispatches to the right transport branch:

```kotlin
is FlowStep.Intervalometer -> {
    val canon = _canonTransport.value
    val ptp = _ptpTransport.value
    when {
        canon != null -> { /* CCAPI path */ }
        ptp != null -> {
            if (step.exposureMs == TIMELAPSE_PULSE_MS) runCanonTimelapse(ptp, ...)
            else runCanonBulb(ptp, ...)
        }
        _simulatorActive.value -> simulateShots(...)
        else -> { /* BLE-ESP firmware path */ }
    }
}
```

The runners (`runCanonTimelapse`, `runCanonBulb`, `runCanonRamp`) live in `transport/CanonRunner.kt`. They're transport-agnostic — they take a `CameraTransport` and call `transport.startBulb(af)` / `transport.stopBulb()` / `transport.fireShutter(af)`. The Canon-specific behavior happens inside `PtpTransport`.

### Wire calls per mode

| Mode | What `PtpTransport` does |
|---|---|
| Timelapse (Intervalometer with `TIMELAPSE_PULSE_MS` sentinel) | `InitiateCapture(0, 0)` per shot. Camera owns the exposure (whatever shutter speed is set on the body). |
| Intervalometer (bulb) / Astro / DarkFrame | Per shot: `RemoteReleaseOn(mode)` → host-side `delay(exposureMs)` → `RemoteReleaseOff(mode)`. |
| Ramp | Same as bulb but `exposureMs` interpolates linearly across steps. |

The `mode` parameter in RemoteReleaseOn/Off:

- `2` = full press, **no AF** — used when the wizard's `useAutofocus` toggle is off (default for astro / bulb)
- `3` = full press + AF — used when `useAutofocus` is on (default for Timelapse)

Pulsar tracks `lastBulbMode` so the matching `RemoteReleaseOff` uses the same value — some bodies require this.

### Programmatic Bulb selection

Before a bulb-style flow, `runCanonBulb` calls `transport.setShutterMode(bulb = true)`. The PTP impl tries `SetDevicePropValue(0xD102, 0x000C)` — that's Canon's shutter-speed property with the "Bulb" code that works on R-class bodies. **Best effort**: if the body uses a different code or rejects the write (e.g. not in Manual exposure mode), Pulsar logs a warning and continues. The exposure will then fire at whatever shutter speed the body currently has, which is wrong for bulb modes — so the body's UI is the safety net (set Bulb on the dial yourself).

**Side effect of this:** once Pulsar sets the body to Bulb via PTP, the body **stays in Bulb until Pulsar disconnects** (PC-remote mode locks the dial). If you then want to do a Timelapse run (camera owns exposure, needs a normal shutter speed), you have to disconnect Pulsar, switch the body to Manual + your desired shutter speed, then reconnect. The `ptp_connected_hint` banner copy mentions this.

## Capability flags

`PtpTransport` derives the `CameraTransport` capability flags from `GetDeviceInfo`:

```kotlin
override val supportsBulb: Boolean =
    OP_CANON_REMOTE_RELEASE_ON in deviceInfo.supportedOperations ||
    0x9125 in deviceInfo.supportedOperations  // older Canon bulb op
override val supportsSettings: Boolean =
    deviceInfo.supportedDeviceProperties.isNotEmpty()
override val supportsLiveView: Boolean =
    OP_CANON_GET_VIEWFINDER_DATA in deviceInfo.supportedOperations
override val supportsLensInfo: Boolean =
    PROP_CANON_LENS_NAME in deviceInfo.supportedDeviceProperties
override val supportsBatteryReadout: Boolean =
    PROP_BATTERY_LEVEL in deviceInfo.supportedDeviceProperties
```

Each flag is checked once at transport creation. The wizards gate their bulb-based tiles on `transport.supportsBulb`, the Astro focal-length auto-fill calls `getLensInfo()` only when `supportsLensInfo`, etc.

## Auto-reconnect on cable replug

The viewmodel records `lastPtpAutoReconnect = (vendorId, productId)` on every successful connect. When the discovery flow emits an event:

- If the **active** PTP camera vanishes from the attached list → tear down (`disconnectPtp(clearAutoReconnect = false)`) and flip the reconnect banner on.
- If we're **idle across all other transports** and `lastPtpAutoReconnect != null` and a device matching that vendor/product reappears → call `connectPtp(matchedDevice)` automatically.

`idleAcrossOtherTransports()` checks that BLE-ESP, CCAPI, and simulator are all inactive — auto-reconnect won't snatch the user away from a deliberate transport switch.

`lastPtpAutoReconnect` clears on:
- Explicit user `disconnect()` from the Settings UI
- Mutual-exclusion teardown when the user picks BLE / CCAPI / simulator
- App process death (in-memory only; not persisted)

## Mid-shoot disconnect (current behavior)

The flow keeps running but each `transport.startBulb` / `stopBulb` call no-ops gracefully (the `_connected` flag inside `PtpTransport` becomes false after release). The wizard's run-screen shows the banner via `_ptpReconnecting`; the flow ultimately ends with whatever the loop's bookkeeping decides (most likely an `IllegalStateException` from the cancel-mid-run sequence).

True mid-shoot resume — where the flow pauses, the cable replug brings the transport back, and the same flow continues — is Phase 6 work. It needs the transport instance to survive cable detach with a refreshed `UsbDeviceConnection` (or a transport-supplier indirection in the runners). For now: replug + tap-Start-again.

## PTP wire format primer

Three message types in one transaction:

```
[Command]   12 bytes: length(4) type=1(2) opcode(2) txid(4) [+ up to 5 × 4-byte params]
[Data]      header(12) + payload bytes        ← optional, only for data-in/data-out ops
[Response]  12 bytes: length(4) type=3(2) rc(2) txid(4) [+ result params]
```

Sent on the bulk-OUT endpoint; response on bulk-IN. `PtpClient.transact()` handles all three. Transaction IDs are monotonic per session.

Strings in PTP are UTF-16LE with a 1-byte length prefix (count of UTF-16 code units **including** the trailing NUL). `PtpClient.readPtpString` and `PtpTransport.decodePtpString` decode them.

## References

- PIMA-15740 — base PTP protocol spec (paid, but the relevant chunks are summarized in libgphoto2's headers).
- USB Still Image Capture Device Class Specification 1.0 — bulk-endpoint framing.
- `libgphoto2`'s `canon` driver — concrete reference for Canon vendor ops (`0x9114` / `0x9115` / `0x9128` / `0x9129`).
- Canon EDSDK / PTP extensions reference — NDA-covered, lives under `../Canon-API/` outside the repo.

## Known issues / future work

1. **No mid-shoot resume.** Cable replug requires manual restart.
2. **Bulb code `0x000C` is R-class specific.** Other Canon bodies may need a different value for `SetDevicePropValue(0xD102, ...)`. We'd want a per-body table or query the supported value range via `GetDevicePropDesc`.
3. **Body locked in Bulb after a bulb flow.** Disconnect-and-reconnect is the workaround. A `setShutterMode(bulb = false)` that writes a known-safe Manual code would be cleaner.
4. **Non-Canon PTP bulb.** `InitiateCapture` is universal; bulb is vendor-specific. Nikon / Sony / Fuji each need their own bulb opcodes.
