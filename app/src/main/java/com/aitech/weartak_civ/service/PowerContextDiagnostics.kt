package com.aitech.weartak_civ.service

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.hardware.display.DisplayManager
import android.net.wifi.WifiManager
import android.nfc.NfcAdapter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.Display
import kotlin.math.roundToLong

internal data class PowerClocks(val elapsed: Long, val uptime: Long, val skew: Long = 0)

internal fun suspendInterval(previous: PowerClocks?, now: PowerClocks): Map<DiagnosticMetric, Long> {
    if (previous == null) return mapOf(DiagnosticMetric.CLOCK_STATUS to 0L)
    val elapsed = now.elapsed - previous.elapsed
    val uptime = now.uptime - previous.uptime
    val rawSleep = elapsed - uptime
    val common = mapOf(
        DiagnosticMetric.PREVIOUS_ELAPSED_MS to previous.elapsed,
        DiagnosticMetric.ELAPSED_DELTA_MS to elapsed,
        DiagnosticMetric.UPTIME_DELTA_MS to uptime
    )
    if (elapsed <= 0 || uptime < 0 || rawSleep < -5 || now.skew > 5 || previous.skew > 5)
        return common + (DiagnosticMetric.CLOCK_STATUS to 2L)
    val asleep = rawSleep.coerceIn(0, elapsed)
    return common + mapOf(
        DiagnosticMetric.CLOCK_STATUS to 1L,
        DiagnosticMetric.ASLEEP_MS to asleep,
        DiagnosticMetric.ASLEEP_BASIS_POINTS to (10000.0 * asleep / elapsed).roundToLong(),
        // 2 means indistinguishable from zero within the clock resolution, not exact zero.
        DiagnosticMetric.NEVER_SUSPENDED to if (rawSleep == 0L) 1L else if (rawSleep <= 5) 2L else 0L,
        DiagnosticMetric.SHORT_INTERVAL to if (elapsed < 1000) 1L else 0L
    )
}

internal data class NumericReading(val status: Long, val value: Long? = null) {
    companion object {
        fun setting(raw: String?): NumericReading = when {
            raw == null -> NumericReading(1)
            raw.toLongOrNull() == null -> NumericReading(3)
            else -> NumericReading(0, raw.toLong())
        }
        fun battery(raw: Long) = if (raw == Long.MIN_VALUE) NumericReading(4) else NumericReading(0, raw)
    }
}

internal data class SettingProbe(val id: Long, val namespace: Long, val key: String)

/** Fixed compatibility probes, never a settings dump or an inferred effective gesture toggle. */
internal val powerSettingProbes = buildList {
    listOf("wake_gesture_enabled", "doze_pick_up_gesture", "doze_pulse_on_pick_up",
        "doze_tap_gesture", "doze_double_tap_gesture", "wake_on_gesture",
        "wrist_gestures_enabled", "doze_always_on", "ambient_enabled").forEachIndexed { i, key ->
        add(SettingProbe(i.toLong(), 1, key))
    }
    listOf("lift_to_wake", "double_tap_to_wake_up", "aod_mode", "aod_tap_to_show_mode")
        .forEachIndexed { i, key ->
            add(SettingProbe(9L + i * 2, 0, key))
            add(SettingProbe(10L + i * 2, 1, key))
        }
    add(SettingProbe(17, 2, Settings.Global.STAY_ON_WHILE_PLUGGED_IN))
    add(SettingProbe(18, 2, "low_power"))
}

