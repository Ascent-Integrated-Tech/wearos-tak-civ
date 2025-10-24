package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import androidx.room.concurrent.AtomicBoolean
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.BloodhoundRepository.BloodHoundItem
import com.aitech.weartak_civ.data.BloodhoundRepository.Point
import com.aitech.weartak_civ.data.BloodhoundRepository.Vector
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.sqrt

class BloodhoundService: Service() {

    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val repo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BloodhoundService onCreate()")
        isRunning.set(true)
        startForegroundServiceCompat(
            BATTERY_MONITOR_SERVICE_NOTIFICATION_ID,
            importance = NotificationManager.IMPORTANCE_LOW
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "BloodhoundService onDestroy()")
        isRunning.set(false)
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "BloodhoundService onStartCommand()")
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun getMyCurrPoint(): Point {
        return Point(
            lat = 41.92288763956643,
            lon = -87.6454244503331,
            hae = 1700.0,
            ce = 0.0,
            le = 0.0
        )
    }

    private fun getVector(bloodHoundItem: BloodHoundItem): Vector {
        val target = bloodHoundItem.target
        val currPoint = getMyCurrPoint()
        val activePoint = target.point

        val dx = activePoint.lat - currPoint.lat
        val dy = activePoint.lon - currPoint.lon
        val dist = sqrt((dx*dx) + (dy*dy))
        var azimuth = toDegrees(atan2(dx,dy))
        azimuth = azimuth % 360         // we want from [0,359] since 0 and 360 are the same. 
        return Vector(dist, azimuth)
    }

    private fun toDegrees(rads: Double): Double {
        val angle = (((180.0/ PI) * rads) + 180.0)
        return angle
    }


    companion object {
        val isRunning: AtomicBoolean = AtomicBoolean(false)
        private val TAG = "WearTAK-${BloodhoundService::class.java.simpleName}"
        private val BATTERY_MONITOR_SERVICE_NOTIFICATION_ID = 848
    }

}