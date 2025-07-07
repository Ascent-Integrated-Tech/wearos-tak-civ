package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent
import com.aitech.weartak_civ.presentation.MainActivity
import com.samsung.android.service.health.tracking.HealthTrackingService
import com.samsung.android.service.health.tracking.ConnectionListener
import com.samsung.android.service.health.tracking.HealthTrackerException
import com.samsung.android.service.health.tracking.HealthTracker
import com.samsung.android.service.health.tracking.data.HealthTrackerType
import com.samsung.android.service.health.tracking.data.DataPoint
import com.samsung.android.service.health.tracking.data.ValueKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.util.concurrent.atomic.AtomicBoolean

class SkinTempService : Service() {
    companion object {
        private const val TAG = "WearTAK-SkinTService"
        private const val NOTIF_ID = 177

        var tempF: Float = -1f
        val skinTempLiveData = MutableLiveData<String>("N/A")
        var latestSweatMl: Float = -1f
        val sweatLossLiveData = MutableLiveData<String>("N/A")
        val isRunning = AtomicBoolean(false)
        var DISPATCH_INTERVAL_MS = 60 * 1_000L
        private const val MIN_SKIN_TEMP_INTERVAL_MS = 10_000L
    }

    private val sharedRepo: DataRepository
        get() = MainActivity.dataRepository

    private lateinit var healthService: HealthTrackingService
    private lateinit var skinTracker: HealthTracker
    private lateinit var sweatTracker: HealthTracker

    private lateinit var handlerThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private var isSkinHdlerRunning = false
    private var isSweatHdlerRunning = false
    private var deviceWorn: Boolean = false

    private var nextFireMs = 0L          // wall-clock target for the next run
    private val nowUptime get() = SystemClock.uptimeMillis()     // One monotonic clock – the same one Handler uses

    // One function that always schedules the NEXT reading
    private fun scheduleNextReading() {
        if (!deviceWorn) return                     // skip if watch off-wrist
        val target = nowUptime + DISPATCH_INTERVAL_MS        // absolute uptime timestamp
        nextFireMs = target                         // purely for diagnostics
        backgroundHandler.postAtTime(::startSkinRunnable, target)
    }

    private fun scheduleNextSkinRead() {
        if (!deviceWorn) return
        val target = nowUptime + DISPATCH_INTERVAL_MS
        backgroundHandler.removeCallbacks(::startSkinRunnable)
        backgroundHandler.postAtTime(::startSkinRunnable, target)
    }

    private fun scheduleNextSweatRead() {
        if (!deviceWorn) return
        val target = nowUptime + DISPATCH_INTERVAL_MS
        backgroundHandler.removeCallbacks(::startSweatRunnable)
        backgroundHandler.postAtTime(::startSweatRunnable, target)
    }

    private val connectionListener = object : ConnectionListener {
        override fun onConnectionSuccess() {
            skinTracker = healthService.getHealthTracker(HealthTrackerType.SKIN_TEMPERATURE_ON_DEMAND)
            sweatTracker = healthService.getHealthTracker(HealthTrackerType.SWEAT_LOSS)
            Log.i(TAG, "Health SDK Connected")
            if (deviceWorn) {
                startSkinRunnable()     // only kick off if worn
                scheduleNextReading()  // looper
            }
        }

        override fun onConnectionFailed(e: HealthTrackerException) {
            Log.e(TAG, "HealthPlatform connection failed", e)
            if (e.hasResolution()) {
                try { e.resolve(null) } catch (ex: Exception) { Log.e(TAG, "Resolution failed", ex) }
            }
        }

        override fun onConnectionEnded() {
            Log.w(TAG, "HealthPlatform DISCONNECTED")
            stopSkinRunnable(true)
        }
    }

