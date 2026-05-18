# Mode Definitions — JSON Schema

## Goal

Let users author, save, share, and run their own trigger-mode presets. A
"user mode" is a named, single-step preset (one firmware/CCAPI mode + its
parameters). Bookmarked presets appear as tiles on the Trigger tab; the
rest live in the per-mode `PresetPickerScreen`. They run through the same
flow runner the built-in modes use — same code path against BLE, simulator,
or CCAPI.

## Where user modes live in the UI

Presets are accessed via the per-mode `PresetPickerScreen` (one picker per
`fwMode`). Each row: name, parameter summary, bookmark toggle, edit/delete
actions. Bookmarking surfaces the preset as a tile on the home Trigger
tab. Tapping a preset opens the wizard for that mode pre-filled with the
saved values.

Running a preset goes through the active transport — BLE if a Pulsar
trigger is connected, CCAPI if a Canon body is connected, simulator
otherwise. The flow runner doesn't care.

## Top-level envelope

Every exported mode file is a single JSON object:

```json
{
  "schema": "pulsar-mode/1",
  "kind": "trigger",
  "id": "uuid",
  "name": "My intervalometer recipe",
  "description": "Optional note shown in the import dialog.",
  "tags": ["timelapse", "shared"],
  "body": {
    "fwMode": "INTERVALOMETER" | "ASTRO" | "TIMELAPSE" | "DARK_FRAME" | "RAMP",
    "params": { ... }
  }
}
```

`schema` is `<id>/<version>`. The runtime rejects unknown ids; loaded JSON
that omits `id` gets a generated UUID. `kind` is always `"trigger"` in this
version (only one kind today; the field is reserved for future expansion).

Multi-mode export wraps an array under a bundle envelope:

```json
{ "schema": "pulsar-mode-bundle/1", "modes": [ <envelope>, <envelope>, ... ] }
```

## `params` by `fwMode`

All five preset-able modes share `intervalMs`, `exposureMs`, `shotCount`,
`delayMs`. Some carry extras:

| `fwMode`         | Required                                           | Extras                                                  |
| ---------------- | -------------------------------------------------- | ------------------------------------------------------- |
| `INTERVALOMETER` | `intervalMs`, `exposureMs`, `shotCount`, `delayMs` | —                                                       |
| `ASTRO`          | same                                               | `focalLength`, `cropFactor`, `ruleDivisor`              |
| `TIMELAPSE`      | same (`exposureMs` is the pulse sentinel)          | —                                                       |
| `DARK_FRAME`     | same                                               | —                                                       |
| `RAMP`           | same                                               | `rampStartExposureMs`, `rampEndExposureMs`, `rampSteps` |

`PRESS_HOLD`, `PRESS_LOCK`, `TRACKER`, `CUSTOM_FLOW` aren't representable as
presets (imperative or app-orchestrated). Import rejects them.

`TIMELAPSE` is the only mode where `exposureMs` is a sentinel rather than a
real exposure value — the camera owns timing (set its own shutter speed)
and Pulsar just pulses the shutter on schedule. On CCAPI this dispatches
through `runCanonTimelapse` (single-shot `POST /shutterbutton`); the other
bulb-based modes use `runCanonBulb` (full_press / wait / release).

Sample full envelope:

```json
{
  "schema": "pulsar-mode/1",
  "kind": "trigger",
  "name": "Bulb timelapse",
  "tags": ["timelapse"],
  "body": {
    "fwMode": "INTERVALOMETER",
    "params": {
      "intervalMs": 30000,
      "exposureMs": 5000,
      "shotCount": 200,
      "delayMs": 0
    }
  }
}
```

## Validator

Run on import before anything is persisted:

- Every numeric clamped to its range in `firmware/include/config.h`
  (`MIN_INTERVAL_MS..MAX_INTERVAL_MS`, etc.). Out-of-range values are
  clamped silently — the firmware enforces the same floors anyway.
- Unknown `fwMode` → reject.
- `schema` mismatch → reject.
- Unknown top-level / params keys → ignored.
- Imports cap the total at `UserMode.MAX_USER_MODES` (5). Bundle imports
  beyond the cap are accepted up to the limit; rest are dropped with a
  user-visible toast.

## Storage

User modes live in `SharedPreferences` ("pulsar_user_modes" / "modes") as a
JSON array. The repository's `upsert` / `remove` / `reorder` operations are
all read-modify-write — single source of truth, no in-memory drift between
the viewmodel and disk.

## Open questions

1. **One file per mode, or bundled?** Schema supports both. UX leans
   single-file for sharing (DM / AirDrop), bundles for "here are all my
   recipes." Final pick TBD.
2. **Edit imported modes in place?** v1 says no — imported is read-only,
   "Duplicate to edit" creates a writable copy.
3. **Sharing transport?** Filesystem (`ACTION_OPEN_DOCUMENT`) is the easy
   path. URL-encoded shareable links and QR codes are nice but additive.
