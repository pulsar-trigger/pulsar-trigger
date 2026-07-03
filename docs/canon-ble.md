# Canon BLE direct transport

Pulsar's fourth transport: drive a Canon body directly over Bluetooth Low Energy using Canon's BR-E1 remote-control protocol. No ESP32 hardware in line, no Wi-Fi setup, no cable — just BLE between the phone and the camera.

Its reason to exist: a fully wireless, hardware-free path on Canon bodies that takes the same `CameraTransport` abstraction as CCAPI and PTP. CCAPI shipped first and remains the most featureful (live view + Star Focus + lens info + battery), PTP-over-USB shipped second to cover the EOS R where CCAPI cannot be activated, and Canon BLE direct now covers the "I just want to wirelessly trigger my Canon from my phone, no cables, no extra gear" case.

## Two protocols (auto-detected)

Canon bodies expose **two different BLE control protocols** depending on the
camera's Bluetooth menu setting. Pulsar auto-detects which one a body speaks
*after connecting* (the advertisement isn't reliable — an RP in smartphone mode
still advertises the BR-E1 UUID), then arms it accordingly:

- **BR-E1 remote** (service `00050000`) — the classic remote protocol; a
  `[0x03, name]` arm-write then single-byte control writes. Fires older DSLR /
  M-series bodies. **Does not fire the EOS R-series** (they pair but ignore the
  shutter).
- **Smartphone mode** (service `00010000` + control service `00030000`) —
  Canon's richer "Connect to smartphone" protocol with a registration
  handshake (4 identify writes → you confirm the pairing on the camera body →
  mode-switch → `[00 01]`/`[00 02]` shutter). **This is the path that fires the
  EOS RP / R5 / R6 / newer.** A generated 128-bit identity is persisted so
  re-connects reuse the registration.
- **EOS R (2018) exception** — registers in smartphone mode but exposes no
  `00030000`: it has *no BLE shutter at all* (even Canon's Camera Connect needs
  Wi-Fi to fire it). Pulsar detects this and steers the user to USB/Wi-Fi.

The full protocol bytes + reverse-engineering record (incl. a confirmed EOS RP
fire) are in **[canon-ble-research.md](canon-ble-research.md)**.

## What works

| Feature | Status | Notes |
|---|---|---|
| Pair / unpair | ✅ | First-time pairing triggers the Android system pair dialog; subsequent connects reuse the bond. The `[0x03, name]` arm-write is re-sent on **every** connect (registers Pulsar as the active remote for the session — the camera ignores control writes otherwise). Camera must be in Bluetooth → Remote pair mode on first connect. |
| Single-shot capture | ✅ | Drives Timelapse. Camera owns the exposure. |
| Bulb exposure | ✅ | Press → host-side wait → release pattern. **User must put the mode dial on Bulb on the camera body itself** — the protocol has no shutter-speed write. |
| Per-shot AF control | ✅ | Half-press (`0x4C`) → release → full-press (`0x8C`) when `useAutofocus=true`; bare full-press when false. |
| All wizard modes (Intervalometer, Astro, DarkFrame, Ramp, Timelapse) | ✅ | Routed through `runCanonBulb` / `runCanonTimelapse` / `runCanonRamp` in `CanonRunner.kt`. |
| Auto-reconnect on re-advertise | ✅ | GATT spontaneous disconnect arms the reconnect banner; the BLE scan restarts; collector reconnects when the same MAC re-advertises. Bond persists across app launches; saved MAC persists in plain SharedPreferences. |
| Live view / Star Focus | ❌ | BR-E1 protocol has no live view endpoint. Use CCAPI or PTP for Star Focus. |
| Lens info | ❌ | Not in the protocol. The Astro wizard's focal-length auto-fill falls back to a manual entry on this transport. |
| Battery readout | ❌ | Not in the protocol. The status chip shows the seeded 0%. |
| Camera settings (ISO / aperture / shutter speed) | ❌ | Not in the protocol. |

## Compatible bodies

