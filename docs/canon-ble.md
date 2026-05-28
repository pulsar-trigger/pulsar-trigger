# Canon BLE direct transport

Pulsar's fourth transport: drive a Canon body directly over Bluetooth Low Energy using Canon's BR-E1 remote-control protocol. No ESP32 hardware in line, no Wi-Fi setup, no cable — just BLE between the phone and the camera.

Its reason to exist: a fully wireless, hardware-free path on Canon bodies that takes the same `CameraTransport` abstraction as CCAPI and PTP. CCAPI shipped first and remains the most featureful (live view + Star Focus + lens info + battery), PTP-over-USB shipped second to cover the EOS R where CCAPI cannot be activated, and Canon BLE direct now covers the "I just want to wirelessly trigger my Canon from my phone, no cables, no extra gear" case.

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

Verified across five open-source references (see `External references` below). Any body Canon lists in the BR-E1 compatibility matrix should work:

EOS R, RP, R5, R6, Ra, 6D Mark II, 77D, 800D / Rebel T7i, 200D / SL2, 850D, M50, M200, plus the PowerShots G7 X Mark III and G5 X Mark II.

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
| `canonble/CanonBleDiscovery.kt` | `BluetoothLeScanner` filtered on the BR-E1 service UUID. Surfaces matched `BluetoothDevice`s as a `StateFlow`. Vendor-agnostic — relies on the service UUID, not the device's GATT name. |
| `canonble/CanonBleClient.kt` | GATT wrapper. Connect, discover services, pair-write, control-write. Operations serialised through a `Mutex` (Android allows one in-flight GATT op per connection). Spontaneous-disconnect callback invoked when the link drops *after* a successful connect. |
| `canonble/CanonBleTransport.kt` | Implements `CameraTransport`. Owns the bond between Canon BLE and Pulsar's wizards — fireShutter / startBulb / stopBulb / setShutterMode / supportsX flags. |
| `viewmodel/PulsarViewModel.kt` | `connectCanonBle` / `disconnectCanonBle` / mutual-exclusion / `onCanonBleLinkDropped` / auto-reconnect collector / persistence. |
| `ui/screens/ScanScreen.kt` | "Canon BLE remotes" section + `CanonBleCameraCard`. Scan starts on screen-visible (`DisposableEffect`), stops on dispose. |
| `ui/screens/MainMenuScreen.kt` | `CanonBleBanner` on the Trigger tab parallel to `CanonBulbBanner` (CCAPI) and `PtpBanner`. |

## Discovery

`CanonBleDiscovery` runs a `BluetoothLeScanner` with `ScanFilter.setServiceUuid(00050000-…-d8492fffa821)`. Any device advertising that service UUID — i.e. any BR-E1-compatible Canon body currently in pair-or-remote mode — shows up as a `BluetoothDevice` in the `cameras` flow.

No vendor filter beyond the UUID — the service UUID is Canon-specific to BR-E1, so we don't need to also filter by manufacturer.

The scan starts when the user opens the Scan screen (DisposableEffect) and stops when they leave it or successfully connect. After a spontaneous disconnect the viewmodel re-arms the scan in the background so the auto-reconnect collector can pick up the next advertisement.

Concurrent with the Pulsar ESP32 scan (`BleController`) — Android handles multiple `ScanCallback`s with independent filters.

## Connect flow

1. **User taps a Canon BLE card** on the scan screen. `vm.connectCanonBle(device)` enqueues on the viewmodel scope.
2. **Mutual exclusion** — any active BLE-ESP / CCAPI / PTP / simulator session is torn down first.
3. **Scan stop** — `canonBleDiscovery.stop()`. Android can't reliably scan + connect on the same radio.
4. **GATT connect** — `device.connectGatt(ctx, autoConnect=false, callback, TRANSPORT_LE)`.
5. **Service discovery** — `gatt.discoverServices()`; pulls out the pair characteristic (`00050002-…`) + the control characteristic (`00050003-…`).
6. **Arm-write (every connect)** — write `[0x03, ...ASCII "Pulsar"]` to the pair characteristic. **This runs on every connection, not just the first** — it registers Pulsar as the active remote for *this session*. The camera silently ignores control-characteristic writes until it's been armed this way, even when the device is already OS-bonded. (Confirmed against `iebyt/cbremote`, the working Android reference, which writes the same payload in `onServicesDiscovered` every connect.) On the first connect this also triggers Android's MITM pair dialog — the user confirms with the passkey shown on the camera screen, and the bond lands in the OS keystore; later connects reuse the bond and the arm-write is silent.
7. **Persist MAC** — `lastCanonBleAddress` stored in plain SharedPrefs.
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

| Mode | What `CanonBleTransport` does |
|---|---|
| Timelapse (Intervalometer with `TIMELAPSE_PULSE_MS` sentinel) | Per shot: write `0x8C` (full press) → 200 ms delay → write `0x0C` (release). With AF: half-press `0x4C` → 200 ms → release first, then the press/release pair. |
| Intervalometer (bulb) / Astro / DarkFrame | Per shot: optional AF half-press, then `startBulb` writes `0x8C` → host-side `delay(exposureMs)` → `stopBulb` writes `0x0C`. |
| Ramp | Same as bulb but `exposureMs` interpolates linearly across steps. |

## Wire format primer

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

## Pairing characteristic

