/*
 * Copyright (C) 2025, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.AlarmManager.AlarmClockInfo
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.provider.Settings
import android.content.Context
import android.content.Context.ALARM_SERVICE
import android.content.Intent
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.annotation.DrawableRes
import androidx.compose.ui.graphics.painter.Painter
import androidx.core.app.NotificationCompat
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.data.audio.AudioDetectionEvent
import com.aitech.weartak_civ.data.audio.AudioDetectionUserLabel
import com.aitech.weartak_civ.data.DataRepository.MarkerType
import com.aitech.weartak_civ.data.model.LatLon
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.presentation.MainActivity.Constants.mainActivity
import com.aitech.weartak_civ.receiver.AlarmReceiver
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleAlt
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleBeacon
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleBeaconUpdateTime
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleDistance
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleLat
import com.aitech.weartak_civ.service.BleBeaconService.Companion.closestBleLon
import com.aitech.weartak_civ.service.CotDispatchService.BleLocationOutput
import com.aitech.weartak_civ.service.CotDispatchService.Companion.GPS_STALE_TIME_INTERVAL_MS
import com.aitech.weartak_civ.service.CotDispatchService.Companion.OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonSyntaxException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.google.gson.reflect.TypeToken
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.cos
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory
import java.security.SecureRandom
import java.text.DateFormat
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import java.lang.reflect.Method
import dalvik.system.PathClassLoader
import mil.nga.mgrs.MGRS

object Utils {
    private const val TAG = "WearTAK-Utils"
    private var logFileFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private const val DECIMAL_DEGREES_PER_METER = 3.631033E-06 //Approximate decimal degrees per meter at the equator
    const val EARTH_RADIUS = 6378137.0  // in meters
    const val GPS_ALARM_REQUEST_CODE = 234324243
    const val REDUCE_ALARM_TIME_MS = 35
    val secureRandom: SecureRandom = SecureRandom()
    // Constants not present on some Android/WearOS JAXP builds:
    private const val ACCESS_EXTERNAL_DTD    = "http://javax.xml.XMLConstants/property/accessExternalDTD"
    private const val ACCESS_EXTERNAL_SCHEMA = "http://javax.xml.XMLConstants/property/accessExternalSchema"

    fun buildFallbackLocation(): Location =
        Location("manual").apply {
            latitude = 0.0
            longitude = 0.0
            altitude = 0.0
            accuracy = 999_999.0f
            verticalAccuracyMeters = 999_999.0f
            time = System.currentTimeMillis()
        }

    // Exactly-one-place we define the CoT UTC formatter.
    // [Unverified] Adjust the pattern here if your current dateTimeFormatter uses a different one.
    private val cotUtcFormatter: DateFormat by lazy {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
    }

    fun isPointInPolygon(point: LatLon, polygon: List<LatLon>): Boolean {
        if (polygon.size < 3) return false

        val x = point.lon
        val y = point.lat

        var inside = false
        var j = polygon.lastIndex

        for (i in polygon.indices) {
            val xi = polygon[i].lon
            val yi = polygon[i].lat
            val xj = polygon[j].lon
            val yj = polygon[j].lat

            val intersects =
                ((yi > y) != (yj > y)) &&
                        (x < (xj - xi) * (y - yi) / (yj - yi + 0.0) + xi)

            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    // airplaneModeStatus value should be "1" (airplaneMode ON) / "0" (airplaneMode OFF)
    fun setAirplaneMode(airplaneModeStatus: String): Boolean {
        var success = true
        try {
            val classLoader = mainActivity.classLoader
            val pathClassLoader = PathClassLoader(
                "/system/framework/com.samsung.android.knox.wearable.common.jar",
                "/system/framework/com.samsung.android.knox.wearable.common.jar",
                classLoader)
            //DpmWrapper API: setSystemSettings(ComponentName who, String callerPackage, String settings, String value)
            var dpmWrapper: Class<*> =
                pathClassLoader.loadClass("com.samsung.android.knox.wearable.common.DpmWrapper")
            val method: Method = dpmWrapper.getDeclaredMethod(
                "setGlobalSettings",
                ComponentName::class.java, Context::class.java, String::class.java,
                String::class.java)
            method.invoke(dpmWrapper, null, mainActivity, Settings.Global.AIRPLANE_MODE_ON, airplaneModeStatus)
        } catch (e : Exception) {
            e.printStackTrace()
            success = false
        }
        Log.i(TAG, "setAirplaneMode($airplaneModeStatus) returning $success")
        return success
    }


    /**
     * Offset in human units, can be positive or negative.
     * e.g. TimeOffset(seconds = -4) matches your "clear the cot" example.
     */
    data class TimeOffset(
        val seconds: Int = 0,
        val minutes: Int = 0,
        val hours: Int = 0,
        val days: Int = 0,
        val years: Int = 0
    )

    /**
     * One snapshot of all the time values you care about for a CoT event.
     *
     * - time    -> millis when the event actually happened (click, etc.)
     * - start   -> millis used for <start>
     * - stale   -> millis used for <stale>
     * - timeCot -> ISO/Zulu string for <time>
     * - startCot-> ISO/Zulu string for <start>
     * - staleCot-> ISO/Zulu string for <stale>
     */
    data class CotEventTimeSnapshot(
        val time: Long,
        val start: Long,
        val stale: Long,
        val timeCot: String,
        val startCot: String,
        val staleCot: String
    )

    private fun Calendar.applyOffset(offset: TimeOffset) {
        if (offset.seconds != 0) {
            this.add(Calendar.SECOND, offset.seconds)
        }
        if (offset.minutes != 0) {
            this.add(Calendar.MINUTE, offset.minutes)
        }
        if (offset.hours != 0) {
            this.add(Calendar.HOUR_OF_DAY, offset.hours)
        }
        if (offset.days != 0) {
            this.add(Calendar.DAY_OF_YEAR, offset.days)
        }
        if (offset.years != 0) {
            this.add(Calendar.YEAR, offset.years)
        }
    }

    /**
     * Builds a CoT event time snapshot using UTC calendar math, same behavior
     * as your existing code:
     *
     *   dateTimeFormatter.setTimeZone(UTC)
     *   val calendar = Calendar.getInstance()
     *   calendar.setTime(Date())
     *   ...
     *
     * @param startOffset  how far <start> is from "now" (can be 0)
     * @param staleOffset  how far <stale> is from "now" (can be negative or positive)
     */
    fun getCotEventTimeSnapshot(
        startOffset: TimeOffset = TimeOffset(),             // usually "same as time"
        staleOffset: TimeOffset = TimeOffset(minutes = 5)   // example default stale window
    ): CotEventTimeSnapshot {

        val nowMillis = System.currentTimeMillis()

        // Base UTC calendar at "now"
        val baseCal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            timeInMillis = nowMillis
        }

        // time (no offset – when the action actually happened)
        val timeCal = baseCal.clone() as Calendar

        // start (with optional offset)
        val startCal = baseCal.clone() as Calendar
        startCal.applyOffset(startOffset)

        // stale (with optional offset)
        val staleCal = baseCal.clone() as Calendar
        staleCal.applyOffset(staleOffset)

        return CotEventTimeSnapshot(
            time = timeCal.timeInMillis,
            start = startCal.timeInMillis,
            stale = staleCal.timeInMillis,
            timeCot = cotUtcFormatter.format(timeCal.time),
            startCot = cotUtcFormatter.format(startCal.time),
            staleCot = cotUtcFormatter.format(staleCal.time)
        )
    }

    fun runTimerJob(timeMs: Long) {
        object : Thread() {
            override fun run() {
                super.run()
                //val logStr="runTimerJob called with timeout: $timeMs ms"
                //Log.d(TAG, logStr)
                //writeToLogFileLocation(logFile, logStr)
                startGlobalTimer(timeMs)
            }
        }.start()
    }

    fun startGlobalTimer(timeMs: Long) {
        //Log.d(TAG, "startGlobalTimer called with timeout: $timeMs ms")
        val alarmIntent = Intent(mainActivity, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(mainActivity, GPS_ALARM_REQUEST_CODE, alarmIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarmManager = mainActivity.getSystemService(ALARM_SERVICE) as AlarmManager
        val ac = AlarmClockInfo(System.currentTimeMillis() + timeMs - REDUCE_ALARM_TIME_MS, pendingIntent)
        alarmManager.setAlarmClock(ac, pendingIntent)
    }

    fun startMainActivityIfNotRunning(context: Context) {
        if (!isActivityRunning(MainActivity::class.java, context)) {
            Log.w(TAG, "MainActivity not running. Starting it...")
            val bootIntent = Intent(context, MainActivity::class.java)
            bootIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(bootIntent)
        } else {
            Log.w(TAG, "Activity is already running. Do not start one")
        }
    }

    fun isActivityRunning(activityClass: Class<*>, currContext: Context): Boolean {
        val activityManager = currContext.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val tasks = activityManager.getRunningTasks(Int.MAX_VALUE)

        for (task in tasks) {
            if (activityClass.canonicalName.equals(task.baseActivity?.className ?: "null", ignoreCase = true)) {
                Log.d(TAG, "isActivityRunning returned true")
                return true
            }
        }
        Log.d(TAG, "isActivityRunning returned false")
        return false
    }

    fun <T> listEqualsIgnoreOrder(list1: List<T>, list2: List<T>): Boolean {
        return list1.size == list2.size && list1.toSet() == list2.toSet()
	}

    // Managed config uses this to determine if an app restriction is configured or not.
    fun Bundle.getNullableBoolean(key: String): Boolean? =
        if (this.containsKey(key)) this.getBoolean(key) else null

    private fun Bundle.getNullableString(key: String): String? =
        if (this.containsKey(key)) this.getString(key) else null

    /**
     * Starts this Service in the foreground, creating a notification channel
     * named after the service class. Defaults can be overridden.
     *
     * @param notificationId  the ID for startForeground(...)
     * @param iconRes         small icon resource (default = R.drawable.weartak_logo)
     * @param contentText     text shown under the icon (default = "<ServiceName> running")
     * @param channelId       ID for the channel (default = "<ServiceName>_channel")
     * @param channelName     human-readable channel name (default = "<ServiceName> Background Channel")
     * @param importance      channel importance (default = IMPORTANCE_LOW)
     */
    @SuppressLint("ObsoleteSdkInt", "ServiceCast")
    fun Service.startForegroundServiceCompat(
        notificationId: Int,
        @DrawableRes iconRes: Int = R.drawable.weartak_logo,
        contentText: String = "${this.javaClass.simpleName} running",
        channelId: String = "${this.javaClass.simpleName}_channel",
        channelName: String = "${this.javaClass.simpleName} Background Channel",
        importance: Int
    ) {
        val tag = this.javaClass.simpleName
        Log.d(tag, "Starting $tag as ForegroundService")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(channelId, channelName, importance).apply {
                lightColor = Color.GRAY
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(chan)
        }

        // Build the notification
        val notification = NotificationCompat.Builder(this, channelId)
            .setOngoing(true)
            .setSmallIcon(iconRes)
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(Notification.CATEGORY_SERVICE)
            .build()

        // Finally, start as foreground
        try {
            startForeground(notificationId, notification)
        } catch (e: NullPointerException) {
            Log.e(tag, "Failed to set $tag as ForegroundService", e)
        }
    }

    fun isValidJson(jsonString: String) : Boolean {
        if (jsonString.isNullOrEmpty()) return false
        return try {
            Gson().fromJson(jsonString, Any::class.java)
            true
        } catch (e: JsonSyntaxException) {
            false
        }
    }

    fun getValueFromJson(jsonString: String, key: String): String? {
        return try {
            val gson = Gson()
            val jsonObject: JsonObject = gson.fromJson(jsonString, JsonObject::class.java)
            jsonObject.get(key)?.asString
        } catch (e: Exception) {
            null
        }
    }

    fun isStringEmptyJson(jsonString: String): Boolean {
        if (jsonString.isNullOrEmpty()) {
            return true
        }

        return try {
            Gson().fromJson(jsonString, Any::class.java)
            jsonString.trim() == "{}" || jsonString.trim() == "[]"
        } catch (e: JsonParseException) {
            false
        }
    }

    fun vibrateWatch(
        vibDurationMs: Long = 200L,
        amplitude: Int = VibrationEffect.DEFAULT_AMPLITUDE,
        numberOfVibes: Int = 1,
        timeBtwnVibesMs: Long = 300L
    ) {
        val ctx = WearTakApplication.AppCtx.app.applicationContext

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }

        vibrator?.let { vib ->
            if (!vib.hasVibrator()) return

            if (numberOfVibes <= 1) {
                // Single vibration
                val effect = VibrationEffect.createOneShot(
                    vibDurationMs,
                    amplitude
                )
                vib.vibrate(effect)
            } else {
                // Build waveform pattern:
                // [delay, vibrate, pause, vibrate, pause, ...]
                // First value must be initial delay (0 for immediate start)

                val patternSize = numberOfVibes * 2
                val timings = LongArray(patternSize)

                // amplitudes array must match timings length
                val amplitudes = IntArray(patternSize)

                for (i in 0 until numberOfVibes) {
                    val vibIndex = i * 2
                    val pauseIndex = vibIndex + 1

                    timings[vibIndex] = vibDurationMs
                    amplitudes[vibIndex] = amplitude

                    if (pauseIndex < patternSize) {
                        timings[pauseIndex] = timeBtwnVibesMs
                        amplitudes[pauseIndex] = 0
                    }
                }

                val effect = VibrationEffect.createWaveform(
                    timings,
                    amplitudes,
                    -1 // no repeat
                )

                vib.vibrate(effect)
            }
        }
    }

    fun cancelWatchVibration() {
        val ctx = WearTakApplication.AppCtx.app.applicationContext

        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vm = ctx.getSystemService(VibratorManager::class.java)
            vm?.defaultVibrator
        } else {
            ctx.getSystemService(Vibrator::class.java)
        }

        vibrator?.cancel()
    }

    fun latLonToMgrs(lat: Double, lon: Double): String {
        val mgrs = MGRS.from(lon, lat)
        return mgrs.coordinate() // 1-meter precision
    }

    fun writeToLogFileLocation(logFile: File?, data: String) {
        if (null == logFile) {
            return
        }
        try {
            val writer = FileWriter(logFile, true) // Append to existing file
            writer.write("${LocalDateTime.now().format(logFileFormatter)}: $data\n")
            writer.close()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to write log to file: ${e.message}", e)
        }
    }

    fun appendJsonLine(logFile: File?, json: String) {
        if (logFile == null) {
            return
        }
        try {
            FileWriter(logFile, true).use { writer ->
                writer.write(json)
                writer.write("\n")
            }
        } catch (e: IOException) {
            Log.e(TAG, "Failed to append json line: ${e.message}", e)
        }
    }

    private val repo by lazy {
        (WearTakApplication.AppCtx.app as WearTakApplication).repo
    }

    suspend fun logAudioDetectionFeedback(
        context: Context,
        event: AudioDetectionEvent,
        label: AudioDetectionUserLabel,
        respondedAtMs: Long,
        responseLatencyMs: Long,
        debugDirName: String,
        extra: Map<String, Any?> = emptyMap()
    ) {
        val dir = File(context.filesDir, debugDirName).apply { mkdirs() }
        val logFile = File(dir, "${event.detectorKey}_feedback.jsonl")
        val sl = repo.snapshotLocation()
        val ss = repo.snapshotSystem()

        val obj = buildAudioDetectionEventJson(event, sl, ss).apply {
            put("label", label.name)
            put("respondedAtMs", respondedAtMs)
            put("responseLatencyMs", responseLatencyMs)
            put("triggerToFeedbackMs", respondedAtMs - event.timestamp)

            val extrasObj = org.json.JSONObject()
            for ((k, v) in extra) extrasObj.put(k, v)
            put("extra", extrasObj)
        }

        appendJsonLine(logFile, obj.toString())
    }

    suspend fun logAudioDetectionClassification(
        context: Context,
        event: AudioDetectionEvent,
        debugDirName: String
    ) {
        val classification = event.classification ?: return
        val dir = File(context.filesDir, debugDirName).apply { mkdirs() }
        val logFile = File(dir, "${event.detectorKey}_classification.jsonl")
        val sl = repo.snapshotLocation()
        val ss = repo.snapshotSystem()

        val obj = buildAudioDetectionEventJson(event, sl, ss).apply {
            put("predictedLabel", classification.predictedLabel)
            put("predictedClassIndex", classification.predictedIndex)
            put("predictedScore", classification.predictedScore)
        }

        appendJsonLine(logFile, obj.toString())
    }

    private fun buildAudioDetectionEventJson(
        event: AudioDetectionEvent,
        location: DataRepository.LocationSnapshot?,
        system: DataRepository.SystemSnapshot
    ): org.json.JSONObject = org.json.JSONObject().apply {
        put("uid", event.uid)
        put("inferenceWindowUid", event.uid)
        put("eventTimestamp", event.timestamp)
        put("detectorKey", event.detectorKey)
        put("confidence", event.confidence)
        put("triggerPcmPath", event.triggerPcmPath)
        put("audioSessionId", event.chunkRef.sessionId)
        put("audioSeq", event.chunkRef.seq)
        put("audioChunkTimestampMs", event.chunkRef.timestampMs)
        put("sampleRateHz", event.chunkRef.sampleRateHz)
        put("channels", event.chunkRef.channels)
        put("micSource", event.audioSource)
        put("inferStartElapsedMs", event.inferStartElapsedMs)
        put("inferEndElapsedMs", event.inferEndElapsedMs)
        putClassificationFields(event)
        putDeviceFields(system)
        putLocationFields(location)
        put("isWorn", system.systemStates.isWorn)
        put("charging", system.systemStates.charging)
        put("bat", system.batteryPercent)
        put("latestActRec", repo.latestActivityRecognized.value)
    }

    private fun org.json.JSONObject.putClassificationFields(event: AudioDetectionEvent) {
        event.classification?.let { classification ->
            put("classifierLabel", classification.predictedLabel)
            put("classifierClassIndex", classification.predictedIndex)
            put("classifierScore", classification.predictedScore)
            put("classifierScores", org.json.JSONArray(classification.scores))
            put("classifierLabels", org.json.JSONArray(classification.labels))
        }
    }

    private fun org.json.JSONObject.putDeviceFields(system: DataRepository.SystemSnapshot) {
        put("manufacturer", android.os.Build.MANUFACTURER)
        put("model", android.os.Build.MODEL)
        put("sdkInt", android.os.Build.VERSION.SDK_INT)
        put("wearTakVersion", system.wearTakVersion)
        put("callsign", system.callsign)
        put("deviceId", system.deviceId)
    }

    private fun org.json.JSONObject.putLocationFields(location: DataRepository.LocationSnapshot?) {
        location?.let {
            put("lat", it.lat)
            put("lon", it.lon)
            put("hae", it.hae)
            put("ce", it.ce)
        }
    }

    fun jitteredLatLon(lat: Double, lon: Double, maxJitterM: Double): Pair<Double, Double> {
        val maxJitterUnsignedM = abs(maxJitterM)
        // Generate random offsets in [-maxJitterUnsignedM, maxJitterUnsignedM] meters
        val dx = (secureRandom.nextDouble() * 2 * maxJitterUnsignedM) - maxJitterUnsignedM  // East-West
        val dy = (secureRandom.nextDouble() * 2 * maxJitterUnsignedM) - maxJitterUnsignedM  // North-South
        //Log.d(TAG, "jitteredLatLon: dlatM($dy), dLonM($dx): dist(${(sqrt(dx*dx + dy*dy))})")

        // Convert to angular distance in radians
        val dLat = dy / EARTH_RADIUS
        val dLon = dx / (EARTH_RADIUS * cos(Math.toRadians(lat)))

        // Apply to original coordinates
        val newLat = lat + Math.toDegrees(dLat)
        val newLon = lon + Math.toDegrees(dLon)

        return Pair(newLat, newLon)
    }

    /* Ditto Track Document uses this legacy cot field. It is
     * some sort of convoluted, inefficient JSON string and is
     * viciously unreadable, but required for meshing PLI in tak.
     * This function alleviates the pain.
    */
    fun cotNode(
        name: String,
        attrs: Map<String, Any?> = emptyMap(),
        children: Map<String, Any?> = emptyMap(),
        indexValue: Any? = null
    ): Map<String, Any?> = mapOf(
        "a" to attrs,
        "d" to children,
        "i" to indexValue,
        "n" to name
    )

    // 1) Ditto‐only version
    fun buildCotDetailDitto(
        callsign: String,
        roleValue: String,
        teamValue: String,
        uid: String,
        battery: Int,
        // takv attributes
        takvOs: String,
        takvVersion: String,
        takvDevice: String,
        takvPlatform: String,
        // precisionlocation (defaults to GPS)
        altsrc: String = "GPS",
        geopointsrc: String = "GPS",
        // ditto attributes
        dittoA: String,
        dittoIp: String,
        dittoVersion: String,
        dittoDeviceName: String,
        // contact endpoint
        endpoint: String = "*:-1:stcp"
    ): String {
        return """
        <detail>
            <takv os='$takvOs' version='$takvVersion' device='$takvDevice' platform='$takvPlatform'/>
            <contact endpoint='$endpoint' callsign='$callsign'/>
            <uid Droid='$uid'/>
            <precisionlocation altsrc='$altsrc' geopointsrc='$geopointsrc'/>
            <__group role='$roleValue' name='$teamValue'/>
            <status battery='$battery'/>
            <ditto a='$dittoA' ip='$dittoIp' version='$dittoVersion' deviceName='$dittoDeviceName'/>
        </detail>
    """.trimIndent()
    }

    // 1
    fun buildCotDetail(
        // existing parameters
        callsign: String,
        roleValue: String,
        teamValue: String,
        uid: String,
        battery: Int,
        // takv-related
        takvOs: String,
        takvVersion: String,
        takvDevice: String,
        takvPlatform: String,
        // precisionlocation (defaults to GPS)
        altsrc: String = "GPS",
        geopointsrc: String = "GPS",
        // ditto-related
        dittoA: String,
        dittoIp: String,
        dittoVersion: String,
        dittoDeviceName: String,
        // contact endpoint (default if you still want it)
        endpoint: String = "*:-1:stcp"
    ): String {
        return """
        <detail>
            <takv 
                os='$takvOs' 
                version='$takvVersion' 
                device='$takvDevice' 
                platform='$takvPlatform'
            />
            <contact 
                endpoint='$endpoint' 
                callsign='$callsign'
            />
            <uid Droid='$uid'/>
            <precisionlocation 
                altsrc='$altsrc' 
                geopointsrc='$geopointsrc'
            />
            <__group 
                role='$roleValue' 
                name='$teamValue'
            />
            <status battery='$battery'/>
            <ditto 
                a='$dittoA' 
                ip='$dittoIp' 
                version='$dittoVersion' 
                deviceName='$dittoDeviceName'
            />
        </detail>
    """.trimIndent()
    }
    fun buildCotDetailMerged(
        // — your original fields —
        heartRate: Int,
        battery:    Int,
        callsign:   String,
        roleValue:  String,
        teamValue:  String,
        uid:        String,
        bearingActual: Float,
        speedActual:   Float,

        // — ATAK version/device info (matches your Ditto‐only sample) —
        takvOs:      String = "WEAROS",
        takvVersion: String = "WearTAK-${Build.VERSION.RELEASE}",
        takvDevice:  String,
        takvPlatform:String = "ATAK-CIV",

        // — precisionlocation defaults to GPS —
        altsrc:      String = "GPS",
        geopointsrc: String = "GPS",

        // — ditto payload (matches your Ditto‐only sample) —
        dittoA:          String,
        dittoIp:         String = "0.0.0.0",
        dittoVersion:    String,
        dittoDeviceName: String,

        // — contact defaults —
        endpoint:   String = "*:-1:stcp",
        phone:      String = "11234567890"
    ): String = "<detail><takv os='$takvOs' version='$takvVersion' device='$takvDevice' platform='$takvPlatform'/><contact endpoint='$endpoint' callsign='$callsign'/><uid Droid='$uid'/><precisionlocation altsrc='$altsrc' geopointsrc='$geopointsrc'/><__group role='$roleValue' name='$teamValue'/><status battery='$battery'/><remarks>HR: $heartRate bpm; Batt: $battery%</remarks><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><bat>$battery</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/><ditto a='$dittoA' ip='$dittoIp' version='$dittoVersion' deviceName='$dittoDeviceName'/></detail>"

    fun buildCotDetailOld(
        heartRate: Int,
        battery: Int,
        callsign: String,
        roleValue: String,
        teamValue: String,
        uid: String,
        bearingActual: Float,
        speedActual: Float,
        endpoint: String = "*:-1:stcp",
        phone: String = "11234567890"
    ): String {
        return """<detail><remarks>HR: $heartRate bpm; Batt: $battery%</remarks><status battery='$battery'/><contact endpoint='$endpoint' phone='$phone' callsign='$callsign'/><__group role='$roleValue' name='$teamValue'/><biometrics><device><model>WEAROS</model><uid>$uid</uid><hr>$heartRate</hr><bat>$battery</bat></device></biometrics><track course='$bearingActual' speed='$speedActual'/></detail>""".trimIndent()}

    /** Minimal data models for chat rooms & messages. */
    data class ChatMessage(
        val message: String,
        val uid: String,
        val callsign: String,
        val timeReceived: Long
    )

    data class ChatRoom(
        val uid: String,
        val title: String,
        val messageList: List<ChatMessage>,
        var newMessagesPresent: Boolean,
        val timeLastOpened: Long,
        var timeLastClosed: Long
    )

    /** Minimal data models for bloodhound. */
    data class BloodhoundItem(
        val target: String,
        val uid: String,
        val callsign: String,
        val timeReceived: Long,
        val distance: Double, // May not come directly from COT, changes consistently
        val orientation: Float, // May not come directly from COT, changes consistently
        val bloodhounding: Boolean,
        val acknowledged: Boolean = false
    )

    fun normalize0to360(deg: Double): Double =
        ((deg % 360.0) + 360.0) % 360.0

    /** Minimal data models for physio alerts. */
    enum class AlertChipState { None, Warning, Emergency }

    data class PhysioAlert(
        val alert: String,
        val level: AlertChipState,
        val time: Long,
        val trigger: String
	)
    data class SelectedPhysioMetric(
        val key: String,
        val icon: Painter?,
        val metric: String,
        val value: String
    )
    data class TextSelectionButton (
        val shortenedText: String,
        val longText: String
    )

    object LocationUtils {
        fun mapLocationAgainstClosestBleBeacon(loc: Location): BleLocationOutput {

            var latActual = loc.latitude; var lonActual = loc.longitude; var haeActual = loc.altitude; var ceActual = loc.accuracy; val startTimeInMs = System.currentTimeMillis();

            //Log.d(TAG, "closestBleBeaconUpdateTime($closestBleBeaconUpdateTime); currTime($startTimeInMs); diff (${abs(startTimeInMs - closestBleBeaconUpdateTime)})")
            if ((closestBleBeaconUpdateTime > 0) && (abs(startTimeInMs - closestBleBeaconUpdateTime) <= GPS_STALE_TIME_INTERVAL_MS)) {
                if (ceActual > closestBleDistance) {
                    //val logString = "Using BLE($closestBleLat, $closestBleLon, $closestBleAlt): gpsHorizError($ceActual) > bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                    //Log.d(TAG, logString)
                    //writeToLogFileLocation(logFile, logString)
                    ceActual = closestBleDistance.toFloat()
                    latActual = closestBleLat
                    lonActual = closestBleLon
                    haeActual = closestBleAlt
                    closestBleBeacon?.overrideGPSWithBLECount = 0
                } else {
                    val currClosestBleBeacon = closestBleBeacon!!
                    if (currClosestBleBeacon.overrideGPSWithBLECount++ < OVERRIDE_GPS_WITH_BLE_EXIT_HYSTERESIS_COUNT) {
                        //val logString = "GPS Hysteresis(${currClosestBleBeacon.overrideGPSWithBLECount}) Using BLE($closestBleLat, $closestBleLon, $closestBleAlt): gpsHorizError($ceActual) <= bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                        //Log.d(TAG, logString)
                        //writeToLogFileLocation(logFile, logString)
                        ceActual = closestBleDistance.toFloat()
                        latActual = closestBleLat
                        lonActual = closestBleLon
                        haeActual = closestBleAlt
                    } else {
                        currClosestBleBeacon.overrideGPSWithBLECount = 0
                        //val logString = "Using GPS: gpsHorizAcc($ceActual) <= bleClosestDist($closestBleDistance) w/ ble updated ${String.format("%.3f", abs(startTimeInMs - closestBleBeaconUpdateTime)/1000.0)}s ago"
                        //Log.d(TAG, logString)
                        //writeToLogFileLocation(logFile, logString)
                    }
                }
            } else {
                //val logString = "Using GPS: gpsHorizAcc($ceActual) w/ closestBleBeaconUpdateTime($closestBleBeaconUpdateTime); currTime($startTimeInMs); diff (${abs(startTimeInMs - closestBleBeaconUpdateTime)})"
                //Log.d(TAG, logString)
                //writeToLogFileLocation(logFile, logString)
            }
            return BleLocationOutput(latActual, lonActual, haeActual, ceActual)
        }
    }

    object PhysioUtils {
        private fun calculateMaxHeartRate(ageYears: Int): Double {
            return (208 - 0.7 * ageYears)
        }

        fun calculateHeartRateReserve(maxHr: Int, restingHr: Int): Int {
            return (maxHr - restingHr)
        }

        fun calculateExertion(currentHr: Int, ageYears: Int, restingHr: Int): Double {
            //Log.i(TAG, "calculateExertion(hr=$currentHr, age=$ageYears, restHr=$restingHr)")
            if (currentHr < 0.0) return -1.0
            val mhr = calculateMaxHeartRate(ageYears)
            val hrr = mhr - restingHr
            if (hrr <= 0) return 1.0 // mhr >= resting heart rate, hence 100% exertion
            //Log.i(TAG, "exertion ($currentHr, $ageYears, $restingHr, $mhr): ($currentHr-$restingHr)/($mhr-$restingHr)")
            return ((currentHr - restingHr).coerceAtLeast(0)) / hrr // If currentHr <= restingHr, then the user is not at all exerting them selfs, hence exertion = 0
        }

        fun classifyExertion(exertion: Double): String = when {
            exertion >= 0.7 -> "VIGOROUS"
            exertion >= 0.5 -> "MODERATE"
            exertion < 0.0 -> "UNKNOWN"
            else            -> "LIGHT"
        }

        fun convertFarenheitToCelsius(f: Float?): Float {
            return (f?.minus(32))?.times(5)?.div(9) ?: 0F
        }

        sealed class PhysioEvent {
            data class Hr(val bpm: Int, val t: Long) : PhysioEvent()
            data class Motion(val accelRms: Double, val t: Long) : PhysioEvent()
        }

        class RestingHeartRateAggregator(
            private val maxSamples: Int = 15 // 15 * 2 hr samples/sec --> 30 second buffer window
        ) {
            private val buffer = ArrayDeque<Double>(maxSamples)
            private val lock = Mutex()

            suspend fun pushSample(bpm: Double) {
                lock.withLock {
                    buffer.addLast(bpm)
                    if (buffer.size > maxSamples) buffer.removeFirst()
                }
            }

            suspend fun computeAverage(minSamples: Int = maxSamples / 2): Double? {
                return lock.withLock {
                    if (buffer.size >= minSamples) {
                        buffer.average()
                    } else null
                }
            }
        }
    }

    object SettingsUtils {
        private const val TAG = "WearTAK-SettingsUtils"

        data class CategoryRolesEntry(
            val category: String, val roles: List<String>
        )

        /**
         * Reads the default roles/categories from res/raw/roles_json_config.json.
         * On any error, logs it and returns an empty list.
         */
        fun Context.defaultRoleCategoryEntriesFromRaw(): List<CategoryRolesEntry> {
            return try {
                Log.i(TAG, "Loading default categories from raw JSON")
                val jsonStr = resources.openRawResource(R.raw.roles_json_config)
                    .bufferedReader()
                    .use { it.readText() }

                // Split into typeToken and type for minifyEnabled true. Do not combine into one line
                val typeToken = object : TypeToken<List<CategoryRolesEntry>>() {}
                val type = typeToken.type
                Gson().fromJson<List<CategoryRolesEntry>>(jsonStr, type).also {
                    Log.i(TAG, "Parsed ${it.size} default categories")
                }
            } catch (io: IOException) {
                Log.e(TAG, "I/O error reading roles_json_config.json", io)
                emptyList()
            } catch (js: JsonSyntaxException) {
                Log.e(TAG, "Malformed JSON in roles_json_config.json", js)
                emptyList()
            } catch (jp: JsonParseException) {
                Log.e(TAG, "Gson failed to parse JSON", jp)
                emptyList()
            } catch (ex: Exception) {
                Log.e(TAG, "Unexpected error loading default categories", ex)
                emptyList()
            }
        }
    }

    fun timeNowIsoUtc(): String {
        return java.time.Instant.now().toString()
    }

    fun oneYearFromNowIsoUtc(): String {
        return java.time.Instant.now()
            .plus(java.time.Period.ofYears(1))
            .toString()
    }

    fun toMillis(x: Long?): Long? =
        x?.let { if (it < 1_000_000_000_000L) it * 1000 else it }

    object CotUtils {

        // Point used at DROP time, reused on CLEAR
        data class CotMarkerInfo(
            val lat: Double?,
            val lon: Double?,
            val hae: Double?,
            val ce: Float?,
            val le: Float?,
            val type: String,
            val icon: String
        )

        data class MarkerCotEvent(
            val version: String,
            val uid: String,
            val type: String,
            val how: String,
            val time: Long,
            val start: Long,
            val stale: Long,
            val access: String?,
            val point: Point,
            val detail: MarkerDetail,
        )

        data class UserPositionCotEvent(
            val version: String,
            val uid: String,
            val team: String,
            val type: String,
            val role: String,
            val how: String,
            val time: Long,
            val start: Long,
            val stale: Long,
            val access: String?,
            val point: Point,
            val detail: MarkerDetail,

        )

        data class MarkerMapItem(
            val cotEvent: MarkerCotEvent
        ) {
            val uid: String = cotEvent.uid
            val type: String = cotEvent.type
            val callsign: String? = cotEvent.detail.markerCallsign
            val point: Point = cotEvent.point
            val creator: Creator? = cotEvent.detail.creator   // who created this map item / marker?
            val link: Link? = cotEvent.detail.link            // who sent us this map item / marker?
        }

        data class Point(
            val lat: Double,
            val lon: Double,
            val hae: Double,
            val ce: Double,
            val le: Double
        )

        data class MarkerDetail(
            val markerCallsign: String?,
            val link: Link?,
            val creator: Creator?,
            val usericon: String?,
            val remarks: String?,
            val contact: String?
        )

        data class Link(
            var uid: String?,
            var productionTime: Long?,
            var type: String?,
            var parentCallsign: String?,
            var relation: String?
        )

        data class Creator(
            var uid: String?,
            var callsign: String?,
            var time: Long?,
            var type: String?,
        )

        object Bloodhound {
            fun parseUserPositionCotOrNull(rawXml: String): UserPositionCotEvent? {
                val typeStr = attrOfEvent(rawXml, "type") ?: return null
                if (!isUserPositionType(typeStr)) return null

                val uid = attrOfEvent(rawXml, "uid") ?: return null
                val how = attrOfEvent(rawXml, "how") ?: ""
                val version = attrOfEvent(rawXml, "version") ?: ""
                val access = attrOfEvent(rawXml, "access")

                val timeIso = attrOfEvent(rawXml, "time") ?: return null
                val startIso = attrOfEvent(rawXml, "start") ?: timeIso
                val staleIso = attrOfEvent(rawXml, "stale") ?: timeIso
                val team = groupName(rawXml) ?: ""
                val role = groupRole(rawXml) ?: ""

                val point = pointOf(rawXml) ?: return null

                val detail = MarkerDetail(
                    markerCallsign = contactCallsign(rawXml) ?: "",
                    creator = creatorOf(rawXml),
                    link = linkOf(rawXml),
                    usericon = null,
                    remarks = extractElementText(rawXml, "remarks"),
                    contact = null
                )

                return UserPositionCotEvent(
                    version = version,
                    uid = uid,
                    team = team,
                    type = typeStr,
                    how = how,
                    time = parseIsoToEpochMs(timeIso),
                    start = parseIsoToEpochMs(startIso),
                    stale = parseIsoToEpochMs(staleIso),
                    access = access,
                    point = point,
                    detail = detail,
                    role = role
                )
            }

            fun isUserPositionType(typeStr: String): Boolean {
                return typeStr == "a-f-G-U-C" || typeStr.startsWith("a-f-G-U-C-")
            }

            /**
             * MVP entrypoint:
             * Returns a MarkerCotEvent if the raw XML is a *marker* CoT; otherwise null.
             * - No side effects
             * - Minimal fields only
             */
            fun parseMarkerCotOrNull(rawXml: String): MarkerCotEvent? {
                // 1) Fast-guard: extract event@type and decide if this is a marker
                val typeStr = attrOfEvent(rawXml, "type") ?: return null
                if (!isMarkerType(typeStr)) return null

                // 2) Extract event-level attrs (uid/how/version/time/start/stale/access)
                val uid = attrOfEvent(rawXml, "uid") ?: return null
                val how = attrOfEvent(rawXml, "how") ?: ""
                val version = attrOfEvent(rawXml, "version") ?: ""
                val access = attrOfEvent(rawXml, "access")

                val timeIso = attrOfEvent(rawXml, "time") ?: return null
                val startIso = attrOfEvent(rawXml, "start") ?: timeIso
                val staleIso = attrOfEvent(rawXml, "stale") ?: timeIso

                val timeEpoch = parseIsoToEpochMs(timeIso)
                val startEpoch = parseIsoToEpochMs(startIso)
                val staleEpoch = parseIsoToEpochMs(staleIso)
                // 3) Extract <point .../>
                val point = pointOf(rawXml) ?: return null

                // 4) Extract <detail> bits we care about
                val callsign = contactCallsign(rawXml) ?: "" // keep empty-string safe for UI
                val creator = creatorOf(rawXml)
                val link = linkOf(rawXml)
                val remark = extractElementText(rawXml, "remarks")

                val detail = MarkerDetail(
                    markerCallsign = callsign,
                    creator = creator,
                    link = link,
                    usericon = null,
                    remarks = remark,
                    contact = null
                )

                return MarkerCotEvent(
                    version = version,
                    uid = uid,
                    type = typeStr,
                    how = how,
                    time = timeEpoch,
                    start = startEpoch,
                    stale = staleEpoch,
                    access = access,
                    point = point,
                    detail = detail
                )
            }


            // --- Marker classification (MVP) ---

            /**
             * Returns true if CoT `type` represents one of your supported marker types.
             * Start strict and widen later as needed.
             */
            fun isMarkerType(typeStr: String): Boolean {
                val types = listOf(MarkerType.FRIENDLY.type,MarkerType.UNKNOWN.type,MarkerType.HOSTILE.type,MarkerType.NEUTRAL.type,"b-m-p-s-p-i") // TODO: Do we want to add "b-m-p-s-p-i" to MarkerType
//                val isMarker = types.contains(typeStr)
//                if (!isMarker) {
//                    Log.i(TAG, "Marker not suspected: \n $typeStr")
//                }
                return types.contains(typeStr)
            }

            // --- XML extractors (MVP, single-pass regex per getter) ---

            /** Extract an attribute from the first <event ...> element. */
            private fun attrOfEvent(xml: String, name: String): String? {
                return extractAttr(xml, "event", name)
            }

            /** Extract <point ...> as Point (requires lat/lon/hae/ce/le). */
            private fun pointOf(xml: String): Point? {
                val lat = extractAttr(xml, "point", "lat")?.toDoubleOrNull() ?: return null
                val lon = extractAttr(xml, "point", "lon")?.toDoubleOrNull() ?: return null
                val hae = extractAttr(xml, "point", "hae")?.toDoubleOrNull() ?: return null
                val ce  = extractAttr(xml, "point", "ce")?.toDoubleOrNull() ?: return null
                val le  = extractAttr(xml, "point", "le")?.toDoubleOrNull() ?: return null
                return Point(lat, lon, hae, ce, le)
            }

            /** Extract contact@callsign inside <detail>. */
            private fun contactCallsign(xml: String): String? {
                return extractAttr(xml, "contact", "callsign")
            }

            /** Extract _group@name inside <detail>. */
            private fun groupName(xml: String): String? {
                return extractAttr(xml, "__group", "name")
            }

            /** Extract _group@role inside <detail>. */
            private fun groupRole(xml: String): String? {
                return extractAttr(xml, "__group", "role")
            }

            /** Extract <creator .../> attributes. */
            private fun creatorOf(xml: String): Creator {
                val uid  = extractAttr(xml, "creator", "uid")
                val cs   = extractAttr(xml, "creator", "callsign")
                val type = extractAttr(xml, "creator", "type")
                val timeIso = extractAttr(xml, "creator", "time")
                val time = timeIso?.let { parseIsoToEpochMs(it) }
                return Creator(uid = uid, callsign = cs, type = type, time = time)
            }

            /** Extract <link .../> attributes. */
            private fun linkOf(xml: String): Link {
                val uid   = extractAttr(xml, "link", "uid")
                val type  = extractAttr(xml, "link", "type")
                val parent= extractAttr(xml, "link", "parent_callsign")
                val rel   = extractAttr(xml, "link", "relation")
                val prodIso = extractAttr(xml, "link", "production_time")
                val prod   = prodIso?.let { parseIsoToEpochMs(it) }
                return Link(
                    uid = uid,
                    type = type,
                    parentCallsign = parent,
                    relation = rel,
                    productionTime = prod
                )
            }

            /**
             * Very small helper to extract an attribute of the *first* matching element name.
             * Uses a small, anchored regex and returns the first capture group if found.
             *
             * - Matches both single and double quoted attribute values.
             * - Case-sensitive tag/attr names (as in your samples).
             * - Does not parse nested structures; intentionally MVP-simple.
             */
            private fun extractAttr(xml: String, elementName: String, attrName: String): String? {
                val pattern = """<${Regex.escape(elementName)}\b[^>]*?\b${Regex.escape(attrName)}\s*=\s*(['"])(.*?)\1""".toRegex(RegexOption.DOT_MATCHES_ALL)
                val m = pattern.find(xml) ?: return null
                return m.groupValues.getOrNull(2)
            }

            private fun extractElementText(xml: String, elementName: String): String? {
                val pattern = """<${Regex.escape(elementName)}\b[^>]*>(.*?)</${Regex.escape(elementName)}>"""
                    .toRegex(setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

                val match = pattern.find(xml) ?: return null
                return match.groupValues[1].trim()
            }

            /** ISO8601 (e.g., 2025-10-22T17:54:57Z or ...11:00:11.065Z) → epoch millis. */
            private fun parseIsoToEpochMs(iso: String): Long {
                // Try direct parse
                try {
                    return java.time.Instant.parse(iso).toEpochMilli()
                } catch (_: Exception) {
                    // Fallbacks: ensure trailing Z, trim subsecond if needed
                    val withZ = if (iso.endsWith("Z", ignoreCase = true)) iso else iso + "Z"
                    try {
                        return java.time.Instant.parse(withZ).toEpochMilli()
                    } catch (_: Exception) {
                        val trimMillis = withZ.substringBefore('.', withZ)
                        return try { java.time.Instant.parse(trimMillis).toEpochMilli() } catch (_: Exception) { 0L }
                    }
                }
            }
        }

        /** Quick screen for chat‐related COTs. */
        fun isChatMessage(raw: String): Boolean =
            raw.contains("<__chat") && raw.contains("<remarks")

        /** Is this message addressed to me or to “All Chat Rooms”? */
        fun isChatForMe(raw: String, myUid: String): Boolean {
            return raw.contains("to=\"${MainActivity.callsign}\"")
                    || raw.contains("""to='$myUid'""")
                    || raw.contains("""to="$myUid"""")
                    || (raw.contains("""to='All Chat Rooms'""") && !(raw.contains("""senderCallsign="${MainActivity.callsign}"""") || raw.contains("""senderCallsign='${MainActivity.callsign}'""")))
                    || (raw.contains("""to="All Chat Rooms"""") && !(raw.contains("""senderCallsign="${MainActivity.callsign}"""") || raw.contains("""senderCallsign='${MainActivity.callsign}'""")))
        }

        /** DOM‐plus‐regex hybrid parser for extracting ChatRooms. */
        fun parseChatRooms(raw: String, myUid: String): List<ChatRoom> {
            val rooms = mutableListOf<ChatRoom>()

            // 1) Try a secure DOM parse
            val doc = try {
                val cleaned = raw.trimStart { it <= ' ' || it == '\uFEFF' } // strip BOM/leading ws

                val factory = DocumentBuilderFactory.newInstance().apply {
                    isNamespaceAware = true
                    isValidating = false

                    // Secure-processing is widely supported on Android, but guard anyway
                    try { setFeature(javax.xml.XMLConstants.FEATURE_SECURE_PROCESSING, true) } catch (_: Exception) {}

                    // Block external DTD/Schema fetches (no-op if unsupported)
                    try { setAttribute(ACCESS_EXTERNAL_DTD, "") } catch (_: Exception) {}
                    try { setAttribute(ACCESS_EXTERNAL_SCHEMA, "") } catch (_: Exception) {}

                    // Best-effort DOCTYPE ban: some Android impls don't recognize this feature
                    try { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) } catch (_: Exception) {}
                }

                val builder = factory.newDocumentBuilder().apply {
                    // Final safety net: never resolve external entities
                    setEntityResolver { _, _ -> InputSource(StringReader("")) }
                    // Optional: hard-fail if a DOCTYPE is present
                    // if (cleaned.contains("<!DOCTYPE", ignoreCase = true)) throw IllegalArgumentException("DOCTYPE not allowed")
                }

                builder.parse(InputSource(StringReader(cleaned)))
            } catch (e: Exception) {
                Log.e(TAG, "Exception: ${e::class.simpleName}: ${e.message}", e)
                null
            }

            Log.d(TAG, "doc\n${doc.toString()}")
            if (doc != null) {
                val chatElems   = doc.getElementsByTagName("__chat")
                val remarkElems = doc.getElementsByTagName("remarks")
                for (i in 0 until chatElems.length) {
                    val c = chatElems.item(i) as? Element ?: continue
                    // extract sender device UID
                    val grpEl        = c.getElementsByTagName("chatgrp").item(0) as? Element
                    val senderUid    = grpEl?.getAttribute("uid0") ?: continue
                    val senderCallsign = c.getAttribute("senderCallsign")

                    for (j in 0 until remarkElems.length) {
                        val r     = remarkElems.item(j) as? Element ?: continue
                        val toUid = r.getAttribute("to")
                        val text  = r.textContent.trim()
                        if ((toUid == MainActivity.callsign) || (toUid == myUid) || (toUid == "All Chat Rooms")) {
                            rooms += ChatRoom(
                                uid               = senderUid,
                                title             = senderCallsign,
                                messageList       = listOf(ChatMessage(text, senderUid, senderCallsign, System.currentTimeMillis())),
                                newMessagesPresent= true,
                                timeLastOpened    = 0L,
                                timeLastClosed    = 0L
                            )
                        }
                    }
                }
            } else {
                // 2) Regex fallback: grab senderUid and callsign, then remarks
                val grpRx    = """<chatgrp[^>]*uid0=['"]([^'"]+)['"]""".toRegex()
                val callRx   = """<__chat[^>]*senderCallsign=['"]([^'"]+)['"]""".toRegex()
                val remarkRx = """<remarks[^>]*to=['"]([^'"]+)['"][^>]*>(.*?)</remarks>""".toRegex(RegexOption.DOT_MATCHES_ALL)

                val grpMatch  = grpRx.find(raw)
                val callMatch = callRx.find(raw)
                val rMatches  = remarkRx.findAll(raw)

                if (grpMatch != null && callMatch != null) {
                    val senderUid      = grpMatch.groupValues[1]
                    val senderCallsign = callMatch.groupValues[1]

                    for (m in rMatches) {
                        val toUid = m.groupValues[1]
                        val text  = m.groupValues[2].trim()
                        if (toUid == myUid || toUid == "All Chat Rooms") {
                            rooms += ChatRoom(
                                uid               = senderUid,
                                title             = senderCallsign,
                                messageList       = listOf(ChatMessage(text, senderUid, senderCallsign, System.currentTimeMillis())),
                                newMessagesPresent= true,
                                timeLastOpened    = 0L,
                                timeLastClosed    = 0L
                            )
                        }
                    }
                }
            }

            return rooms
        }

        class CotDetail(elementName: String = "detail") {
            private var elemName: String = elementName
                set(value) {
                    require(value.isNotBlank()) { "Element name cannot be blank" }
                    field = value
                }

            private val attrs = LinkedHashMap<String, String>()
            private val children = mutableListOf<CotDetail>()
            private var innerText: String? = null

            fun elementName(): String = elemName

            fun setElementName(name: String): CotDetail {
                this.elemName = name
                return this
            }

            fun setAttribute(name: String, value: String?): CotDetail {
                if (!name.isNullOrBlank() && value != null) {
                    attrs[name] = value
                }
                return this
            }

            fun removeAttribute(name: String): String? = attrs.remove(name)

            fun clearAttributes(): CotDetail {
                attrs.clear()
                return this
            }

            /** Inner text and children are mutually exclusive (like ATAK’s CotDetail). */
            fun setInnerText(text: String?): CotDetail {
                children.clear()
                innerText = text
                return this
            }

            /** Adding a child clears innerText to mirror ATAK behavior. */
            fun addChild(child: CotDetail): CotDetail {
                require(child.elementName().isNotBlank()) { "Child element must have a name" }
                innerText = null
                children.add(child)
                return this
            }

            fun getChildren(): List<CotDetail> = children.toList()

            fun toXmlString(): String = buildString { buildXml(this) }

            private fun buildXml(out: Appendable) {
                out.append('<').append(elemName)
                for ((k, v) in attrs) {
                    out.append(' ').append(k).append("='")
                    if (v != null) out.append(escapeXml(v))
                    out.append('\'')
                }
                val text = innerText
                if (text != null) {
                    out.append('>')
                    out.append(escapeXml(text))
                    out.append("</").append(elemName).append('>')
                } else {
                    if (children.isEmpty()) {
                        out.append("/>")
                    } else {
                        out.append('>')
                        children.forEach { it.buildXml(out) }
                        out.append("</").append(elemName).append('>')
                    }
                }
            }

            private fun escapeXml(s: String): String {
                // Minimal, covers content/attr usage like ATAK’s CotDetail.escapeXmlText(...)
                val sb = StringBuilder(s.length + 16)
                for (ch in s) {
                    when (ch) {
                        '&'  -> sb.append("&amp;")
                        '<'  -> sb.append("&lt;")
                        '>'  -> sb.append("&gt;")
                        '"'  -> sb.append("&quot;")
                        '\'' -> sb.append("&apos;")
                        else -> sb.append(ch)
                    }
                }
                return sb.toString()
            }

        }

    }
}
