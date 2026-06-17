# Parked audit items

Items from the v0.364 full-codebase audit that we chose **not** to ship in
the v0.365 + v0.366 fix bundles. Recorded here so they don't get lost — if
a real symptom shows up (the "bitten in the ass" trigger), this is the
starting point.

Each item lists: *what the audit flagged*, *why we parked it*, *what to
watch for*, and *the cheapest fix when we do tackle it*.

> Last reviewed: v0.463 (2026-06-16). Phase 1 cleanup shipped: the
> `menuAnchor()` + `Geocoder` deprecations are DONE, and O2 is retired
> (retracted by the audit author). Remaining items below are trigger-only;
> the Map Annotations deprecation is now its own scoped item (it spans
> `MapLocationPicker` **and** Aircraft Watch, and doubles as a perf win).

---

## R3 — Further `disconnect()` extraction — ⏸️ confirmed parked

> **Re-reviewed 2026-06-16 against current code:** `disconnect()` is a clean,
> well-commented 5-branch `when` over the active transport, each calling a
> genuinely different `disconnectXxx()`. There is no duplication to remove —
> extracting it would just hide the switch in a helper. Stays parked.


**Audit claim:** Four near-identical transport-disconnect blocks in
`PulsarViewModel.disconnect()` — brittle, easy to miss a field if a new
flag is added later.

**Why parked:** R1 (v0.365) already extracted `cancelRunningFlowSync()`,
which was the dangerous part (the race window). What's left is a
`when (...) { ... }` switch over the active transport, where each branch
calls a genuinely different `disconnectXxx()` method. That's a switch, not
duplication.

**Trigger to revisit:** If we add a sixth transport, or if a future
`_flowState` field gets added and is forgotten in one branch (regression
caught in code review or by a user-reported state-leak between sessions).

**Cheapest fix:** Move the per-transport `disconnectXxx()` dispatch into
a `private fun teardownActiveTransport()` helper that the `when` calls
into. Net diff: ~10 lines, no behavior change.

---

## C3 — Shared HTTP helper for 6 `HttpURLConnection` sites — ⏸️ confirmed parked

> **Re-reviewed 2026-06-16:** even the narrowest subset (the 3 astro fetches)
> isn't a clean dedup — `fetchLightPollution` needs 3 custom request headers
> (Origin/Referer/User-Agent) the others don't, and disconnect handling
> differs. The shared part is just `openConnection` + 2 timeouts: too thin to
> helper-ize. Family resemblance, not duplication. Stays parked.


**Audit claim:** Each of the 6 sites (`FirmwareUpdateManager`,
`AppUpdateManager`, `VersionUtils` ×3, `PlannerManager`,
`CameraDescription`, `CcapiClient`, `AstroDashboardData` ×3) rolls its
own connection setup, timeouts, redirect handling. A ~40-line `HttpKit`
helper would dedupe.

