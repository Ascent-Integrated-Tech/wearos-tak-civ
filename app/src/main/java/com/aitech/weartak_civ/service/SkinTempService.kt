package com.aitech.weartak_civ.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_LOW
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import com.aitech.weartak_civ.R
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.events.IsDeviceWornEvent

class SkinTempService : Service() {
    // reference shared repository
    private val sharedRepository: DataRepository
        get() = MainActivity.dataRepository

    // Health Services clients

    // local state
    private var deviceWorn: Boolean = false

    override fun onCreate() {
        super.onCreate()
        // TODO: init Samsung SDK Sensor Instances
        startForegroundServiceCompat(SKINTEMP_SERVICE_NOTIFICATION_ID, importance = IMPORTANCE_LOW)
        // register for device-worn events
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "SkinTempService onStartCommand called w/ isRunning=$isRunning")
        if (!isRunning.getAndSet(true)) {
            deviceWorn = sharedRepository.latestIsDeviceWorn.value ?: false
            CoroutineScope(Dispatchers.Main).launch {
                if (deviceWorn) {
                    startSensors()
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        deviceWorn = false
        if (EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().unregister(this)
        }
        stopSensors()
        Log.d(TAG, "SkinTemp Service Destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onIsDeviceWornEvent(event: IsDeviceWornEvent) {
        val curr = event.isDeviceWorn
        Log.d(TAG, "Received IsDeviceWornEvent for skintemp: worn=$curr (prev=$deviceWorn)")
        if (deviceWorn != curr) {
            if (curr) {
                startSensors()
            } else {
                stopSensors()
                temp = -1
                runBlocking {
                    skinTempStringLiveData.postValue("N/A")
                    sharedRepository.storeLatestSkinTemp(temp)
                }
            }
            deviceWorn = curr
        }
    }

    private fun startSensors() {
        Log.d(TAG, "SkinTempService startSensors called")
        if (!checkPermissions()) {
            Log.e(TAG, "Missing BODY_SENSORS permission for skin temp")
            return
        }
        /* TODO:
        *   1. get skin temp using API from Samsung Health Sensor SDK
        *   2. update the UI if needed
        *   3. send a physio or / skin temp event for subs (like cotdispatch service)
        */
    }

    private fun stopSensors() {
        /* TODO: unregister skin temp callback */
    }

    private fun checkPermissions(): Boolean {
        return (ContextCompat.checkSelfPermission(
            MainActivity.getActivity(),
            "android.permission.health.READ_SKIN_TEMPERATURE"
        ) == PackageManager.PERMISSION_GRANTED)
    }

    companion object {
        private const val TAG = "WearTAK-${'$'}{SkinTempService::class.java.simpleName}"
        private const val SKINTEMP_SERVICE_NOTIFICATION_ID = 106
        var temp: Int = -1
        val skinTempStringLiveData = MutableLiveData<String>("N/A")
        val isRunning = java.util.concurrent.atomic.AtomicBoolean(false)
    }
}
