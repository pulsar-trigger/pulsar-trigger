# Refactor Plan

> Status: **draft for review.** Eight phases, each shippable on its own.
> Pick which ones to do in which order; nothing here is mandatory.

The premise from `docs/mode-schema.md` and the architectural reflection: the
firmware is sound, the app has accumulated debt. Patch ambitiously rather
than rewrite. Each phase below leaves the app in a working, shippable state
when it ends.

## Phase 1 — `FlowStep` → sealed hierarchy

**Goal.** Replace the union-struct `FlowStep` (every type's fields stored
as siblings with sentinel defaults) with a sealed class. Each variant
carries only the fields it actually uses.

**Why.** Today you can construct `FlowStep(type = PAUSE, rampSteps = 50,
darkFrameCount = 20)`. It compiles, persists, and runs through whichever
branch of `executeFlowStep` matches `type`. The sentinel fields are dead
state, but they're loaded, serialised, validated, and rendered by accident
in summary text more than once. A sealed hierarchy makes invalid states
unrepresentable and forces exhaustive `when` blocks at every consumer.

**Shape.**

```kotlin
sealed class FlowStep {
    abstract val id: String

    data class Intervalometer(override val id: String, val intervalMs: Long,
        val exposureMs: Long, val shotCount: Int, val delayMs: Long) : FlowStep()
    data class Astro(override val id: String, val focalLength: Int,
        val cropFactor: Float, val ruleDivisor: Int, val gapMs: Long,
        val shotCount: Int, val delayMs: Long) : FlowStep()
    data class DarkFrame(override val id: String, val exposureMs: Long,
        val shotCount: Int, val gapMs: Long) : FlowStep()
    data class Ramp(override val id: String, val startExposureMs: Long,
        val endExposureMs: Long, val steps: Int, val intervalMs: Long) : FlowStep()
    data class Pause(override val id: String, val label: String,
        val wakeOnPause: Boolean) : FlowStep()
}
```

**Scope.**
- `FlowStep.kt` rewritten.
- JSON serialisation: discriminator key stays `type`; per-variant fields
  written/read in matching branches.
- `PulsarViewModel.executeFlowStep` becomes an exhaustive `when`.
- `loadQuickMode`, `saveFlowSteps`, `saveFlowAs` and friends adapt.
- UI consumers: `ControlScreen` (panel content per type), `CustomFlowScreen`
  (step editor), `ModeScreen`, `summaryLabel`.
- `FlowPresets` (astro NPF presets, dark-frame presets) adapt.

**Blast radius.** ~10–12 files. Mechanical changes — the type system
points at every site.

**Verify.** Existing unit tests pass; flows save/load round-trip cleanly;
each step type still runs against the simulator.

**Effort.** Half a day to a day.

**Shippable after.** Yes.

## Phase 2 — `BleController` extraction

**Goal.** Pull BLE scanning, connection, send/receive, and OTA forwarding
out of `PulsarViewModel` into a dedicated class. Viewmodel keeps state
flows for UI consumption; `BleController` is the only thing that talks to
`BleManager` / `BluetoothAdapter`.

**Why.** The viewmodel is ~2000 lines and growing. Phone-camera removal
took 200 lines of surgery exactly because connection-state logic was woven
through every method. A clean controller means future "connection types"
(BLE, simulator, future Wi-Fi pulsar?) plug in without rewriting calls.

**Shape.**

```kotlin
class BleController(context: Context) {
    val scanning: StateFlow<Boolean>
    val devices: StateFlow<List<DiscoveredDevice>>
    val connected: StateFlow<Boolean>
    val status: StateFlow<StatusFrame?>
    val rssi: StateFlow<Int>

    fun startScan(); fun stopScan()
    fun connect(device: BluetoothDevice); fun disconnect()
    fun sendCommand(packet: ByteArray)
    fun requestCacheRefresh()
}
```

`PulsarViewModel` constructs one of these and exposes its flows; methods
like `start()`, `stop()`, `sendModeCommand` delegate through it.

**Scope.**
- New `ble/BleController.kt`.
- Move ~600 lines out of `PulsarViewModel` (scan + connect + status frame
  decode + sendCommand wrappers).
- Viewmodel becomes a coordinator: flow runner + mode state + persistence.

**Blast radius.** ~3 files (`PulsarViewModel`, new `BleController`,
`BleManager` if its interface drifts). UI is untouched because flow
signatures are preserved.

**Verify.** Connect, run modes, OTA, disconnect — all unchanged behaviour.
Unit tests on `BleController` with a fake `BleManager`.

**Effort.** 1–2 days.

**Shippable after.** Yes.

## Phase 3 — Canonical `RunState`

