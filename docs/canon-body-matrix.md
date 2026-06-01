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
