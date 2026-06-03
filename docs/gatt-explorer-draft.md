# GATT Explorer wizard — design draft

**Status**: planning draft, not yet shipping. Sister-feature to the
**Tools → Camera Test** wizard but with the opposite intent: instead of
firing shots through Pulsar's known protocols, the user drives raw GATT
operations on a Canon (or any) BLE camera so we can map unfamiliar bodies
and grow [canon-body-matrix.md](canon-body-matrix.md).

## Why an in-app tool when `tools/canon_ble_test.py` already exists?

The Python diagnostic driver is the right tool for *deep* RE — full GATT
dumps, blind-write campaigns, indication snapshots around stimuli — but it
needs a Linux box with `bleak`. Most users (Eduardo included) only have a
phone in the field. An in-app GATT Explorer means a community tester with
just an Android device can:

- Connect to a new Canon body that isn't in our supported list.
- Enumerate its GATT tree without `adb logcat` or a separate computer.
- Try a known UUID's typical write pattern and observe the result.
- Share the captured log back to the project as the artifact that
  promotes the body into the *Snapshot* section of the body matrix.

Python tooling stays the authoritative driver for *new-protocol*
discovery; the in-app explorer is the field-friendly companion for
*existing-protocol verification on unsupported bodies*.

## Entry point

- **Settings → Diagnostics → GATT Explorer** (sits next to *Test Camera*
  and *Diagnostics log*).
- Gated behind a **Debug mode** preference (off by default). The toggle
  lives in *Settings → About → Developer options* (new sub-section) — a
  user has to deliberately opt in. The entry stays invisible when the
  toggle is off, so casual users never see it.
- Disabled when there's no BLE bond. Required state: the body must have
  been paired through Android's OS pairing (Pulsar's existing Canon BLE
  scan flow does this). If the body isn't yet bonded, the wizard offers
  a "scan first" sub-step that reuses the existing scan UI with the
  service-UUID filter relaxed to "any device."

The earlier "enter through Simulator" idea is a dead-end — the simulator
has no real GATT. RE needs a real radio target. The right gate is "we
have an OS-level bond" (or "we just scanned and found one"), regardless
of whether Pulsar's known transports can drive that body.

## Wizard flow (5 steps)

The state machine mirrors the **Camera Test** wizard pattern so the
mental model is consistent (Step n of N → Continue → Step n+1).

### Step 1 — Intent picker (orientation)

Two cards:

1. **"Probe a body Pulsar already supports"** — for adding undocumented
   characteristics or verifying a new firmware version's behavior. The
   wizard skips to Step 3 (Tree) using the existing BLE bond.
2. **"Test a body Pulsar doesn't yet support"** — opens the relaxed scan
   in Step 2 (Connect). Service-UUID filter is lifted; the user sees any
   BLE device advertising and picks the camera.

Each card has a one-liner under the title: what you'll see + what a
typical good outcome looks like ("the body's full service tree appears";
"a write to the suspected shutter char triggers a single frame").

### Step 2 — Connect (conditional)

Only shown when the user picked path 2 above, *or* when no bond exists.

- LazyColumn of scan results (re-uses `CanonBleDiscovery` with relaxed
  filter — show every advertising peer).
- Tap a row → Pulsar calls `device.createBond()` and runs a minimal GATT
  connect (no `armSmart` / `armBre1` — we want the raw tree, not a
  protocol-specific handshake).
- On success, advance to Step 3.

### Step 3 — Service / characteristic tree

- Auto-enumerate via `BluetoothGatt.discoverServices()`.
- Render as expand-collapse `Tree`:
  - Service UUID (with the human-readable nickname when known —
    `00010000` → "Canon smartphone-mode identify").
  - Characteristic UUID, with property chips (Read / Write / WriteNR /
    Notify / Indicate).
- Tap a characteristic to drill into Step 4.
- Toolbar action: **"Dump full tree"** writes a structured listing into
  the GATT explorer's log buffer (separate from `CanonBleLog` so the
  RE artifact is self-contained).

### Step 4 — Per-characteristic actions

A single-page panel for the selected characteristic:

- **Read** button (when `READ` is in properties): displays the value as
  hex + best-effort ASCII. Logs `[t] READ <uuid> → <hex>`.
- **Subscribe** toggle (when `NOTIFY` or `INDICATE` is in properties):
  writes the CCCD enable, logs notifications with timestamps as they
  arrive. Toggle off to unsubscribe.
