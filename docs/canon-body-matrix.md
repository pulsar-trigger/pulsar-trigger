# Canon body × transport compatibility matrix

Seeded by Compatibility Report runs (Tools → Compatibility Report; see
[CompatibilityReport.kt](../android/app/src/main/java/com/ehrocha/pulsar/transport/CompatibilityReport.kt)).
The probe is read-only — no shutter releases, no property writes — so this
table can be extended safely by community testers.

## Snapshot — 2026-06-01

| Body | Firmware | Canon BLE | USB PTP | Wi-Fi PTP/IP | CCAPI |
|---|---|---|---|---|---|
| **EOS R** (2018) | 3-1.8.0 | `SMART_NO_SHUTTER` — pairs but no shutter control surface | Bulb ✓ · Live view ✗ · Battery ✗ · Lens ✗ | Bulb ✓ · Live view ✗ · Battery ✗ · Lens ✗ | Does not activate |
| **EOS RP** | 3-1.6.0 | `SMART` — full shutter (`name+iden+mode+shutter` all true). Canonical smart-mode body. | Bulb ✓ · Live view ✗ · Battery ✗ · Lens ✗ | Bulb ✓ (cable release verified) · Live view ✗ · Battery ✗ · Lens ✗ | ✓ connects; live view inconclusive (no lens mounted at test time) |

Legend — ✓ works, ✗ advertised by `GetDeviceInfo` but rejected at runtime.

## R-series PTP firmware traits

Both R and RP exhibit the **same false-positive PTP capability
advertisements**. This is a Canon R-series PTP firmware behaviour, not a
single-body bug:

- `0x9153 GetViewFinderData` is in `supportedOperations` so
  `supportsLiveView` initialises true. The follow-up `SetDevicePropValue`
  on `0xD1B0 EVF_OUTPUT = 2` returns `rc=0x200A PARAMETER_NOT_SUPPORTED`.
  Live view is unreachable over PTP on these bodies (either wire).
- `0x5001 BatteryLevel` is in `supportedDeviceProperties` so
  `supportsBatteryReadout` initialises true. `GetDevicePropValue(0x5001)`
  returns `rc=0x2005 DEVICE_PROP_NOT_SUPPORTED`. Battery is unreachable
  over PTP.
- `0xD157 LensName` is **not** in `supportedDeviceProperties`. Lens
  auto-fill via PTP is therefore inert; the Astro "Detect lens" button
  hides automatically.
- `vendorExtensionId = 6` (MTP) on **both** USB and Wi-Fi — the earlier
  assumption that USB reports 11 was wrong. Canon's manufacturer string
  is the reliable gate, not the vendor-extension id.

Pulsar downgrades `supportsLiveView` / `supportsBatteryReadout` at
runtime (since v0.322) the first time the body returns one of the
"not supported" rc codes, so the Star Focus tile and battery poll loop
self-correct on these bodies after the first probe.

## R-series shutter trait

Both bodies require `RemoteReleaseOn(mode=3)` (with-AF press) over PTP/IP.
`mode=2` (no-AF press) returns `rc=0x2019 DEVICE_BUSY`. The AF toggle in
the wizards is hidden on PTP/IP (`supportsAfToggle = false`) and the
transport forces `mode=3` on the wire — see
[PtpIpTransport.kt](../android/app/src/main/java/com/ehrocha/pulsar/ptp/PtpIpTransport.kt).
USB PTP on the same bodies *does* accept `mode=2`, so the toggle works
there.

## Canon BLE direct trait

The 2018 **EOS R** advertises the smartphone-mode pairing service
(`00010000`) but exposes **no shutter control characteristic** —
detected at connect time as `SMART_NO_SHUTTER`. Use USB PTP or Wi-Fi
PTP/IP on this body; the BLE path is for pairing only.

The **EOS RP** is the canonical full-smart-shutter body: `SMART` mode
with all four characteristics (`name`, `iden`, `mode`, `shutter`). Bulb
+ M fire reliably; AF toggle is not honored at the wire level on smart
mode (Pulsar hides the toggle when `isSmart=true`).

> **✅ Pulsar-confirmed on hardware (2026-07-03):** **EOS RP** (smartphone) and
> **EOS R6** (BOTH BR-E1/Remote *and* smartphone mode) pass the full Camera Test
> (all bulb modes + manual + timelapse), and the **EOS R** (BR-E1/Remote) too.
> BR-E1/Remote needs the camera's **drive mode = Remote** to fire; smartphone mode
> fires regardless. See `canon-ble-bulb-status.md` for the confirmed matrix.

## Expected to work — smartphone-mode BLE (research, mostly not Pulsar-tested)

Cross-referenced 2026-06-03 from
[intervalometer.app](https://intervalometer.app/) — a commercial competitor
that ships dedicated per-body BLE setup guides. **All 22 bodies they
support use Canon's smartphone-mode** BLE protocol (service `00010000` +
control `00030000`), which is the same path Pulsar's `CanonBleTransport`
arms in smart mode. The body has to be in *Connect to smartphone* on the
Canon menu — pair via Pulsar, decline the QR code with `[Do not display]`
(Pulsar is already installed). **None of the 22 use BR-E1** in this flow.

Treat the table below as **"strong prior" rather than confirmed** — these
bodies should work but haven't been probed by Pulsar's
[CompatibilityReport.kt](../android/app/src/main/java/com/ehrocha/pulsar/transport/CompatibilityReport.kt).
Run the report and PR a row up to the *Snapshot* table above when you
verify one.

Canon ships several different menu layouts depending on body generation /
class. Pulsar's pair flow is the same; only the in-camera menu path
differs. Five patterns observed:

### Pattern A — pro DSLR `[Network settings]`

