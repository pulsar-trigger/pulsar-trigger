/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Copyright (C) 2026 Pulsar Trigger contributors
 */

package com.ehrocha.pulsar.canonble

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Owns the Canon BLE user-tunables (post-frame cool-down + the two protocol
 * registration names), the dial-change reminder state machine, and the
 * interval-floor clamp that depends on the cool-down. Extracted from
 * `PulsarViewModel` (audit H1) so this cohesive, self-contained surface can be
 * read and tested on its own — the ViewModel keeps the connection lifecycle +
 * run loop and delegates here.
 *
 * The decision *math* lives in [CanonBleRules] (unit-tested); this class holds
 * the *state* — persisted prefs + the StateFlows/SharedFlows the UI observes —
 * and wires the cool-down to the live transport via [applyCooldownToTransport].
 */
class CanonBleSettings(
    private val prefs: SharedPreferences,
    /** Push a changed cool-down to the live transport's step-boundary floor
     *  (`postFrameCooldownMs`) so it takes effect at once; no-op if unconnected. */
    private val applyCooldownToTransport: (Long) -> Unit,
) {
    // --- Post-frame cool-down --------------------------------------------

    private val _cooldownMs = MutableStateFlow(
        prefs.getLong(KEY_COOLDOWN, CanonBleRules.COOLDOWN_DEFAULT_MS),
    )
    val cooldownMs: StateFlow<Long> = _cooldownMs.asStateFlow()

    fun setCooldownMs(v: Long) {
        val clamped = CanonBleRules.clampCooldown(v)
        prefs.edit().putLong(KEY_COOLDOWN, clamped).apply()
        _cooldownMs.value = clamped
        applyCooldownToTransport(clamped)
    }

    // --- Registration names (must stay distinct) -------------------------

    private val _nameRemote = MutableStateFlow(
        prefs.getString(KEY_NAME_REMOTE, null) ?: CanonBleTransport.PAIR_NAME_BRE1,
    )
    val nameRemote: StateFlow<String> = _nameRemote.asStateFlow()
    fun setNameRemote(v: String) {
        val name = CanonBleRules.sanitizeName(v, CanonBleTransport.PAIR_NAME_BRE1)
        // Reject a collision with the smart name — otherwise the two protocols
        // are indistinguishable in the camera's paired-devices list.
        if (!CanonBleRules.namesDistinct(name, _nameSmart.value)) return
        prefs.edit().putString(KEY_NAME_REMOTE, name).apply()
        _nameRemote.value = name
    }

    private val _nameSmart = MutableStateFlow(
        prefs.getString(KEY_NAME_SMART, null) ?: CanonBleTransport.PAIR_NAME_SMART,
    )
    val nameSmart: StateFlow<String> = _nameSmart.asStateFlow()
    fun setNameSmart(v: String) {
        val name = CanonBleRules.sanitizeName(v, CanonBleTransport.PAIR_NAME_SMART)
        if (!CanonBleRules.namesDistinct(name, _nameRemote.value)) return
        prefs.edit().putString(KEY_NAME_SMART, name).apply()
        _nameSmart.value = name
    }

    // --- Interval floor ---------------------------------------------------

    private val _intervalRaised = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /** One-shot: a run asked for an interval below the cool-down and it was
     *  raised. The UI surfaces the explanatory snackbar. */
    val intervalRaised: SharedFlow<Unit> = _intervalRaised.asSharedFlow()

    /** Raise a below-cool-down interval/gap to the floor; pass others through.
     *  [isCanonBle] gates the clamp (the caller knows the active transport);
     *  emits [intervalRaised] once when it actually raises. */
    fun safeInterval(intervalMs: Long, isCanonBle: Boolean): Long {
        if (!isCanonBle) return intervalMs
        val floor = _cooldownMs.value
        val safe = CanonBleRules.safeInterval(intervalMs, floor)
        if (safe != intervalMs) {
            Log.i(TAG, "Canon BLE: interval ${intervalMs}ms below cool-down — raised to ${floor}ms")
            _intervalRaised.tryEmit(Unit)
        }
        return safe
    }

    // --- Dial-change reminder --------------------------------------------

    private val _dialReminder = MutableSharedFlow<Boolean>(extraBufferCapacity = 1)
    /** One-shot: the user moved between a Bulb-dial mode and an M-dial mode while
     *  on the dial-dependent Canon BLE transport. Payload: true = set to BULB,
     *  false = set to M. */
    val dialReminder: SharedFlow<Boolean> = _dialReminder.asSharedFlow()
    @Volatile private var lastModeBulbDial: Boolean? = null

    /** Record the entered mode's dial requirement (true = Bulb, false = M,
     *  null = no preference → ignored) and emit a reminder only on a real
     *  change and only while [transportActive]. */
    fun noteModeDial(bulbDial: Boolean?, transportActive: Boolean) {
        if (bulbDial == null) return
        val prev = lastModeBulbDial
        lastModeBulbDial = bulbDial
        if (CanonBleRules.shouldRemindDial(prev, bulbDial, transportActive)) {
            _dialReminder.tryEmit(bulbDial)
        }
    }

    companion object {
        private const val TAG = "CanonBleSettings"
        private const val KEY_COOLDOWN = "canon_ble_cooldown_ms"
        private const val KEY_NAME_REMOTE = "canon_ble_name_remote"
        private const val KEY_NAME_SMART = "canon_ble_name_smart"
    }
}