Verified across six open-source references (see `External references` below). Any body Canon lists in the BR-E1 compatibility matrix should work:

EOS R, RP, R5, R6, Ra, 6D Mark II, 77D, 800D / Rebel T7i, 200D / SL2, 850D, M50, M200, plus the PowerShots G7 X Mark III and G5 X Mark II.

**Smartphone-mode coverage (research-confirmed, 22 bodies)** — a 2026-06-03
cross-reference against [intervalometer.app](https://intervalometer.app/)'s
per-body setup guides confirmed that every modern Canon body they support
pairs over smartphone-mode (`00010000` + `00030000`), not BR-E1. Full
list + per-body menu paths in
**[canon-body-matrix.md → Expected to work — smartphone-mode BLE](canon-body-matrix.md#expected-to-work--smartphone-mode-ble-research-not-pulsar-tested)**.
Strong prior that Pulsar's smart-mode path works on all of them; needs
on-body verification before the *Snapshot* table.

BR-E1 remote-mode (`00050000`) is the only BLE path on older bodies
(pre-2018 DSLRs — 5D IV, 6D II, 7D II, 80D, T7i, T8i, etc.) and for
users who already have a BR-E1 physical remote paired. Pulsar
auto-detects at connect time — no user-visible toggle.

Bodies without a BR-E1 listing won't advertise the service UUID (`00050000-…-d8492fffa821`) and won't show up in the Canon-BLE-remotes section of the scan screen.

## Architecture

```
PulsarViewModel ──┬── _canonBleTransport (StateFlow<CanonBleTransport?>)
                  │   _canonBleReconnecting (StateFlow<Boolean>)
                  │   lastCanonBleAddress (SharedPrefs, plain text MAC)
                  │
                  └── canonBleDiscovery (CanonBleDiscovery)
                       └── cameras: StateFlow<List<BluetoothDevice>>

CanonBleTransport ── CanonBleClient ── BluetoothGatt (Android)
                                       + service / characteristics
                                       + spontaneous-disconnect callback
```

### Files

| File | Purpose |
|---|---|
| `canonble/CanonBleDiscovery.kt` | `BluetoothLeScanner` filtered on **both** the BR-E1 and smartphone-mode service UUIDs. Surfaces matched `BluetoothDevice`s as a `StateFlow`; `onScanResult` re-verifies the advertisement to drop phones / unrelated devices some stacks let through. |
| `canonble/CanonBleClient.kt` | GATT wrapper. Connect, discover services, pair-write, control-write, smartphone-mode handshake, indication subscription for the `0x02` pairing accept. Operations serialised through a `Mutex` (Android allows one in-flight GATT op per connection). Spontaneous-disconnect callback invoked when the link drops *after* a successful connect. |
| `canonble/CanonBleTransport.kt` | Implements `CameraTransport`. Auto-dispatches between BR-E1 byte writes and smartphone-mode `[00,01]` toggles based on the detected `CanonProtocol`. Reports `Ok` / `Failed` / `NoBleShutter` from `connect()` so the viewmodel can steer the user when an R-series body has no BLE shutter. |
| `canonble/CanonBleLog.kt` | In-app 600-line ring buffer for every Canon BLE event (connect, handshake, arm, every shutter write, disconnects). Forwarded to `Logcat` AND captured in memory so the user can grab it via **Tools → Diagnostics**. |
| `viewmodel/PulsarViewModel.kt` | `connectCanonBle` / `disconnectCanonBle` / mutual-exclusion / `onCanonBleLinkDropped` / auto-reconnect collector / persistence / `abortFlowOnTransportDrop` / `canonDiagnosticsText`. |
| `ui/screens/ScanLandingScreen.kt` | The Reconnect card + Diagnostics shortcut when no device is connected. |
| `ui/screens/TransportSetupScreen.kt` | Per-transport setup card — the "Canon BLE remotes" section + `CanonBleCameraCard` live here. Scan starts on screen-visible (`DisposableEffect`), stops on dispose. |
| `ui/screens/MainMenuScreen.kt` | `CanonBleBanner` on the Trigger tab parallel to `CanonBulbBanner` (CCAPI) and `PtpBanner`; the **Diagnostics** tile on the Tools tab. |
| `ui/screens/DiagnosticsScreen.kt` | Full-screen log viewer (scrollable, selectable, monospace) with Refresh / Copy / Share. Reads `vm.canonDiagnosticsText()`. |

## Discovery

`CanonBleDiscovery` runs a `BluetoothLeScanner` with **two** `ScanFilter`s in
parallel — the BR-E1 service UUID (`00050000-…-d8492fffa821`) and the
smartphone-mode service UUID (`00010000-…-d8492fffa821`). The latter is
what R-series bodies advertise in their default Bluetooth-menu state.

Some Android stacks accept the `ScanFilter` but pass through every advertisement
anyway (we've seen phones, headphones, BR-E1 hardware remotes etc. land in the
list). To filter, `onScanResult` re-verifies that the advertisement record
actually carries one of the two Canon service UUIDs — anything else is dropped
before it reaches the `cameras` flow.

The scan starts when the user opens the Scan screen (DisposableEffect) and stops when they leave it or successfully connect. After a spontaneous disconnect the viewmodel re-arms the scan in the background so the auto-reconnect collector can pick up the next advertisement.

Concurrent with the Pulsar ESP32 scan (`BleController`) — Android handles multiple `ScanCallback`s with independent filters.

## Connect flow

1. **User taps a Canon BLE card** on the scan screen. `vm.connectCanonBle(device)` enqueues on the viewmodel scope.
2. **Mutual exclusion** — any active BLE-ESP / CCAPI / PTP / simulator session is torn down first.
3. **Scan stop** — `canonBleDiscovery.stop()`. Android can't reliably scan + connect on the same radio.
4. **GATT connect** — `device.connectGatt(ctx, autoConnect=…, callback, TRANSPORT_LE)`. First connect uses `autoConnect=false`; the **reconnect** path (post-link-drop, OS-managed) uses `autoConnect=true` with a 120 s window — Android then completes the connection whenever the bonded body becomes available (including via the directed advertisements a service-UUID scan never sees).
5. **Service discovery + protocol auto-detect** — `gatt.discoverServices()`. Whichever Canon service is present picks the path (the advertisement is unreliable — an RP in smartphone mode still advertises `00050000`):
   - **BR-E1** (`00050000` only) → pull the pair (`00050002`) + control (`00050003`) characteristics.
   - **Smartphone** (`00010000` + `00030000`) → pull the identity chars (`00010006`, `0001000a`), the mode-select (`00030010`), and the shutter toggle (`00030030`). Arm the pairing-result indication on `00010006` so the camera's `0x02` accept byte can't be missed.
   - **Smartphone but no `00030000`** (the 2018 EOS R) → return `NoBleShutter` to the viewmodel; the UI steers the user to USB / Wi-Fi.
6. **Arm the protocol.**
   - **BR-E1** — write `[0x03, ...ASCII "Pulsar"]` to the pair characteristic. **This runs on every connection**, not just the first — it registers Pulsar as the active remote for *this session*. The camera silently ignores control-characteristic writes until armed this way, even when already OS-bonded. (Confirmed against `iebyt/cbremote`.) On the first connect this also triggers Android's MITM pair dialog — the user confirms with the passkey shown on the camera; later connects reuse the bond and the arm-write is silent.
   - **Smartphone** — `ensureBonded()` first: the RP ignores registration writes on an unbonded link, so we call `device.createBond()` and wait for the OS to drive the pairing prompt **on the camera body** (the user taps Confirm on the camera). Then the smartphone-mode handshake runs: `[01,name]` → `00010006`, `[03,uuid]` → `0001000a`, `[04,name]` → `0001000a`, `[05,02]` → `0001000a`, wait for the camera's `0x02` accept indication (first pair only — reconnects skip), finalize with `[01]` → `0001000a`, and switch to shoot mode with `[MODE_SHOOT]` → `00030010`. The 16-byte device UUID used in step 2 is generated once and persisted in `pulsar_canon_ble.smart_device_uuid` so reconnects reuse the registration.
7. **Persist MAC** — `lastCanonBleAddress` stored in plain SharedPrefs (the bond itself is in the OS keystore; this is just the reconnect hint).
8. **Done** — `_canonBleTransport.value` flips non-null; the UI navigates to the trigger tab.

## Disconnect flow

`disconnectCanonBle(clearAutoReconnect: Boolean = true)`:

1. Cancel the in-flight connect job if any.
2. `transport.release()` — closes the GATT session. Doesn't unpair (the OS bond survives).
3. `_canonBleTransport.value = null`.
4. If `clearAutoReconnect == true` (user-initiated disconnect or transport switch): wipe `lastCanonBleAddress` and `_canonBleReconnecting`. If `false` (spontaneous link drop): preserve both so the auto-reconnect collector can re-pair on re-advertise.

## Capture path (where wizards meet the wire)

The wizards build a `FlowStep`, save it, and `vm.startFlow()` walks the list. For each step, `executeFlowStep` dispatches to the right transport branch. Canon BLE goes through the same `runCanonBulb` / `runCanonTimelapse` / `runCanonRamp` runners as CCAPI and PTP — the runners are transport-agnostic.

### Wire calls per mode

`CanonBleTransport` dispatches on the protocol detected at connect time. The
shutter pattern differs:

| Mode | BR-E1 (older bodies) | Smartphone-mode (R-series) |
|---|---|---|
| Timelapse / single-shot (`fireShutter`) | Per shot: `0x8C` (press) → 200 ms → `0x0C` (release) on `00050003`. AF: `0x4C` → 200 ms → `0x0C` first. | Per shot: `[00,01]` (M-mode **press** event) → 200 ms → `[00,02]` (M-mode **release** event) on `00030030`. **No AF over BLE** (`afToggle=false`). (`smartShutterTap` in `CanonBleClient`.) |
| Intervalometer (bulb) / Astro / DarkFrame | Optional AF half-press, `startBulb` writes `0x8C` → `delay(exposureMs)` → `stopBulb` writes `0x0C`. | `startBulb` writes `[00,01]` → `delay(exposureMs)` → `stopBulb` writes `[00,01]` (a second toggle → closed). Bulb tracks open/closed on `[00,01]` toggles only; `[00,02]` is inert in Bulb. (`smartBulbToggle` in `CanonBleClient`, v0.620 — an earlier `[00,02]`-close regression left the sensor stuck open.) |
| Ramp | Same as bulb; `exposureMs` interpolates linearly. | Same as bulb; `exposureMs` interpolates linearly. |

The smartphone shutter behaves differently by **camera dial setting**, not
just by bytes:

- **Bulb dial:** the camera tracks an open/closed shutter state and flips it
  on each `[00,01]`. `[00,02]` is inert here. A complete bulb op is two
  `[00,01]` toggles back to closed.
- **M (non-bulb) dial:** the camera treats the bytes as **two distinct
  shutter events** — `[00,01]` = press, `[00,02]` = release. Sending two
  `[00,01]`s in M mode re-presses on the second byte and leaves the body
  shooting continuously (verified on EOS RP, v0.288 diagnostics).

Two more smartphone-mode facts, both established on the RP 2026-07-03:

- **Same ~4 s post-frame cooldown as BR-E1.** The RP also eats presses / skips
  frames below ~4 s of quiet between exposures, so `canonBleSafeInterval` clamps
  sub-4 s intervals to 4 s for smartphone mode too (v0.628; the v0.619 attempt to
  exempt smart mode was wrong).
- **No trustworthy readable shutter state.** The `00030031` state char (READ +
  NOTIFY partner of `00030030`) looked like a clean `01`=open / `03`=closed signal
  but its **read is stuck at `010101` regardless of the real state** — acting on it
  fired stray shots. So the safety-close uses the **`bulbOpen` parity flag** (only
  toggles when we believe the shutter is open), never a state read. Same
  "the reads lie" conclusion as BR-E1's `00050004`.
- **Camera Test / dial mismatch:** because a single-shot tap's `[00,01]` toggles
  the Bulb shutter (if the dial is on Bulb) without touching the flag, the Camera
  Test fires its manual phase **twice** on Canon BLE (even `[00,01]` count → the
  shutter returns to closed on either dial), so a wrong-dial manual phase can't
  leave the following bulb phase open (v0.629).

Because the app can't read the dial position over BLE, Pulsar gates the
choice at the UI level: the **Manual** tile drives bulb (camera dial → Bulb),
the **Cable release** tile drives single-shot (camera dial → M). The two
tiles wire to different transport methods (`smartShutter` vs
`smartShutterTap`), so the right byte sequence is sent for the right dial
setting. See [canon-ble-research.md §7](canon-ble-research.md) for the
verification log.

## Wire format primer

### BR-E1 protocol (`00050000`)

Every shutter / focus / video event is a single byte written to the control characteristic (`00050003-…`). The byte packs `mode | button`:

```
Bit:    7 6 5 4 3 2 1 0
        │ │ │ │ └─┴─┴─┴── mode (low nibble)
        │ │ │ └────────── BUTTON_WIDE   (PowerShot zoom out)
        │ │ └──────────── BUTTON_TELE   (PowerShot zoom in)
        │ └────────────── BUTTON_FOCUS  (AF half-press)
        └──────────────── BUTTON_RELEASE (full press)

mode bits (low nibble):
   0x04 = MODE_DELAY      (2-second self-timer)
   0x08 = MODE_MOVIE      (video record toggle)
   0x0C = MODE_IMMEDIATE  (direct release — used for every Pulsar mode)
```

Common byte combos (matches `cbremote`'s `SIGNAL_*` constants — Apache 2.0 ref impl):

| Byte | Meaning |
|---|---|
| `0x0C` | Release everything (button up, in immediate mode) |
| `0x4C` | AF half-press |
| `0x8C` | Shutter full-press, no AF |
| `0xCC` | Shutter full-press with AF |
| `0x88` | Video record toggle |

For bulb, the host writes `0x8C`, sleeps the desired exposure time, then writes `0x0C`. Same press-and-hold pattern a physical BR-E1 hardware remote uses.

### Smartphone-mode protocol (`00010000` + `00030000`)

A richer, two-service protocol used by the R-series. Identity / registration
on `00010000`, shooting on `00030000`. All writes are `WRITE_NO_RESPONSE`.

| Characteristic | UUID | Direction | Payload |
|---|---|---|---|
| `SMART_NAME` | `00010006-…` | write + indicate | Phone-side: `[01, ...ASCII name]`. Camera-side indicate: `0x02` accept / `0x03` reject (fires once the user confirms the pairing on the body). |
| `SMART_IDEN` | `0001000a-…` | write | The registration handshake: `[03, <16-byte UUID>]`, `[04, ...name]`, `[05, 02]`, then `[01]` to finalize after the camera accepts. |
| `SMART_MODE` | `00030010-…` | write | `[0x02]` = `MODE_SHOOT` — switches the body into shooting mode after handshake. |
| `SMART_SHUTTER` | `00030030-…` | write | Two distinct events. **Bulb path:** `[00, 01]` toggles the shutter-open state (open ↔ closed). `[00, 02]` is inert in Bulb. So bulb is `[00,01]` → wait → `[00,01]`. **M (non-bulb) path:** `[00, 01]` = press event, `[00, 02]` = release event. A single shot is `[00,01]` → wait → `[00,02]`; sending the bulb pattern (`[00,01]`/`[00,01]`) in M re-presses on the second toggle and leaves the body shooting continuously. |

Auto-focus is folded into the toggle on the camera side — there's no separate
half-press write. The body uses the focus mode set on the lens / camera at
release time. `MODE_SHOOT` is set once per session and persists until the GATT
link drops or the body switches Bluetooth modes.

## Pairing characteristic (BR-E1)

Written exactly once per new bond. Payload format: `[0x03, ...ASCII name...]`. We use `"Pulsar"` so the camera's paired-devices list shows a recognisable entry.

After the OS bond is established, this characteristic isn't needed again. The smartphone-mode protocol does **not** use this characteristic at all — see the SMART_NAME / SMART_IDEN registration handshake above.

## Auto-reconnect on link drop

Two complementary paths, in priority order:

**1. OS-managed `autoConnect=true` (primary).** `onCanonBleLinkDropped()` fires
from the GATT callback when the link drops post-success, aborts any running
flow (the camera may still be exposing — see [Mid-session disconnect](#mid-session-disconnect)),
releases the old GATT, and re-issues `connectGatt` with `autoConnect=true` and
a 120 s window. Android then completes the connection whenever the bonded body
becomes available — **including via directed advertisements that a service-UUID
scan never sees** (which is how an R-series body re-advertises to its bonded
phone). This is the path that actually reconnects on Eduardo's RP.

**2. Service-UUID scan + collector (fallback).** The viewmodel's init block
watches `canonBleDiscovery.cameras`. The collector reconnects when all of:

- `_canonBleReconnecting.value` is true (we lost the link and want it back)
- `_canonBleTransport.value` is null (we're not already reconnected)
- `_canonBleConnecting.value` is false (the OS-managed reconnect above isn't already in-flight)
- `idleAcrossOtherTransports()` (the user hasn't switched to a different transport)
- A device with `address == lastCanonBleAddress` appears in `cameras`

The arm-write (BR-E1) or smartphone-mode handshake still runs on every connect
but is silent — the OS bond is already in the keystore so no pair dialog
appears, and on the smartphone path `armSmart` shortens the accept wait to 6 s
(the camera doesn't re-prompt on a known-good registration).

`lastCanonBleAddress` is persisted in SharedPrefs (`pulsar_canon_ble.last_address`), so this survives app restart — open the app, walk into BLE range of the previously-bonded body, the scan landing's reconnect card finishes the job on its own.

### Mid-session disconnect

If the link drops while a flow is *running*, `abortFlowOnTransportDrop()`
cancels the flow job, flips `_sessionInterrupted = true`, and the next time the
foreground screen is visible a dialog warns the user that the camera **may
still be holding the exposure** — a bulb that opened before the drop has no way
to be closed remotely. The auto-reconnect still tries to re-establish the link
in the background so the next session can start cleanly.

## Honest caveats

- **Mode dial is on the body.** The protocol can't change shutter speed, aperture, ISO, or exposure mode. Bulb flows require the user to set Bulb on the body's mode dial.
- **Single-byte protocol, no acks.** The camera doesn't notify status. Pulsar trusts that writes landed. In practice GATT's link-level retries make this reliable.
- **No live view.** Star Focus, lens auto-detect, and the battery chip all fall back to "not supported" gates on this transport.
- **The OS pair dialog can be missed.** If the user dismisses the system pair prompt the connect fails with `connect_failed`. Re-trying re-pops the dialog.
- **Two BLE scans share one radio.** Pulsar's ESP32 scan and the Canon BLE scan run concurrently with different filters. Android handles this transparently but each adds to the BLE-scan battery cost; the ESP32 scan is throttled to scan-screen visibility and the Canon BLE scan stops while connected.

## Troubleshooting — "it pairs but won't shoot"

This is the failure mode we hit on the EOS RP + EOS R during bring-up: the Android pair dialog completes, the camera shows "Paired with: Pulsar", but no shutter fires in any mode. Work through these in order.

### 0. Camera-side prerequisites

- **Pick the right Bluetooth mode for your body** (the menu is *Wireless communication → Bluetooth*):
  - **Older bodies** (M50, M200, 6D II, 77D, 200D / SL2, 800D / Rebel T7i, 850D, PowerShots) → **Remote**. Pulsar speaks the BR-E1 remote protocol.
  - **R-series with BLE shutter** (RP, R5, R6, …) → **Smartphone**. Pulsar speaks Canon's smartphone-mode protocol — the only path that fires these bodies over BLE.
  - **EOS R (2018) has no BLE shutter at all** — it registers in smartphone mode but exposes no `00030000` control service. Even Canon's Camera Connect needs Wi-Fi to fire it. Pulsar detects this and reports `no_ble_shutter`; use USB (PTP) or Wi-Fi (CCAPI) instead.
- Body on **Manual + Bulb** on the mode dial if you're testing a bulb mode (Pulsar can't set this remotely — see Honest caveats).
- For a single shot, Timelapse mode is the simplest test (camera owns the exposure).

### 1. Capture the connect + shutter trace

Pulsar keeps an in-app ring buffer of every Canon BLE event — connect,
handshake, arm, every shutter / focus / mode write, and any spontaneous
disconnect. The easiest way to read it:

**Tools → Diagnostics → Copy** (or **Share**).

That gives you a self-contained text file with app/device/Android versions,
the active transport, and the recent wire log — paste it into a bug report
or attach the share-sheet output. The same log lives under the `CanonBleClient`
/ `CanonBleTransport` tags in `adb logcat -s CanonBleClient:* CanonBleTransport:*`
if you'd rather work from the cable.

Connect the camera in Pulsar and fire a single shot (Timelapse, or Manual → tap). You're looking for the `writeControl` / `onCharacteristicWrite` lines:

```
D CanonBleClient: writeControl: sending 0x8C
D CanonBleClient: onCharacteristicWrite[control] status=0 (GATT_SUCCESS)
D CanonBleClient: writeControl: sending 0x0C
D CanonBleClient: onCharacteristicWrite[control] status=0 (GATT_SUCCESS)
```

Interpretation:

| What you see | Meaning | Where the bug is |
|---|---|---|
| `writeControl: sending 0x8C` then `status=0 (GATT_SUCCESS)`, but **no shot fires** | The camera *accepted* the byte at the link layer and then ignored it | Arm / state issue — the body doesn't consider Pulsar the active remote for this session. The `[0x03, name]` arm-write either didn't happen or didn't "take". Check the `CanonBleTransport: connected + armed …` line appears on connect. |
| `onCharacteristicWrite[control] status=` **non-zero** (e.g. 5 = insufficient authentication, 8 = insufficient encryption, 137 = auth failure) | The link **rejected** the write | Encryption / bond / CCCD issue. The bond may not be establishing a secure-enough link, or the control characteristic needs notifications (CCCD) enabled before it accepts writes. |
| `writeControl: writeCharacteristic() returned false` | Android couldn't even queue the write | The GATT link isn't in a writable state — usually a stale/dropped connection. Check for a `spontaneous disconnect` line just before. |
| No `writeControl` line at all when you tap | The wizard never reached the transport | Not a BLE bug — check the mode dispatch (`executeFlowStep`) and that `_canonBleTransport` is the active transport. |

Attach the relevant lines and we can pin it precisely.

### 2. Try a different drive mode on the body

A couple of the open-source references hint that some bodies only honour the immediate-release byte (`0x8C`) when the camera's **drive mode** is set to single shot / remote, and that the self-timer path uses the `MODE_DELAY` (`0x04`) bit instead of `MODE_IMMEDIATE` (`0x0C`).

If immediate release does nothing, set the body's **drive mode to the 2-second self-timer** and try again. If the self-timer fires but immediate release doesn't, that tells us this body wants the `MODE_DELAY` byte for triggering — a per-body quirk we'd handle by sending `0x84` (DELAY | RELEASE) instead of `0x8C`, gated on a body whitelist or a user toggle.

### 3. Confirm the arm-write landed

On connect you should see (tag `CanonBleTransport`):

```
I CanonBleTransport: connected + armed <camera name> (AA:BB:CC:DD:EE:FF)
```

If instead you see `pair/arm write failed … aborting`, the `[0x03, name]` write to the pair characteristic didn't confirm — the camera isn't being armed, so it'll ignore every shutter byte. That points back at the pair characteristic / encryption rather than the control path.

## Future work

1. **Per-family BLE mechanism map.** Today `CanonProtocol` is auto-detected at
   connect time. A small per-family map (`EOS R-series → SMART`, `older →
   BR-E1`, `2018 EOS R → NO_BLE_SHUTTER`) would let the scan card pre-label
   the capability before the user even taps connect, and lets us add
   per-family shutter quirks.
2. **Manual pair-name field** — let the user override `"Pulsar"` so multiple
   phones paired to the same camera are distinguishable in the camera's list.
3. **Multi-camera bonds.** Today `lastCanonBleAddress` is single-valued. Could
   become a list with a "preferred" entry, like CCAPI's per-UDN nickname map.
4. **2-second self-timer mode.** `MODE_DELAY (0x04)` is exposed by the BR-E1
   wire but not surfaced in Pulsar. Useful for tripod-shake suppression on
   single shots; doesn't apply to the smartphone-mode protocol.

## External references

> **Full reverse-engineering log & reference catalog: [canon-ble-research.md](canon-ble-research.md).**
> Every UUID, constant, write semantic, per-project quirk, and the empirical EOS
> R GATT dump + the open "pairs-but-won't-shoot" investigation are captured
> there in detail — so the external projects never need to be re-cloned. The
> live diagnostic driver is in-repo at [`tools/canon_ble_test.py`](../tools/canon_ble_test.py).

Verified against six independent open-source implementations — every byte and UUID below was cross-checked:

- **`maxmacstn/ESP32-Canon-BLE-Remote`** — Arduino / ESP32. Source of the BR-E1 constants in `CanonBleClient`. Tested on EOS M50.
- **`pklaus/canoremote`** — Python `bleak`. README lists the broadest compatibility matrix (EOS R/RP/R5/R6/Ra and many others) — but it's the same simple subset (see research doc §2.4).
- **`iebyt/cbremote`** — Android app (Apache 2.0). Source of the BR-E1 `SIGNAL_*` byte combos used in our bulb implementation. Tested on EOS 200D.
- **`ArthurFDLR/BR-M5`** — M5Stick C+ / PlatformIO. Tested on EOS M50 Mark I.
- **`ids1024/cannon-bluetooth-remote`** — Python via `btgatt-client`. Confirmed BR-E1 handle numbers 0xf504 / 0xf506.
- **`RReverser/eos-remote-web`** — Web Bluetooth (JS). Same simple BR-E1 `0002`+`0003` protocol; touches no extra characteristic.
- **`gkoh/furble`** (GPL-3.0) — ESP32 multi-vendor remote. **Source of the smartphone-mode protocol** (services `00010000` / `00030000`, the identify handshake, MODE_SHOOT). Pulsar empirically verified the `[00,01]` shutter toggle is correct on the EOS RP and that furble's `[00,02]` "release" is inert — both Pulsar press and release send `[00,01]`.

Protocol write-ups:

- **Ian Douglas Scott, "Reverse-engineering the Canon T7i's Bluetooth (work-in-progress)"** — `iandouglasscott.com/2017/09/04/…`. First public dump of the service / characteristic UUIDs.
- **Ian Douglas Scott, "Canon DSLR Bluetooth remote protocol"** — `iandouglasscott.com/2018/07/04/…`. The bit-layout of the control byte.

The clean-room reimplementation in this transport derives the protocol facts from all six and re-expresses them in idiomatic Kotlin. None of those references are GPL-3 — the Apache 2.0 (`cbremote`), MIT (`BR-M5`, `cannon-bluetooth-remote`, `eos-remote-web`), and the unlicensed-but-pedagogical posts are all compatible with Pulsar's GPL-3-or-later relicensing.