- **Write** form (when `WRITE` or `WRITE_NO_RESPONSE`):
  - Hex input field with live validation (whitespace tolerated, only
    `0-9a-fA-F`, even number of nibbles).
  - "Use WRITE" / "Use WRITE_NO_RESPONSE" radio (defaulted from the
    available property — if both, default to WRITE_NO_RESPONSE since
    Canon's protocols all use it).
  - Send button. Logs `[t] WRITE <uuid> <hex> → <status>`.
- **Known-UUID hints panel**: if the UUID is one we know about, show a
  one-line hint above the action buttons ("Canon smartphone-mode
  shutter; try `00 01` press / `00 02` release") so users without prior
  knowledge can still make productive probes. Table sourced from
  `docs/canon-ble-research.md` UUID inventory.

### Step 5 — Capture + share

- Big "Share findings" button at the bottom of Step 4 (always visible).
- Dumps a structured report:
  ```
  === Pulsar GATT Explorer Report ===
  Time: <ISO 8601>
  Pulsar version: <BuildConfig.VERSION_NAME>
  Body: <name>  MAC: <addr>  Manufacturer/Model (if readable from 0x180A): <…>

  --- Service tree ---
  Service <uuid> [<nickname or "unknown">]
    Char <uuid>  props=R,WNR,N
    Char <uuid>  props=W

  --- Interactions ---
  [t=12.345] READ <uuid> → 01 00 00 00
  [t=15.012] WRITE_NR <uuid> 00 01 → status=0
  [t=15.218] NOTIFY <uuid> 02
  …
  ```
- Goes through `FileProvider` + share intent (the same helper used by
  the Camera Test wizard's share dialog).
- The report is the **artifact that goes back to the project** as an
  issue attachment / PR to `docs/canon-body-matrix.md`.

## Safety rails

- **First-screen disclaimer** (Step 1, before the picker): "This sends
  arbitrary bytes to your camera. Bad writes can put the body in
  unexpected states; some require a power-cycle to recover. Don't run
  this on a camera in the middle of a real shoot."
- **Debug-mode gate** — covered above. Off by default.
- **No "blind-poke all writable chars" button** — users have to
  deliberately tap each char and supply bytes. The Python driver's
  `--poke` mode is left out of the in-app tool.
- **No DFU / firmware-update writes** — if a service-UUID matches a
  known DFU profile we'll grey-out writes to its chars with a warning.
  (Future hardening, not v1.)

## Implementation outline

### New files

- `android/app/src/main/java/com/ehrocha/pulsar/canonble/GattExplorerClient.kt`
  — wraps `BluetoothGatt` for raw operations. Doesn't share state with
  the existing `CanonBleClient` (which is protocol-specific); it's a
  thin wrapper for `discoverServices`, `readCharacteristic`,
  `writeCharacteristic`, `setCharacteristicNotification`, plus a Compose-
  friendly `Flow<GattEvent>` stream.
- `android/app/src/main/java/com/ehrocha/pulsar/ui/screens/GattExplorerScreen.kt`
  — the wizard UI (5 steps).
- `android/app/src/main/java/com/ehrocha/pulsar/canonble/GattExplorerLog.kt`
  — separate ring buffer + share helper (so RE captures don't pollute
  the main `CanonBleLog`).
- `android/app/src/main/java/com/ehrocha/pulsar/canonble/KnownGattUuids.kt`
  — static table mapping UUIDs → nicknames + hint strings. Seeded from
  `docs/canon-ble-research.md` §1–7 + the OEM SIG list (0x180A device
  info, etc.).

### Touching existing code

- `ui/screens/SharedSections.kt` — add `SettingsSection.GATT_EXPLORER`,
  gated behind a new `debugMode: StateFlow<Boolean>` from the viewmodel.
  `DiagnosticsSectionContent` gains a third drill-in row (visible only
  when `debugMode == true`).
- `ui/screens/SharedSections.kt` — add a Developer options sub-section
  in About with the Debug mode switch.
- `viewmodel/PulsarViewModel.kt` — new `debugMode` StateFlow backed by
  SharedPrefs (`pulsar_debug.debug_mode = bool`); two methods
  (`setDebugMode(Boolean)`, `runGattExplorer(...)`) to drive the
  wizard's GATT operations.
- `MainActivity.kt` — new `AppScreen.GattExplorer` data object;
  navigation callback wired from the new Diagnostics drill-in.

### Strings (English-only initially — locale translations at ship)

About a dozen new strings (wizard step titles + bodies, hint table
entries, share-report header text). Adding the locales happens once
the wizard is functionally complete.

## Effort estimate

- `GattExplorerClient.kt`: ~150 lines (GATT callbacks → flow).
- `GattExplorerScreen.kt`: ~300 lines (5 wizard steps + dialogs).
- `GattExplorerLog.kt`: ~50 lines (ring buffer copy of `CanonBleLog`).
- `KnownGattUuids.kt`: ~80 lines (data table).
- Wiring (Settings menu + Mode dispatch + nav): ~50 lines.
- English strings: ~15 entries.
- **Total**: ~600 lines of Kotlin + ~15 strings.

Locale translations come at ship: ~15 strings × 6 locales = 90 entries.

Manual testing: pair an EOS RP, walk through the wizard, write `[00,01]`
to `00030030`, verify a single shot fires + the report captures
correctly.

## Open questions for Eduardo

1. **Should the explorer's bond stack on top of an existing Canon BLE
   session, or replace it?** Replace is simpler; stacking would let
   you compare known-good protocol writes against raw probes side by
   side. Lean: replace.
2. **Hex input format**: free-form (`00 01 02` / `0001 02` / `000102`
   all equivalent) or strict? Lean: free-form with normalisation.
3. **Subscribe-log size cap**: the `GattExplorerLog` ring buffer needs
   an upper bound to prevent OOM if the user subscribes to a noisy
   indicate channel. 1000 entries should be plenty; configurable
   later if needed.
4. **CCCD writes** — Android handles these automatically when you call
   `setCharacteristicNotification(...)`. Worth surfacing in the log so
   testers see the full picture? Lean: yes, log them.

These can shift as we build — capturing here so they're not lost.