**Goal.** Single `StateFlow<RunState>` derived from inputs (BLE status frame,
simulator state, flow runner state), replacing the parallel state holders
(`_flowRunning`, `_flowPaused`, `_status`, `flowJob`, `simulatorJob`) the
viewmodel currently keeps consistent by hand.

**Why.** Right now four flags have to agree about "are we running" and the
race conditions are subtle. We saw symptoms (state stale across disconnect,
button enable/disable lagging the real state). A derived state flow makes
truth single-sourced and impossible to drift.

**Shape.**

```kotlin
sealed class RunState {
    data object Idle : RunState()
    data class Waiting(val timeRemainingMs: Long, val shotsTaken: Int) : RunState()
    data class Running(val currentStep: Int, val totalSteps: Int,
        val timeRemainingMs: Long, val shotsTaken: Int) : RunState()
    data class Paused(val stepLabel: String) : RunState()
    data class Error(val code: Int) : RunState()
}
```

Combined from `bleController.status` + `flowRunner.state` + `simulator.state`
via `combine { ... }`.

**Scope.**
- New `RunState` type.
- Viewmodel exposes one `runState: StateFlow<RunState>`.
- UI consumers (`HeroSummary`, mode info panels, the running overlay) read
  one flow instead of three.

**Blast radius.** ~6–8 UI files plus viewmodel.

**Verify.** Existing flows behave the same; UI updates in the same places
they did before.

**Effort.** 1 day. Depends on Phase 2.

**Shippable after.** Yes.

## Phase 4 — Distinct mode IDs

**Goal.** Stop overloading firmware mode `0x01` for INTERVALOMETER and
ASTRO. Assign every named mode its own id end to end.

**Why.** The `CLAUDE.md` Key Gotchas section says "ASTRO and INTERVALOMETER
share firmware mode 0x01 — distinction is app-side only via
`vm.currentMode`." That shortcut surfaces in every log, every grep, every
new feature that has to disambiguate. Cheap to fix.

**Shape.**
- `protocol.h`: new `MODE_ASTRO = 0x03` (already exists but unused on the
  wire — make it the actual ID).
- Firmware: `set_mode` switches on the new id; INTERVALOMETER stays 0x01,
  ASTRO becomes 0x03 with its own parsing branch.
- App: `CommandBuilder.setAstro` sends 0x03; `TriggerMode.ASTRO.id` updates.
- Status frame's `mode` byte reports the actual id.

**Compatibility.** Old firmware doesn't know 0x03 → old fw + new app won't
work for astro until firmware is updated. Pair with Phase 5 (TLV) so this
becomes "everything that talks the new protocol uses the new IDs."

**Blast radius.** 3 firmware files, 3 app files.

**Verify.** Real device + simulator. Astro mode fires the expected exposure
times and shot counts.

**Effort.** Half a day.

**Shippable after.** Yes, paired with a firmware OTA bump.

## Phase 5 — TLV protocol + version byte

**Goal.** Replace the fixed 20-byte BLE packets with a self-describing
TLV payload (`[opcode][version][len][TLV...]`). New fields don't break old
clients; old fields can be deprecated without rebuilding everyone.

**Why.** Every new parameter we've added since the start has required
coordinated firmware+app updates. The 20-byte ceiling is approaching:
adding focus distance, sub-style hints, or any future-proof field requires
robbing existing bits. Self-describing encoding is a one-time cost that
pays back forever.

**Shape (sketch).**

```
[opcode:1][protoVersion:1][payloadLen:1][TLV...]

TLV: [tag:1][len:1][value:len]

Common tags:
  0x01 INTERVAL_MS    u32
  0x02 EXPOSURE_MS    u32
  0x03 SHOT_COUNT     u16
  0x04 DELAY_MS       u32
  0x05 FOCAL_LENGTH   u16
  ...
```

App refuses to talk to firmware that reports a lower `protoVersion` than
it understands (and vice versa) — with a clear "please update firmware"
dialog.

**Status frame** gets the same treatment: opcode + version + TLV.

**Compatibility.** One-release deprecation: app speaks both old and new
for one version, then the old framing is dropped. This is the *only* phase
worth breaking compat over.

**Scope.**
- `protocol.h` + `triggers.cpp` rewrite of the framing layer.
- `Protocol.kt` + `CommandBuilder.kt` rewrite on the app side.
- Status frame parser adapts.
- Version negotiation on connect (or at OTA-check time).

**Blast radius.** Significant on the protocol layer; UI untouched.

**Verify.** Round-trip tests with a fake `BleManager`. Real-device pairing
between old/new app and old/new firmware in all four combinations.

**Effort.** 3–5 days. Pair with Phase 4 — same protocol revision.