    private val skinTempListener = object : HealthTracker.TrackerEventListener {

        override fun onDataReceived(dataPoints: List<DataPoint>) {
            // 1  Stop sensor immediately (but keep queue)
            stopSkinRunnable(false)

            // 2  Collect temp data point
            dataPoints.firstOrNull()?.let { dp ->
                val status = dp.getValue(ValueKey.SkinTemperatureSet.STATUS)

                if (status >= 0) {
                    val objectTemp = dp.getValue(ValueKey.SkinTemperatureSet.OBJECT_TEMPERATURE)
                    val ambientTemp = dp.getValue(ValueKey.SkinTemperatureSet.AMBIENT_TEMPERATURE)
                    tempF = objectTemp.toFahrenheit()
                    Log.i(TAG, "Skin Temp: ${"%.1f".format(tempF)}°F")
                } else {
                    tempF = -1f
                    skinTempLiveData.postValue("N/A")
                }

                CoroutineScope(Dispatchers.IO).launch {
                    sharedRepo.storeLatestSkinTemp(tempF)
                }
            }
            // 3  Schedule the next run *once*, with the helper
            scheduleNextReading()
        }

        override fun onFlushCompleted() {
            // No-op for on-demand service
        }

        fun Float.toFahrenheit(): Float = this * 9f/5f + 32f

        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "Tracker error: $error")
        }
    }

    private val sweatLossListener = object : HealthTracker.TrackerEventListener {
        override fun onDataReceived(dataPoints: List<DataPoint>) {
            // stop this tracker so it doesn’t flood
            stopSweatRunnable(clearQueue = false)

            dataPoints.firstOrNull()?.let { dp ->
                val sweat = dp.getValue(ValueKey.SweatLossSet.SWEAT_LOSS)
                latestSweatMl = sweat
                Log.i(TAG, "Sweat Loss: ${"%.1f".format(sweat)} mL")
                sweatLossLiveData.postValue("%.1f mL".format(sweat))

                // persist data
                CoroutineScope(Dispatchers.IO).launch {
                    sharedRepo.storeLatestSweatLoss(sweat)
                }
            }
            // schedule next run
            scheduleNextSweatRead()
        }

        override fun onFlushCompleted() { /* no-op */ }
        override fun onError(error: HealthTracker.TrackerError) {
            Log.e(TAG, "Sweat tracker error: $error")
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service onCreate")

        CoroutineScope(Dispatchers.IO).launch{
            sharedRepo.reportingUpdateIntervalSecs.collect { secs ->
                val newIntervalMs = (secs * 1_000L).coerceAtLeast(MIN_SKIN_TEMP_INTERVAL_MS)
                if (newIntervalMs != DISPATCH_INTERVAL_MS) {
                    DISPATCH_INTERVAL_MS = newIntervalMs

                    if (deviceWorn) {
                        backgroundHandler.removeCallbacksAndMessages(null)
                        scheduleNextReading()
                        startSkinRunnable()
                    }
                }
            }
        }

        // Start a dedicated thread for handling callbacks
        handlerThread = HandlerThread("SkinTempThread").apply { start() }
        backgroundHandler = Handler(handlerThread.looper)

        // Connect to the Samsung Health Tracking Service
        healthService = HealthTrackingService(connectionListener, applicationContext)
        healthService.connectService()

        // Promote to foreground to avoid being killed
        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW
        )

        // Listen for wrist wear state changes
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service onStartCommand, isRunning=${isRunning.get()}")
        if (!isRunning.getAndSet(true)) {
            deviceWorn = sharedRepo.latestIsDeviceWorn.value ?: false
            if (deviceWorn && ::skinTracker.isInitialized) {
                startSkinRunnable()
                scheduleNextSkinRead()
            }
            if (deviceWorn && ::sweatTracker.isInitialized) {
                startSweatRunnable()
                scheduleNextSweatRead()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service onDestroy")

        isRunning.set(false)
        deviceWorn = false
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }

        // Ensure listener is removed and thread stopped
        stopSkinRunnable(true); stopSweatRunnable()
        healthService.disconnectService()
        handlerThread.quitSafely()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Whenever the device is put on, we want fresh skin temp, not wait for the dispatch interval
    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onIsDeviceWornEvent(event: IsDeviceWornEvent) {
        val nowWorn = event.isDeviceWorn
        Log.d(TAG, "Wear event: new=$nowWorn")
        // Clear old callbacks so they don’t pile up
        backgroundHandler.removeCallbacksAndMessages(null)

        if (nowWorn) {
            deviceWorn = true
            startSkinRunnable(); startSweatRunnable();   // immediate snapshot
            scheduleNextSkinRead(); scheduleNextSweatRead();
        } else {
            deviceWorn = false
            stopSkinRunnable(true); stopSweatRunnable(true)     // shut it down
        }
    }

    private fun startSkinRunnable() {
        if (!::skinTracker.isInitialized || isSkinHdlerRunning) return
        isSkinHdlerRunning = true
        backgroundHandler.post { skinTracker.setEventListener(skinTempListener) }
    }

    private fun startSweatRunnable() {
        if (!::sweatTracker.isInitialized || isSweatHdlerRunning) return
        isSweatHdlerRunning = true
        backgroundHandler.post { skinTracker.setEventListener(sweatLossListener) }
    }

    private fun stopSkinRunnable(clearQueue: Boolean = false) {
        if (!isSkinHdlerRunning) return
        isSkinHdlerRunning = false
        backgroundHandler.post {
            skinTracker.unsetEventListener()
            if (clearQueue) backgroundHandler.removeCallbacksAndMessages(null)
        }
    }

    private fun stopSweatRunnable(clearQueue: Boolean = false) {
        if (!isSweatHdlerRunning) return
        isSweatHdlerRunning = false
        backgroundHandler.post {
            sweatTracker.unsetEventListener()
            if (clearQueue) backgroundHandler.removeCallbacksAndMessages(null)
        }
    }

}