| Body | Menu path on the camera |
|---|---|
| EOS 1D X Mark III | `Network settings` → `Bluetooth settings` → `Pairing` |

### Pattern B — newer R-series dedicated `[Bluetooth settings]`

| Body | Menu path on the camera |
|---|---|
| EOS R3 | `MENU` → `Bluetooth settings` → `Smartphone` → `Pairing` |
| EOS R5 | `Wireless features: Wi-Fi settings` → `Bluetooth settings` → `Wi-Fi/Bluetooth connection` → `Connect to smartphone` → `Pair via Bluetooth` |
| EOS R6 Mark II | `Connect to smartphone(tablet)` → `Add a device to connect to` |
| EOS R8 | `Connect to smartphone(tablet)` → `Add a device to connect to` |
| EOS RP | `Wireless communication settings` → `Bluetooth function` → `Smartphone` → `Pairing` (confirmed in *Snapshot* above) |

### Pattern C — combined `[Wi-Fi/Bluetooth connection]` (most R / mid-range DSLR / newer M)

| Body | Menu path on the camera |
|---|---|
| EOS R6 | `Wi-Fi/Bluetooth connection` → `Connect to smartphone` → `Pair via Bluetooth` |
| EOS R7 | same |
| EOS R10 | same |
| EOS R50 | `Connect to smartphone(tablet)` → `Add a device to connect to` |
| EOS R100 | `Wireless settings: Wi-Fi/Bluetooth connection` → `Connect to smartphone` → `Pair via Bluetooth` |
| EOS M50 Mark II | `Wireless settings: Wi-Fi/Bluetooth connection` → `Connect to smartphone` → `Pair via Bluetooth` |
| EOS M6 Mark II | same |
| EOS M200 | same |
| EOS 90D | same |
| EOS 200D Mark II | same |
| EOS 250D | same |
| EOS 850D | `Wireless settings: Wi-Fi/Bluetooth connection` → `Connect to smartphone` → `Pair via Bluetooth` |

### Pattern D — older `[Wireless communication settings]` → `[Bluetooth function]` → `[Smartphone]`

| Body | Menu path on the camera |
|---|---|
| EOS M50 | `Wireless communication settings` → `Bluetooth function` → `Bluetooth function` → `Smartphone` |
| PowerShot G5 X Mark II | `Wireless communication settings` → `Bluetooth function` → `Bluetooth function` → `Smartphone` → `Pairing` |
| PowerShot G7 X Mark III | same pattern, slight wording variations |

### Pattern E — touchscreen `[Wireless settings]` → `[Bluetooth settings]`

| Body | Menu path on the camera |
|---|---|
| EOS M6 | `Wireless settings` → `Bluetooth settings` → `Pairing` |
| PowerShot G9 X Mark II | `Wireless settings` → `Bluetooth settings` → `Pairing` (touchscreen) |

### Cross-cutting observations

- **QR-code skip is universal**: every body shows a QR code during first-pair and expects the user to pick `[Do not display]` since the app is already installed. Worth surfacing in Pulsar's BLE setup screen.
- **Dual confirmation is universal**: phone confirms Bluetooth pair dialog, then user confirms on the body. No quirks beyond timing.
- **Conflicting Canon-app warning**: intervalometer.app calls out that Canon's own Camera Connect app should be closed before connecting. Same applies to Pulsar — worth noting in the setup guide.
- **No BR-E1 usage anywhere**: intervalometer.app appears to have entirely skipped the BR-E1 protocol. Pulsar's BR-E1 path (`CanonBleClient` BR-E1 mode) remains useful only for pre-2018 bodies that don't support smartphone-mode (5D Mark IV, 6D Mark II, T7i, etc., which intervalometer.app doesn't list).

### Conspicuous absence — original EOS R (2018)

intervalometer.app's catalog lists **no first-gen EOS R** guide. That's
external validation of the local finding: the 2018 EOS R registers over
smartphone-mode BLE but exposes no shutter characteristic
(`SMART_NO_SHUTTER` — see *Snapshot* row). Use USB PTP or Wi-Fi PTP/IP on
that body; the BLE path is for pairing only.

### When BR-E1 protocol still matters

The 22 bodies above all use smartphone-mode. **BR-E1 remote-mode**
(service `00050000`) remains the only BLE path on older Canon bodies that
either predate smartphone-mode or don't expose it to third parties:

- DSLRs older than the 1D X Mark III / 90D era — 5D Mark IV, 6D Mark II,
  7D Mark II, 80D, 77D, 800D, T7i, T8i
- Anywhere the user has a physical BR-E1 remote paired and wants Pulsar
  to drive the same wire

Pulsar auto-detects protocol at connect time (see
[canon-ble.md](canon-ble.md) — "Two protocols (auto-detected)"); no
user-visible toggle.

## How to extend this table

Run **Tools → Compatibility Report** on each transport that connects to
the body, then **Tools → Collect Diagnostics → Share**. The diagnostics
file contains a `── Compatibility report ──` block per run. Open an
issue with the file attached, or paste the relevant block.

Useful extensions: any R-series body not in the table above (R5, R6,
R7, R8, R10, R50, Ra, R5 C), and any pre-R Wi-Fi-PTP-capable body that
supports "Remote Control (EOS Utility)" Wi-Fi mode (5D Mark IV, 6D
Mark II, 7D Mark II, 90D, 80D, 77D, 800D).

The two questions per body that matter most:

1. **Does PTP live view actually work?** (the EOS R + RP false-positive
   suggests other R-series might too — needs verification per model)
2. **Does `RemoteReleaseOn(mode=2)` work?** (the R / RP rejection over
   Wi-Fi PTP forces mode=3; bodies that accept mode=2 can keep the AF
   toggle wired)
