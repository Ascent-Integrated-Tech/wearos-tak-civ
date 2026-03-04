package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.AccelPayload
import com.aitech.weartak_civ.cep.model.payloads.ActivityPayload
import com.aitech.weartak_civ.cep.model.payloads.AmbientPayload
import com.aitech.weartak_civ.cep.model.payloads.GyroPayload
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.cep.model.payloads.HrvPayload
import com.aitech.weartak_civ.cep.model.payloads.HumidityPayload
import com.aitech.weartak_civ.cep.model.payloads.ImmersionPayload
import com.aitech.weartak_civ.cep.model.payloads.LightPayload
import com.aitech.weartak_civ.cep.model.payloads.OffBodyPayload
import com.aitech.weartak_civ.cep.model.payloads.PpgPayload
import com.aitech.weartak_civ.cep.model.payloads.SkinTempPayload
import com.aitech.weartak_civ.cep.model.payloads.SleepPayload
import com.aitech.weartak_civ.cep.model.payloads.Spo2Payload
import com.aitech.weartak_civ.cep.model.payloads.WaterPayload
import com.aitech.weartak_civ.cep.model.payloads.WindPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

class CEPSimulationService : Service() {

    companion object {
        val isRunning = AtomicBoolean(false)

        val TAG = "Simulation"

        private const val NOTIF_ID = 8123
        private const val NOTIF_CHANNEL_ID = "cep_sim_channel"
        private const val NOTIF_CHANNEL_NAME = "CEP Simulation"

        const val ACTION_START = "com.aitech.weartak_civ.cep.sim.START"
        const val ACTION_STOP  = "com.aitech.weartak_civ.cep.sim.STOP"

        fun start(ctx: Context) {
            val i = Intent(ctx, CEPSimulationService::class.java).apply { action = ACTION_START }
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
        }
        fun stop(ctx: Context) {
            val i = Intent(ctx, CEPSimulationService::class.java).apply { action = ACTION_STOP }
            ctx.startService(i)
        }
    }

    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Default + job)
    private var loopStarted = false

    private val toggleCEP = true

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIF_ID, buildNotification("Idle"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (toggleCEP){
            when (intent?.action) {
                ACTION_START, null -> if (!loopStarted) startLoop()
                ACTION_STOP -> stopSelf()
            }
        }

        Log.d(TAG, "CEPSimulationService STARTED")

        //TODO - ALERT SIMULATION: Uncomment to use
//        testAlert()

        return START_STICKY
    }

    private fun testAlert() {
        val title = "Test Alert"
        val message = "This is a test alert message."

        // --- Haptic feedback (vibration for 5 seconds) ---
        try {
            val vibrator = if (android.os.Build.VERSION.SDK_INT >= 31) {
                val vm = getSystemService(android.os.VibratorManager::class.java)
                vm?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            }

            vibrator?.let { v ->
                if (android.os.Build.VERSION.SDK_INT >= 26) {
                    val effect = android.os.VibrationEffect.createOneShot(
                        5000,  // 5 seconds
                        android.os.VibrationEffect.DEFAULT_AMPLITUDE
                    )
                    v.vibrate(effect)
                } else {
                    @Suppress("DEPRECATION")
                    v.vibrate(5000)
                }
            }
        } catch (e: Exception) {
            Log.e("CEPSim", "Vibration failed: ${e.message}")
        }

        // --- Simple notification ---
        val channelId = "test_alert_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (nm.getNotificationChannel(channelId) == null) {
            val ch = android.app.NotificationChannel(
                channelId,
                "Test Alerts",
                android.app.NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(ch)
        }

        val notification = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        nm.notify(999, notification)
    }


    private fun startLoop() {
        loopStarted = true
        scope.launch {
            val allScenarios = Scenario.values().toList()

            while (isActive) {
                for (scenario in allScenarios) {
                    Log.i("[CEP][SIM]", "Starting Scenario Test: $scenario")
                    updateNotification("Running: $scenario")

                    simulateScenario(scenario, ageYears = 35, hrRest = 60)

                    Log.i("[CEP][SIM]", "Completed Scenario Test: $scenario")
                    updateNotification("Completed: $scenario")

                    // short pause between scenarios
                    delay(1_500)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    @Suppress("MagicNumber")
    private enum class Scenario {
        /*NORMAL_BASELINE,
        COLD_WARN_EARLY_COOLING,
        COLD_ALERT_SUSTAINED_SHIVER,
        COLD_EMERGENCY_ABSENT_SHIVER,
        COLD_EMERGENCY_IMMERSION,
        COLD_EMERGENCY_FROSTBITE_WINDCHILL,
        DEXTERITY_LOSS_HANDS,
        TACTILE_FAILURE_HANDS,
        SPO2_PPG_PERFUSION_LOSS*/
        //REST_BRADY_10MIN_EMERGENCY,
        //ZONE1_REST_10MIN_ALERT,
        //ZONE2_REST_60S_ALERT,
        //ZONE3_REST_30S_ALERT,
        ZONE3_EXERCISE_30S_WARN,
        //ZONE4_REST_5S_EMERGENCY,
        ZONE4_EXERCISE_10MIN_EMERGENCY,
        //ZONE5_REST_5S_EMERGENCY,
        ZONE5_EXERCISE_5MIN_EMERGENCY,
        HRMAX_REST_5S_EMERGENCY,
        HRMAX_EXERCISE_60S_EMERGENCY
    }

    private data class ScenarioConfig(
        val ticks: Int,
        val tickMs: Long,
        val activity: String,
        val onWrist: Int = 1,
        val sleeping: Boolean = false,
        val covered: Boolean = false,
        val ambientC: Double,
        val windMps: Double,
        val humidityPct: Double,
        val lux: Double,

        // HR inputs
        val entryHr: Double,               // HR during trigger phase
        val exitHr: Double? = null,        // HR during recovery phase
        val exitStartTick: Int? = null,    // when to switch to exitHr

        //val hr: Double,
        val hrvRmssdMs: Double,
        val spo2Pct: Double,
        val ppgAmp: Float,
        val accelMagG: Double,
        val cadenceSpm: Double?,
        val gyroRms: Float,
        val skinWristC: Double,
        val skinHandC: Double,
        val wet: Boolean,
        val immersion: Boolean,
        val waterTempC: Float? = null,
        val skinSlopePerTickC: Double = 0.0,
        val hrSlopePerTick: Double = 0.0,
        val ppgSlopePerTick: Float = 0f,
        val spo2SlopePerTick: Double = 0.0,
        val gyroPattern: ((Int) -> Float)? = null
    )

    private fun scenarioConfig(s: Scenario, ageYears: Int = 35, hrRest: Int = 60): ScenarioConfig {
        // Kept for potential future HRR-based scenario tuning; currently we just feed raw HR.
        val hrMax = 208 - (0.7 * ageYears)
        fun hrr(percent: Double) = hrRest + (hrMax - hrRest) * percent

        return when (s) {
            // 5) Zone 3 (147–159) EXERCISE ≥30 s -> WARN
            Scenario.ZONE3_EXERCISE_30S_WARN -> ScenarioConfig(
                ticks = 400, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 153.0,               // high HR in Zone 3
                exitHr = 120.0,                // raw HR safely below Zone 3 HRR band
                exitStartTick = 320,           // >300s sustained so alert fires, then we cool down
                hrvRmssdMs = 12.0, spo2Pct = 97.0, ppgAmp = 0.85f,
                accelMagG = 1.0, cadenceSpm = 170.0, gyroRms = 6.0f,
                skinWristC = 36.0, skinHandC = 35.0,
                wet = false, immersion = false
            )
            // 7) Zone 4 (160–171) EXERCISE ≥10 min -> EMERGENCY
            Scenario.ZONE4_EXERCISE_10MIN_EMERGENCY -> ScenarioConfig(
                ticks = 700, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 166.0,               // Zone 4 HR
                exitHr = 130.0,                // drop to much lower HR
                exitStartTick = 620,           // >600s sustained before cooldown
                hrvRmssdMs = 10.0, spo2Pct = 96.0, ppgAmp = 0.85f,
                accelMagG = 1.2, cadenceSpm = 175.0, gyroRms = 6.0f,
                skinWristC = 36.0, skinHandC = 35.0,
                wet = false, immersion = false
            )
            // 9) Zone 5 (172–184) EXERCISE ≥5 min -> EMERGENCY
            Scenario.ZONE5_EXERCISE_5MIN_EMERGENCY -> ScenarioConfig(
                ticks = 350, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 178.0,               // Zone 5 HR
                exitHr = 140.0,                // drop out of extreme zone
                exitStartTick = 320,           // >300s sustained before cooldown
                hrvRmssdMs = 8.0, spo2Pct = 95.0, ppgAmp = 0.85f,
                accelMagG = 1.2, cadenceSpm = 178.0, gyroRms = 6.0f,
                skinWristC = 36.0, skinHandC = 35.0,
                wet = false, immersion = false
            )
            // 10) ≥185 bpm REST ~immediate (rule uses 120s) -> EMERGENCY
            Scenario.HRMAX_REST_5S_EMERGENCY -> ScenarioConfig(
                ticks = 150, tickMs = 1000,
                activity = "STILL", sleeping = false,
                ambientC = 22.0, windMps = 0.2, humidityPct = 40.0, lux = 300.0,
                entryHr = 185.0,               // >HRmax at rest
                exitHr = 150.0,                // drop back down
                exitStartTick = 130,           // >120s sustained before cooldown
                hrvRmssdMs = 10.0, spo2Pct = 98.0, ppgAmp = 0.9f,
                accelMagG = 0.005, cadenceSpm = null, gyroRms = 0.2f,
                skinWristC = 33.0, skinHandC = 32.0,
                wet = false, immersion = false
            )
            // 11) ≥185 bpm EXERCISE ≥60 s -> EMERGENCY
            Scenario.HRMAX_EXERCISE_60S_EMERGENCY -> ScenarioConfig(
                ticks = 400, tickMs = 1000,
                activity = "RUN", sleeping = false,
                ambientC = 18.0, windMps = 1.5, humidityPct = 45.0, lux = 500.0,
                entryHr = 185.0,               // >HRmax during exercise
                exitHr = 150.0,                // recovery HR
                exitStartTick = 320,           // >300s sustained; plenty of time for cooldown + exit_sustain_sec
                hrvRmssdMs = 8.0, spo2Pct = 96.0, ppgAmp = 0.85f,
                accelMagG = 1.0, cadenceSpm = 180.0, gyroRms = 6.0f,
                skinWristC = 36.0, skinHandC = 35.0,
                wet = false, immersion = false
            )
        }
    }

    /*private suspend fun simulateScenario(s: Scenario, ageYears: Int = 35, hrRest: Int = 60) {
        val cfg = scenarioConfig(s, ageYears, hrRest)
        val rnd = Random(42)

        fun jitterD(d: Double, mag: Double) = d + rnd.nextDouble(-mag, mag)
        fun jitterF(f: Float, mag: Float) = f + rnd.nextFloat() * (2f * mag) - mag

        var skinWrist = cfg.skinWristC
        var skinHand = cfg.skinHandC
        var hr = cfg.entryHr
        var ppg = cfg.ppgAmp
        var spo2 = cfg.spo2Pct

        repeat(cfg.ticks) { t ->
            val gyro = cfg.gyroPattern?.invoke(t) ?: cfg.gyroRms

            CepBus.emit(CepEvent(Topics.ACTIVITY,  ActivityPayload(cfg.activity)))
            CepBus.emit(CepEvent(Topics.OFFBODY,   OffBodyPayload(cfg.onWrist)))
            CepBus.emit(CepEvent(Topics.SLEEP,     SleepPayload(cfg.sleeping)))
            CepBus.emit(CepEvent(Topics.HEART_RATE,HeartRatePayload(bpm = hr)))
            CepBus.emit(CepEvent(Topics.HRV,       HrvPayload(rmssd_ms = cfg.hrvRmssdMs)))
            CepBus.emit(CepEvent(Topics.SPO2,      Spo2Payload(pct = spo2)))
            CepBus.emit(CepEvent(Topics.PPG,       PpgPayload(amplitude = ppg, quality = 1.0f, wavelength = "green")))
            CepBus.emit(CepEvent(Topics.ACCEL,     AccelPayload(magnitude_g = jitterD(cfg.accelMagG, 0.01), cadence_spm = cfg.cadenceSpm)))
            CepBus.emit(CepEvent(Topics.GYRO,      GyroPayload(x = jitterF(0f, 0.1f), y = jitterF(0f, 0.1f), z = jitterF(0f, 0.1f), rms = gyro)))
            CepBus.emit(CepEvent(Topics.AMBIENT,   AmbientPayload(temp_c = jitterD(cfg.ambientC, 0.2), wind_mps = jitterD(cfg.windMps, 0.2), humidity_pct = jitterD(cfg.humidityPct, 1.5))))
            CepBus.emit(CepEvent(Topics.WIND,      WindPayload(speedMps = jitterF(cfg.windMps.toFloat(), 0.2f), directionDeg = (t * 17 % 360).toFloat())))
            CepBus.emit(CepEvent(Topics.HUMIDITY,  HumidityPayload(relativePercent = jitterF(cfg.humidityPct.toFloat(), 1.5f))))
            CepBus.emit(CepEvent(Topics.LIGHT,     LightPayload(lux = jitterF(cfg.lux.toFloat(), 5f), covered = cfg.covered)))
            CepBus.emit(CepEvent(Topics.SKIN_TEMP, SkinTempPayload(c_peripheral = listOf(skinWrist, skinHand))))
            CepBus.emit(CepEvent(Topics.IMMERSION, ImmersionPayload(wet_flag = cfg.wet, immersion_flag = cfg.immersion)))
            CepBus.emit(CepEvent(Topics.WATER,     WaterPayload(wet = cfg.wet, temperatureC = cfg.waterTempC)))

            // update drifting values
            skinWrist += cfg.skinSlopePerTickC
            skinHand  += cfg.skinSlopePerTickC * 1.15

            // HR: trigger phase then cooldown phase
            if (cfg.exitStartTick != null && cfg.exitHr != null && t >= cfg.exitStartTick) {
                hr = cfg.exitHr
            } else {
                hr += cfg.hrSlopePerTick
            }

            ppg  = (ppg + cfg.ppgSlopePerTick).coerceIn(0f, 1f)
            spo2 = (spo2 + cfg.spo2SlopePerTick).coerceIn(80.0, 100.0)

            delay(cfg.tickMs)
        }
    }*/

    private suspend fun simulateScenario(s: Scenario, ageYears: Int = 35, hrRest: Int = 60) {
        val cfg = scenarioConfig(s, ageYears, hrRest)

        // Fixed seed keeps the simulation reproducible while satisfying S2245
        //val rnd = SecureRandom().apply { setSeed(42L) }

        //SonarQube compliant
        val rnd = SecureRandom()

        fun jitterD(d: Double, mag: Double): Double {
            // rnd.nextDouble() -> [0,1); map to [-mag, +mag]
            val delta = (rnd.nextDouble() * 2.0 - 1.0) * mag
            return d + delta
        }

        fun jitterF(f: Float, mag: Float): Float {
            val delta = ((rnd.nextFloat() * 2f) - 1f) * mag
            return f + delta
        }

        var skinWrist = cfg.skinWristC
        var skinHand = cfg.skinHandC
        var hr = cfg.entryHr
        var ppg = cfg.ppgAmp
        var spo2 = cfg.spo2Pct

        repeat(cfg.ticks) { t ->
            val gyro = cfg.gyroPattern?.invoke(t) ?: cfg.gyroRms

            CepBus.emit(CepEvent(Topics.ACTIVITY,  ActivityPayload(cfg.activity)))
            CepBus.emit(CepEvent(Topics.OFFBODY,   OffBodyPayload(cfg.onWrist)))
            CepBus.emit(CepEvent(Topics.SLEEP,     SleepPayload(cfg.sleeping)))
            CepBus.emit(CepEvent(Topics.HEART_RATE,HeartRatePayload(bpm = hr)))
            CepBus.emit(CepEvent(Topics.HRV,       HrvPayload(rmssd_ms = cfg.hrvRmssdMs)))
            CepBus.emit(CepEvent(Topics.SPO2,      Spo2Payload(pct = spo2)))
            CepBus.emit(CepEvent(Topics.PPG,       PpgPayload(amplitude = ppg, quality = 1.0f, wavelength = "green")))
            CepBus.emit(CepEvent(Topics.ACCEL,     AccelPayload(magnitude_g = jitterD(cfg.accelMagG, 0.01), cadence_spm = cfg.cadenceSpm)))
            CepBus.emit(CepEvent(Topics.GYRO,      GyroPayload(x = jitterF(0f, 0.1f), y = jitterF(0f, 0.1f), z = jitterF(0f, 0.1f), rms = gyro)))
            CepBus.emit(CepEvent(Topics.AMBIENT,   AmbientPayload(temp_c = jitterD(cfg.ambientC, 0.2), wind_mps = jitterD(cfg.windMps, 0.2), humidity_pct = jitterD(cfg.humidityPct, 1.5))))
            CepBus.emit(CepEvent(Topics.WIND,      WindPayload(speedMps = jitterF(cfg.windMps.toFloat(), 0.2f), directionDeg = (t * 17 % 360).toFloat())))
            CepBus.emit(CepEvent(Topics.HUMIDITY,  HumidityPayload(relativePercent = jitterF(cfg.humidityPct.toFloat(), 1.5f))))
            CepBus.emit(CepEvent(Topics.LIGHT,     LightPayload(lux = jitterF(cfg.lux.toFloat(), 5f), covered = cfg.covered)))
            CepBus.emit(CepEvent(Topics.SKIN_TEMP, SkinTempPayload(c_peripheral = listOf(skinWrist, skinHand))))
            CepBus.emit(CepEvent(Topics.IMMERSION, ImmersionPayload(wet_flag = cfg.wet, immersion_flag = cfg.immersion)))
            CepBus.emit(CepEvent(Topics.WATER,     WaterPayload(wet = cfg.wet, temperatureC = cfg.waterTempC)))

            // update drifting values
            skinWrist += cfg.skinSlopePerTickC
            skinHand  += cfg.skinSlopePerTickC * 1.15

            // HR: trigger phase then cooldown phase
            if (cfg.exitStartTick != null && cfg.exitHr != null && t >= cfg.exitStartTick) {
                hr = cfg.exitHr
            } else {
                hr += cfg.hrSlopePerTick
            }

            ppg  = (ppg + cfg.ppgSlopePerTick).coerceIn(0f, 1f)
            spo2 = (spo2 + cfg.spo2SlopePerTick).coerceIn(80.0, 100.0)

            delay(cfg.tickMs)
        }
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                NOTIF_CHANNEL_ID, NOTIF_CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW
            ).apply { description = "CEP simulation foreground service" }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }
    }

    private fun buildNotification(content: String): Notification {
        val stopIntent = Intent(this, CEPSimulationService::class.java).apply { action = ACTION_STOP }
        val stopPI = PendingIntent.getService(
            this, 1001, stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("CEP Simulation")
            .setContentText(content)
            .setOngoing(true)
            .addAction(0, "Stop", stopPI)
            .build()
    }

    private fun updateNotification(content: String) {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID, buildNotification(content))
    }
}