/** All callbacks are non-wakeup Handler work; the only periodic caller is an existing path tick. */
internal class PowerContextDiagnostics(
    private val app: Application,
    private val emit: (DiagnosticEvent, Map<DiagnosticMetric, Long>) -> Boolean,
    private val clocks: () -> PowerClocks = {
        fun sample(): PowerClocks {
            val before = SystemClock.elapsedRealtime()
            val uptime = SystemClock.uptimeMillis()
            val after = SystemClock.elapsedRealtime()
            return PowerClocks(after, uptime, after - before)
        }
        sample().let { if (it.skew > 5) sample() else it }
    }
) {
    private val handler = Handler(Looper.getMainLooper())
    private var active = false
    private var epoch = 0L
    private var previous: PowerClocks? = null
    private var lastEventAt: Long? = null
    private val settings = mutableMapOf<Long, Map<DiagnosticMetric, Long>>()
    private val observers = mutableListOf<ContentObserver>()
    private val observedSettings = mutableSetOf<Long>()
    private var receiverRegistered = false
    private var displayRegistered = false
    private var pending: Runnable? = null
    private var pendingReason = 2L
    private var observationFailures = 0L
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = changed()
    }
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = changed()
        override fun onDisplayRemoved(displayId: Int) = changed()
        override fun onDisplayChanged(displayId: Int) = changed()
    }

    @Synchronized fun start() {
        if (active) return
        active = true
        epoch++
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_DEVICE_LIGHT_IDLE_MODE_CHANGED)
            addAction(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(NfcAdapter.ACTION_ADAPTER_STATE_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
        }
        try {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            receiverRegistered = true
        } catch (_: SecurityException) { observationFailures++ }
        try {
            app.getSystemService(DisplayManager::class.java)?.let {
                it.registerDisplayListener(displayListener, handler)
                displayRegistered = true
            }
        } catch (_: SecurityException) { observationFailures++ }
        powerSettingProbes.forEach { probe ->
            val observer = object : ContentObserver(handler) {
                override fun onChange(selfChange: Boolean) = changed()
            }
            try {
                app.contentResolver.registerContentObserver(uri(probe), false, observer)
                observers.add(observer)
                observedSettings.add(probe.id)
            } catch (_: SecurityException) { observationFailures++ }
        }
        snapshot(0)
    }

    @Synchronized fun stop() {
        active = false
        epoch++
        pending?.let(handler::removeCallbacks)
        pending = null
        if (receiverRegistered) app.unregisterReceiver(receiver)
        if (displayRegistered) app.getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        observers.forEach(app.contentResolver::unregisterContentObserver)
        observers.clear()
        observedSettings.clear()
        receiverRegistered = false
        displayRegistered = false
        reset()
    }

    @Synchronized fun reset(clearRecorder: () -> Unit = {}) {
        epoch++
        pending?.let(handler::removeCallbacks)
        pending = null
        previous = null
        lastEventAt = null
        settings.clear()
        clearRecorder()
    }

    @Synchronized private fun changed(reason: Long = 2) {
        if (!active) return
        if (reason == 3L) pendingReason = 3 else if (pending == null) pendingReason = reason
        if (pending != null) return
        val capturedEpoch = epoch
        val callback = Runnable {
            synchronized(this) {
                pending = null
                if (active && capturedEpoch == epoch) snapshot(pendingReason)
            }
        }
        pending = callback
        handler.post(callback)
    }

    fun battery(chargingBoundary: Boolean) = changed(if (chargingBoundary) 3 else 2)

    private fun uri(probe: SettingProbe) = when (probe.namespace) {
        0L -> Settings.System.getUriFor(probe.key)
        1L -> Settings.Secure.getUriFor(probe.key)
        else -> Settings.Global.getUriFor(probe.key)
    }

    private fun read(probe: SettingProbe): NumericReading = try {
        NumericReading.setting(when (probe.namespace) {
            0L -> Settings.System.getString(app.contentResolver, probe.key)
            1L -> Settings.Secure.getString(app.contentResolver, probe.key)
            else -> Settings.Global.getString(app.contentResolver, probe.key)
        })
    } catch (_: SecurityException) { NumericReading(2) }

    private fun query(block: () -> Long?): NumericReading = try {
        block()?.let { NumericReading(0, it) } ?: NumericReading(4)
    } catch (_: SecurityException) { NumericReading(2) }
      catch (_: UnsupportedOperationException) { NumericReading(4) }

    @Synchronized fun snapshot(reason: Long = 1) {
        if (!active) return
        val now = clocks()
        powerSettingProbes.forEach { probe ->
            val reading = read(probe)
            val values = mapOf(
                DiagnosticMetric.SETTING_ID to probe.id,
                DiagnosticMetric.NAMESPACE to probe.namespace,
                DiagnosticMetric.READ_STATUS to reading.status,
                DiagnosticMetric.SEMANTICS_VERIFIED to 0L,
                DiagnosticMetric.OBSERVER_AVAILABLE to if (probe.id in observedSettings) 1L else 0L
            ) + reading.value?.let { mapOf(DiagnosticMetric.VALUE to it) }.orEmpty()
            if (settings[probe.id] != values) {
                emit(DiagnosticEvent.DEVICE_SETTING, values)
                settings[probe.id] = values
            }
        }
        val metrics = mutableMapOf(
            DiagnosticMetric.CONTEXT_VERSION to 1L,
            DiagnosticMetric.REASON to reason,
            DiagnosticMetric.CAPTURE_ELAPSED_MS to now.elapsed,
            DiagnosticMetric.UPTIME_MS to now.uptime,
            DiagnosticMetric.CLOCK_SKEW_MS to now.skew,
            DiagnosticMetric.OBSERVATION_FAILURES to observationFailures
        )
        metrics.putAll(suspendInterval(previous, now))
        val power = app.getSystemService(PowerManager::class.java)
        metrics[DiagnosticMetric.IDLE] = power?.isDeviceIdleMode?.let { if (it) 1L else 0L } ?: -1
        metrics[DiagnosticMetric.LIGHT_IDLE] = power?.isDeviceLightIdleMode?.let { if (it) 1L else 0L } ?: -1
        metrics[DiagnosticMetric.INTERACTIVE] = power?.isInteractive?.let { if (it) 1L else 0L } ?: -1
        metrics[DiagnosticMetric.SAVER] = power?.isPowerSaveMode?.let { if (it) 1L else 0L } ?: -1
        metrics[DiagnosticMetric.DISPLAY_STATE] =
            app.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)?.state?.toLong() ?: -1
        val battery = app.getSystemService(BatteryManager::class.java)
        fun batteryProperty(property: Int): NumericReading {
            val reading = query { battery?.getLongProperty(property) }
            return if (reading.status == 0L) NumericReading.battery(reading.value!!) else reading
        }
        fun put(reading: NumericReading, value: DiagnosticMetric, status: DiagnosticMetric) {
            metrics[status] = reading.status
            reading.value?.let { metrics[value] = it }
        }
        put(batteryProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW), DiagnosticMetric.CURRENT_UA, DiagnosticMetric.CURRENT_STATUS)
        put(batteryProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER), DiagnosticMetric.CHARGE_UAH, DiagnosticMetric.CHARGE_STATUS)
        val radios = listOf(
            query { app.getSystemService(BluetoothManager::class.java)?.adapter?.state?.toLong() },
            query { NfcAdapter.getDefaultAdapter(app)?.let { if (it.isEnabled) 1L else 0L } },
            query { app.getSystemService(WifiManager::class.java)?.wifiState?.toLong() }
        )
        radios.forEachIndexed { index, reading ->
            val values = mapOf(DiagnosticMetric.SETTING_ID to (19L + index),
                DiagnosticMetric.NAMESPACE to 3L, DiagnosticMetric.READ_STATUS to reading.status) +
                reading.value?.let { mapOf(DiagnosticMetric.VALUE to it) }.orEmpty()
            if (settings[19L + index] != values) {
                emit(DiagnosticEvent.DEVICE_SETTING, values)
                settings[19L + index] = values
            }
        }
        listOf(DiagnosticMetric.IDLE, DiagnosticMetric.LIGHT_IDLE, DiagnosticMetric.INTERACTIVE,
            DiagnosticMetric.DISPLAY_STATE, DiagnosticMetric.SAVER).forEachIndexed { index, metric ->
            val value = metrics.getValue(metric)
            val values = mapOf(DiagnosticMetric.SETTING_ID to (22L + index),
                DiagnosticMetric.NAMESPACE to 3L, DiagnosticMetric.READ_STATUS to if (value < 0) 4L else 0L) +
                if (value < 0) emptyMap() else mapOf(DiagnosticMetric.VALUE to value)
            if (settings[22L + index] != values) {
                emit(DiagnosticEvent.DEVICE_SETTING, values)
                settings[22L + index] = values
            }
        }
        // State transitions remain prompt even when full context snapshots are cadence-gated.
        if (reason == 2L && lastEventAt?.let { now.elapsed - it in 0 until 30_000 } == true) return
        if (emit(DiagnosticEvent.POWER_CONTEXT, metrics)) {
            previous = now
            lastEventAt = now.elapsed
        }
    }
}