**Why parked:** On closer look the sites only superficially resemble each
other. They differ in: timeout values (Canon: 1.5 s; GitHub: 15 s),
auth (CCAPI digest vs none), redirect handling (manifest fetches follow,
camera calls don't), body type (JSON vs binary vs text), error semantics
(retryable vs fatal). A shared helper would either need 8+ config
parameters or push the variance onto each caller. Net negative.

**Trigger to revisit:** If we add a 7th or 8th HTTP call site with the
*same* shape as one we already have (i.e. real duplication appears, not
just family resemblance).

**Cheapest fix:** Extract only the call sites that genuinely match
(e.g. the 3 in `AstroDashboardData` for separate astro endpoints).
Leave the others alone.

---

## O1 — Batch `_status` updates from CCAPI poll job — ⏸️ confirmed parked

> **Re-reviewed 2026-06-16:** `applyCanonPollUpdate` emits at most **two
> conditional** `_status.update`s per poll (~1 Hz), on different fields
> (battery vs shots). Compose batches recomposition per frame, so the
> theoretical saving is nil. Stays parked.


**Audit claim:** Each `_status` write in the poll loop triggers a full
recompose; a busy session could see frame drops.

**Why parked:** No observed frame drops in real sessions. The poll runs
~1 Hz, well below Compose's 60 Hz frame budget. R4 (v0.366) addressed the
*race* aspect of the same code via `.update {}`; the perf aspect is
hypothetical.

**Trigger to revisit:** User reports UI stutter during a long Canon
bulb session, or a profiler shows `applyCanonPollUpdate` triggering
multiple recomposes per poll round.

**Cheapest fix:** Collect all field updates in a local `var` inside one
poll cycle, emit a single `_status.update { ... }` at the end.

---

## O2 — Replace `runState = combine(5 flows)` with a single MutableStateFlow — 🗑️ RETIRED (won't fix)

> **Closed 2026-06-16:** the audit author retracted this (below) — the
> "fix" is worse than the status quo. Left here only so it isn't
> re-flagged. Trigger to truly reconsider: a `RunState` field that can't
> be derived from the existing leaf flows.


**Audit claim:** Recomputes on every leaf change. As more flows are added,
this won't scale.

**Why parked:** On reflection, the "fix" is *worse* than the status quo.
A single `MutableStateFlow<RunState>` would need 5 different update sites
(run loop, transport drop, pause, step advance, status changes) to write
transactionally. The `combine(...)` does the derivation declaratively in
one place and Compose handles the recompose batching. I shouldn't have
flagged this.

**Trigger to revisit:** If we ever need a `RunState` field that can't be
derived from the existing leaf flows.

**Cheapest fix:** Add the new leaf flow and one more `combine` arg —
*don't* invert the model.

---

## O3 — CanonBleClient retry backoffs — 🛑 hold (device-only)

> **Re-reviewed 2026-06-16:** changing the `150/300/500/800 ms` queue-retry
> backoffs touches Canon BLE timing, which the project rule says must be
> validated on real hardware — and there's still no evidence of slow connect.
> Not a blind-edit candidate; hold until there's a logged symptom AND a
> device to test on.


**Audit claim:** Deterministic 150/300/500/800 ms backoffs total ~1.7 s
worst case. Could be exponential-with-jitter, or could short-circuit when
the link is down vs. queue-busy.

**Why parked:** No reports of slow Canon BLE connect. The deterministic
backoffs are easier to debug than jittered ones in a transport where we
already do a lot of log triage. The 1.7 s worst case is invisible vs the
2-3 s BR-E1 pairing handshake on most bodies.

**Trigger to revisit:** Logs show repeated queue-rejection retries on a
specific body (i.e. the in-flight detection would actually pay off),
or users complain about Canon BLE feeling sluggish.

**Cheapest fix:** Add a `gattReadyForOp` check on the existing GATT
callback path; short-circuit retry if false. Keep the deterministic
backoffs.

---

## Map Annotations API deprecation — ✅ DONE + DEVICE-VERIFIED (v0.466–0.471)

> **2026-06-17:** Eduardo device-verified the whole migration — "map
> migration looks good". All five Aircraft Watch marker types (aircraft,
> trails, sun/moon, highlight ring, user pin/cone) render correctly on the
> data-driven layers, rotation is clean (no double-rotate), and everything
> survives a Hybrid style swap. The picker (2a) was verified earlier. The
> file is fully off the legacy annotation API. **Item closed.**