**Shippable after.** Yes, after a coordinated firmware+app release.

## Phase 6 — Strangler-fig mode runtime

**Goal.** Built-in modes (today still `start*` functions in the viewmodel
for `INTERVALOMETER`/`ASTRO`/etc.) gradually migrate onto the same
JSON-mode runtime as user modes. New code goes through the new path;
old built-ins stay on Kotlin until they need to change anyway.

**Why.** The 9-step "add a new mode" recipe in `CLAUDE.md` is the symptom.
Every mode redesign (Auto Astro had three) needed code changes in nine
places. A data-driven runtime makes new modes a single JSON file.

**Shape.**
- The user-modes runtime already exists (`UserModeRepository`, the editor
  screen, the BLE dispatch in `runUserMode`).
- Ship built-in mode templates as JSON assets in `app/src/main/assets/`.
- `FlowPresets` becomes data — read from JSON on first run, cached after.
- When a built-in needs to be edited, port it to the JSON path and delete
  its Kotlin generator. No "big bang."

**Scope.**
- `assets/modes/*.json` for each built-in.
- `FlowPresets.kt` loads from assets instead of constructing in code.
- New code never adds a `start*` function on the viewmodel.

**Blast radius.** Adding files; subtracting them as built-ins migrate.
Old viewmodel functions live until they're naturally retired.

**Verify.** Each migrated built-in runs identically to its Kotlin
predecessor.

**Effort.** 1 day to set up the asset-loading path; per built-in
migration is hours of work.

**Shippable after.** Yes — incremental.

## Phase 7 — Test the timing-sensitive paths

**Goal.** Unit tests covering the flow runner state machine, status-frame
decoder, and reconnect/state-restore logic. Use a virtual clock and a fake
`BleManager`.

**Why.** Every bug we've spent real time on (Auto Astro stalls, AE thrash,
30 s timeout discovery) was reachable from a test fixture but invisible to
the existing unit tests, which cover plain data classes. The most-fragile
paths have the least coverage — exactly inverted.

**Scope.**
- `FakeBleManager` that drives `StatusFrame` notifications on a schedule.
- `TestScope` + `runTest` for time control in flow-runner tests.
- Cases: happy path, mid-flow disconnect, stop during waiting, pause
  resume, error frame during running, simulator equivalence.

**Blast radius.** Test code only. Catches regressions in the others.

**Verify.** Tests pass on CI. Mutation testing (manually break a state
transition, confirm a test fails) for the critical paths.

**Effort.** 2–3 days, ideally spread across Phases 1–5 as you go (each
phase adds tests for the surface it touches).

**Shippable after.** Always.

## Phase 8 — Translation tooling

**Goal.** Stop hand-syncing six locale files. One source of truth, generated
output.

**Why.** Strings drift silently. The `feedback_update_all_languages` rule
exists because this is a known failure mode. A few hundred strings is not a
human problem.

**Shape.** Choose one:
- (a) Drop locales we don't actively maintain. Keep `values/` and one or
  two; let Android fall back. Smallest change.
- (b) Generate translations at build time from a master CSV/YAML via a
  Gradle task. Still hand-edited per locale, but in one place.
- (c) Wire to an LLM translation pass on CI. Honest but new infrastructure.

Most likely answer: (a) for the locales nobody complains about, (b) for the
ones that matter.

**Scope.** Build script + one consolidation pass.

**Blast radius.** Build pipeline. Localised strings stay correct.

**Effort.** Half a day to a day.

**Shippable after.** Yes.

## Suggested order

Phases 2, 3, and 6 build on Phase 1. Phases 4 and 5 should pair (one
firmware-protocol revision, not two). Tests (Phase 7) sprinkle throughout.
Phase 8 is cosmetic — defer.

Roughly:

1. Phase 1 — FlowStep sealed hierarchy. *(starting now)*
2. Phase 2 — BleController extraction.
3. Phase 3 — Canonical RunState.
4. Phase 6 — Strangler-fig mode runtime (parallel with Phases 4/5).
5. Phases 4 + 5 — Distinct mode IDs + TLV protocol (one firmware OTA).
6. Phase 7 — Tests (incremental throughout; final hardening pass at end).
7. Phase 8 — Translation tooling (whenever).

End state: a viewmodel that coordinates rather than implements, a protocol
that survives feature growth, a mode system that's data, and tests on the
parts that have bitten before.

## Out of scope

- Rewriting firmware. It's small, sound, and battery-tested.
- Reintroducing the phone-camera capture path.
- Major Compose UI redesign. Visual changes happen ad-hoc as we touch
  screens; no big rework planned here.
- A new persistence layer. SharedPreferences + JSON works fine at this
  scale.
