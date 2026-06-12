# SIGNAL — Pulsar Trigger's design system

The visual identity is built on one fact: **the app is named after the thing
CP 1919 is.** The first pulsar's chart-recorder trace — stacked pulse lines,
made famous by the *Unknown Pleasures* cover — is the brand. Carbon surfaces,
one electric accent, and pulse waveforms wherever other apps draw lines.

Reference register: Halide, Flighty, Teenage Engineering, Braun-era
instrument faces. The app should feel like a **precision field instrument**,
not a consumer app: data-forward, calm when idle, alive when running.

## The one law

> **The live gradient (violet → magenta) is reserved for elements that are
> actively doing something.** A run in progress, an armed Start button, the
> best window on a chart. Idle UI never wears it.

Roles: `PulsarTheme.colors.liveStart` / `liveEnd`
(`ui/theme/PulsarColors.kt`). In Phosphor Red night mode they resolve to a
red ramp — the law survives every theme.

## Type — three voices, strict roles

| Voice | Family | Used for | Never for |
|---|---|---|---|
| **Display** | Unbounded | Brand: destination headers, hero labels | Body copy (it shouts at small sizes) |
| **UI** | Space Grotesk | Everything readable: titles, body, labels | — |
| **Telemetry** | JetBrains Mono | Every live number: countdowns, distances, coordinates, scrub digits | Prose |

All bundled as OFL variable fonts in `res/font/` (`ui/theme/Type.kt` —
`Display`, `Grotesk`, `Mono` families; `PulsarTypography` re-voices the
Material scale). Mono digits are inherently tabular — values must not
wobble as they tick. **Any future font change must re-audit fixed-size
numeric rows for width overflow** (mono digits are ~0.6 em vs ~0.5 for the
sans; this clipped the clock editor once, v0.428).

## Color — semantic roles, resolved per mode

Screens may not contain `Color(0x…)` literals. Use:

- `MaterialTheme.colorScheme.*` for surfaces/text (schemes in
  `ui/theme/Theme.kt`: **Carbon** dark `#0C0C0F`, **Chart Paper** light
  `#F7F4EE`, **Outdoor** high-contrast, **Phosphor Red**)
- `PulsarTheme.colors.*` for semantics: positive/negative/caution/critical/
  info, proximity + lighting + badge ramps, sky data-vis ramp, selection,
  trail, liveStart/liveEnd. Each role is defined per ThemeMode; Phosphor Red
  collapses everything onto a red/grey **luminance** ramp (state encoded by
  brightness, not hue). Which mode is active is always the user's choice.

Documented exemptions: emoji glyph `fontSize` scaling, the instrument
display sizes (44–96 sp, above the Material scale), BatteryIndicator's
explicit per-mode `when`, the three 4 dp Dashboard micro-bar clips, and the
home-screen widget's system fonts (Glance can't bundle typefaces — it gets
the schemes via `GlanceTheme(colors = …)` instead).

## Shape & spacing

Radii: **8 / 12 / 20** only (chips / cards / sheets+heroes). Spacing grid:
**4 / 8 / 12 / 16 / 24 / 32**. Touch targets ≥ 48 dp.

## Component kit (one blessed implementation each)

| Component | Job |
|---|---|
| `PulsarTopBar` | Every screen's top bar (MainMenu's root brand bar is the only exception) |
| `StartStopBar` | Every flow-launching screen; armed Start wears the live gradient; 2 dp breathing strip while running |
| `StatPanel` / `StatRow` / `StatHero` | Computed results, tabular mono values |
| `DetailSheet` | All rich-content modals. `AlertDialog` only for true confirm/cancel decisions |
| `EmptyState` | Dimmed icon + line, centred |
| `AppSnackbar` (`LocalSnackbarHost` + `rememberSnackbarPoster`) | The only "something happened" surface. Never `Toast` |
| `PulseDivider` | Section rules — a flat trace with one pulse in it |
| `SignalSweep` | Searching/connecting/probing indicator. Preferred over `CircularProgressIndicator` for new code |
| `PulseScope` | Run history: one pulse per captured frame |
| `CyclePhaseTrace` | The current exposure/gap cycle as a time-proportional waveform with a live playhead |
| `SectionContainer` | Grouped launcher/section panels (carries the PulseDivider) |
| `ToggleRow` / `SliderRow` | Settings-style rows (in the Aircraft Watch files; promote when next needed) |

## Signature moments (don't dilute them)

1. **Tonight's Signal** (`ui/screens/TonightSignal.kt`) — dashboard hero;
   CP 1919 ridgeline of tonight's hours, amplitude = shooting quality
   (twilight darkness × cloud × moon × rain). ⓘ opens the legend sheet.
2. **The oscilloscope run** — `CyclePhaseTrace` (present cycle) above
   `PulseScope` (fired-frame history) in the shared RunningView.
3. **Launch** — pulsar-beacon adaptive icon (white core, tilted gradient
   beams) and the self-drawing-trace splash.

## Brand-mark rule

The pulse-trace language is for **in-app graphics only**. Standalone marks
(launcher icon, store assets) must depict the **celestial object** — a lone
pulse line out of context reads as a heart-rate app. Never place the
adaptive launcher foreground on a coloured surface; use the white-forward
`ic_pulsar_mark` for in-app brand spots.

## Motion

Screens cross-fade with a small rise (180 ms in / 110 ms out, wrapped around
the `AppScreen` dispatch). Live elements breathe at ~1 Hz. New pulses land
with a spring overshoot. Success = one clean double-tick haptic + snackbar —
**never confetti**. Searching states sweep (`SignalSweep`); the arrival of
real UI is the lock.

## Adding UI — checklist

1. Colors via roles only; no literals (build the role first if missing).
2. Numbers → `Mono`; headers → scale slots (Display lives in
   display/headline slots); no ad-hoc `fontSize` except emoji scaling.
3. Radii/spacing from the token scales.
4. Feedback through the snackbar host; rich modals through `DetailSheet`.
5. Strings in all 7 locales.
6. If it launches a flow, it uses `StartStopBar`.