> **2026-06-16:** **MapLocationPicker migrated (v0.466)** to the
> dependency-free `GeoJsonSource` + `SymbolLayer` path (new `ic_map_pin`
> icon, tap moves the source point). **Device-verified** by Eduardo —
> "map looks good".
>
> **2b — Aircraft Watch** is being migrated **one subsystem per bump** (the
> live map is unverifiable by build, so each slice gets its own device
> check rather than a six-way blind rewrite):
> - **Slice 1 — aircraft markers (v0.467): DEVICE-VERIFIED.** Planes render
>   via a data-driven `SymbolLayer` (one GeoJSON source, a feature per plane
>   naming a pre-rendered icon image; tap-to-select via
>   `queryRenderedFeatures`). Eduardo confirmed planes render correctly and
>   rotate to true world heading with **no double-rotate** (the main risk).
>   Proved the patterns the picker did not: data-driven `iconImage`,
>   feature-tap query, source/layer/image re-add after a style swap. (A brief
>   base-map *label* wobble he saw was a transient tile-settle glitch on an
>   older build — unrelated to the markers, self-resolved. Still worth a
>   glance that planes re-appear after a Hybrid toggle next time satellite is
>   on.)
> - **Slice 2 — trails (v0.468): DONE in code, awaiting device.** Past +
>   future tracks render via one data-driven `LineLayer` (a source of
>   `LineString` features, each with its own colour/width/opacity), slotted
>   below the aircraft layer. **Device checklist:** trails render *under* the
>   planes, the selected plane's trail is bright/thick vs dim/thin for
>   others, and they re-appear after a Hybrid toggle.
> - **Slice 3 — sun/moon (v0.469): DONE in code, awaiting device.** Own
>   `SymbolLayer` (0-2 point features), below the aircraft layer; source
>   emptied when below horizon / overlay off. **Device checklist:** enable
>   the overlay, both bodies appear at the right spots under the planes,
>   follow pan/zoom, vanish below horizon, survive a Hybrid toggle.
> - **Slice 4 — user pin + heading cone + highlight ring.** Split for safety:
>   - **4a — highlight ring (v0.470): DONE in code, awaiting device.** Own
>     single-feature `SymbolLayer` below the planes (tinted ring image keyed
>     by selection colour). Check: tap a plane → ring appears around it,
>     follows in live mode, clears on deselect.
>   - **4b — cone + pin (v0.471): DONE in code, awaiting device.**
>     `SymbolLayer` using data-driven `iconRotate` (same bearing-compensation
>     maths) + `symbol-z-order: source` for the pin-on-top-of-cone stack.
>     Check the rotation path: cone points right, stays upright under
>     heading-lock with NO double-rotate during a spin, pin sits on the cone.
>
> **Migration complete in code (v0.471):** `AircraftWatchSupport.kt` is fully
> off the legacy annotation API — `IconFactory`/`Marker`/`MarkerOptions`/
> `Polyline` imports + `iconFactory` all removed. Layer stack (bottom→top):
> trails, sun/moon, highlight, aircraft, user pin/cone. Also swept the
> unrelated `LocalLifecycleOwner` deprecation (moved to the
> `androidx.lifecycle.compose` package) across the 4 map files — the
> `lifecycle-runtime-compose` dep was already present. **Remaining:** Eduardo
> to device-verify slices 2–4b together (trails under planes, sun/moon,
> selection ring, cone/pin rotation, and that everything survives a Hybrid
> toggle).
>
> Each remaining slice still uses the (working but deprecated) annotation API
> until its turn. The file isn't deprecation-free until slice 4 lands.


**Audit claim:** `Marker`, `MarkerOptions`, `removeMarker`, `addMarker`
all deprecated. Library may need a version bump.

**Why parked:** Mapbox v10 replaced the Annotations API with a
Plugin-based annotation manager that has a different lifecycle, different
event hooks, and (in our case) would change the location-picker's
double-tap + drag UX. This is a focused refactor of a feature you actually
use (planner location → forecast lookups), not a bundle tail item.

**Trigger to revisit:** A Mapbox SDK update drops the old API, or we
want to add new map features (clustering, layers) that the new API
makes easier.

**Cheapest fix:** Replace `MarkerOptions().position(...)` with
`PointAnnotationManager.create(PointAnnotationOptions().withPoint(...))`,
adapt the long-press handler to the new event API. ~30 lines, but needs
manual testing on the planner flow.

---

## `Geocoder.getFromLocation` sync API deprecation — ✅ DONE (v0.463)

> **Fixed 2026-06-16.** Turned out to be **three** identical sites (map
> picker, planner GPS button, astro dashboard), so they were consolidated
> into one `util/GeocodeUtil.kt#reverseGeocodeName`, which uses the async
> `GeocodeListener` overload on API 33+ and the sync call on ≤32. Genuine
> dedup (two sites were byte-identical), not premature abstraction.
> Original note kept below for context.


**Audit claim:** 3-arg sync overload deprecated in API 33; should use the
async overload with `GeocodeListener`.

**Why parked:** minSdk 26. The async overload only exists on API 33+;
removing the deprecation requires `if (Build.VERSION.SDK_INT >= 33)` +
two code paths. The sync API still works on all supported devices.

**Trigger to revisit:** minSdk bumps to 33, or Google removes the sync
overload (no announcement so far).

**Cheapest fix:** Add the SDK-33 branch with the async overload;
keep the sync path for older devices.

---

## `ExposedDropdownMenuBox.menuAnchor()` signature change — ✅ DONE (v0.463)

> **Fixed 2026-06-16.** The `SharedSections` site was already on the new
> form; only `Intervalometer2Screen` still used the deprecated no-arg
> overload. Now `menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)`.


**Audit claim:** Old no-arg `Modifier.menuAnchor()` deprecated; new
overload takes `MenuAnchorType` + `enabled`.

**Why parked:** Single call site (`Intervalometer2Screen.kt:1256`),
no behavioral difference between the old and new forms for our use
case (it's a text-field anchor in expanded state).

**Trigger to revisit:** Compose-material3 drops the old overload.

**Cheapest fix:** Change to `Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = true)`.
One-line edit.