Written exactly once per new bond. Payload format: `[0x03, ...ASCII name...]`. We use `"Pulsar"` so the camera's paired-devices list shows a recognisable entry.

After the OS bond is established, this characteristic isn't needed again.

## Auto-reconnect on link drop

The viewmodel's init block watches `canonBleDiscovery.cameras`. Conditions for an auto-reconnect:

- `_canonBleReconnecting.value` is true (we lost the link and want it back)
- `_canonBleTransport.value` is null (we're not already reconnected)
- `idleAcrossOtherTransports()` (the user hasn't switched to a different transport)
- A device with `address == lastCanonBleAddress` appears in `cameras`

When all four hold, `connectCanonBle(matchedDevice, auto = true)` runs. The arm-write still runs (it runs every connect) but is silent — the OS bond is already in the keystore so no pair dialog appears.

`lastCanonBleAddress` is persisted in SharedPrefs (`pulsar_canon_ble.last_address`), so this survives app restart — open the app, walk into BLE range of the previously-bonded body, scan screen will reconnect on its own.

## Honest caveats

- **Mode dial is on the body.** The protocol can't change shutter speed, aperture, ISO, or exposure mode. Bulb flows require the user to set Bulb on the body's mode dial.
- **Single-byte protocol, no acks.** The camera doesn't notify status. Pulsar trusts that writes landed. In practice GATT's link-level retries make this reliable.
- **No live view.** Star Focus, lens auto-detect, and the battery chip all fall back to "not supported" gates on this transport.
- **The OS pair dialog can be missed.** If the user dismisses the system pair prompt the connect fails with `connect_failed`. Re-trying re-pops the dialog.
- **Two BLE scans share one radio.** Pulsar's ESP32 scan and the Canon BLE scan run concurrently with different filters. Android handles this transparently but each adds to the BLE-scan battery cost; the ESP32 scan is throttled to scan-screen visibility and the Canon BLE scan stops while connected.

## Troubleshooting — "it pairs but won't shoot"

This is the failure mode we hit on the EOS RP + EOS R during bring-up: the Android pair dialog completes, the camera shows "Paired with: Pulsar", but no shutter fires in any mode. Work through these in order.

### 0. Camera-side prerequisites

- **Bluetooth mode = Remote**, not Smartphone. (Wireless communication → Bluetooth → Remote.) Smartphone mode is for Canon's own Camera Connect app / CCAPI — it does *not* speak the BR-E1 remote protocol Pulsar uses, so a body in Smartphone mode pairs but never shoots.
- Body on **Manual + Bulb** on the mode dial if you're testing a bulb mode (Pulsar can't set this remotely — see Honest caveats).
- For a single shot, Timelapse mode is the simplest test (camera owns the exposure).

### 1. Capture a logcat while you tap the shutter

The control-write path logs under the tag `CanonBleClient`. With the phone plugged into a computer:

```
adb logcat -c                          # clear the buffer first
adb logcat -s CanonBleClient:* CanonBleTransport:*
```

Then connect the camera in Pulsar and fire a single shot (Timelapse, or Manual → tap). You're looking for the `writeControl` / `onCharacteristicWrite` lines:

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

1. **Manual pair-name field** — let the user override `"Pulsar"` so multiple phones paired to the same camera are distinguishable in the camera's list.
2. **Per-body capability gating.** Today supportsBulb is hard-coded true; a PowerShot bulb-via-BLE may not behave the same as a DSLR. If we hit issues, query `device.name` or a whitelist.
3. **Multi-camera bonds.** Today `lastCanonBleAddress` is single-valued. Could become a list with a "preferred" entry, like CCAPI's per-UDN nickname map.
4. **2-second self-timer mode.** `MODE_DELAY (0x04)` is exposed by the wire but not surfaced in Pulsar. Useful for tripod-shake suppression on single shots.

## External references

Verified against five independent open-source implementations — every byte and UUID below was cross-checked:

- **`maxmacstn/ESP32-Canon-BLE-Remote`** — Arduino / ESP32. Source of the constants in `CanonBleClient`. Tested on EOS M50.
- **`pklaus/canoremote`** — Python `bleak`. README lists the broadest compatibility matrix (EOS R/RP/R5/R6/Ra and many others).
- **`iebyt/cbremote`** — Android app (Apache 2.0). Source of the `SIGNAL_*` byte combos used in our bulb implementation. Tested on EOS 200D.
- **`ArthurFDLR/BR-M5`** — M5Stick C+ / PlatformIO. Tested on EOS M50 Mark I.
- **`ids1024/cannon-bluetooth-remote`** — Python via `btgatt-client`. Confirmed handle numbers 0xf504 / 0xf506.

Protocol write-ups:

- **Ian Douglas Scott, "Reverse-engineering the Canon T7i's Bluetooth (work-in-progress)"** — `iandouglasscott.com/2017/09/04/…`. First public dump of the service / characteristic UUIDs.
- **Ian Douglas Scott, "Canon DSLR Bluetooth remote protocol"** — `iandouglasscott.com/2018/07/04/…`. The bit-layout of the control byte.

The clean-room reimplementation in this transport derives the protocol facts from all five and re-expresses them in idiomatic Kotlin. None of those references are GPL-3 — the Apache 2.0 (`cbremote`), MIT (`BR-M5`, `cannon-bluetooth-remote`), and the unlicensed-but-pedagogical posts are all compatible with Pulsar's GPL-3-or-later relicensing.
