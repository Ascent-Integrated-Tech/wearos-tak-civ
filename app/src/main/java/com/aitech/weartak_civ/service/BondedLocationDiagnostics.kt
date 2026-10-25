package com.aitech.weartak_civ.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.GnssMeasurementsEvent
import android.location.GnssStatus
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.json.JSONArray
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.ArrayDeque
import java.util.UUID
import kotlin.math.max

enum class LocationDiagnosticQuality { GOOD, DEGRADED, UNAVAILABLE, UNKNOWN }

data class LocationDiagnosticSample(
    val source: String? = null,
    val ageMillis: Long? = null,
    val horizontalAccuracyMeters: Float? = null,
    val speedMetersPerSecond: Float? = null,
    val speedAccuracyMetersPerSecond: Float? = null,
    val bearingAccuracyDegrees: Float? = null,
    val satelliteCount: Int? = null,
    val usedSatelliteCount: Int? = null,
    val meanCn0DbHz: Float? = null,
    val maxAgcDb: Float? = null,
    val satelliteStatusSupported: Boolean? = null,
    val measurementsSupported: Boolean? = null,
    val providerAvailable: Boolean? = null,
    val error: String? = null,
    val observedAtEpochMillis: Long = System.currentTimeMillis(),
    val assessedQuality: LocationDiagnosticQuality? = null,
    val assessmentReason: String? = null
) {
    fun quality(): LocationDiagnosticQuality {
        return assessedQuality ?: measuredQuality()
    }

    private fun measuredQuality(): LocationDiagnosticQuality {
        if (providerAvailable == false || error != null) return LocationDiagnosticQuality.UNAVAILABLE
        if (providerAvailable != true) return LocationDiagnosticQuality.UNKNOWN
        if (source != LocationManager.GPS_PROVIDER) return LocationDiagnosticQuality.UNKNOWN
        if (ageMillis == null || horizontalAccuracyMeters == null ||
            satelliteStatusSupported != true || satelliteCount == null || usedSatelliteCount == null
        ) return LocationDiagnosticQuality.UNKNOWN
        if (measurementsSupported != true || meanCn0DbHz == null || maxAgcDb == null) {
            return LocationDiagnosticQuality.UNKNOWN
        }
        if (ageMillis > 30_000 || horizontalAccuracyMeters > 100f || usedSatelliteCount < 4) {
            return LocationDiagnosticQuality.DEGRADED
        }
        if (ageMillis <= 10_000 && horizontalAccuracyMeters <= 20f &&
            satelliteCount >= 4 && usedSatelliteCount >= 4 && meanCn0DbHz >= 20f
        ) return LocationDiagnosticQuality.GOOD
        return LocationDiagnosticQuality.DEGRADED
    }

    fun reason(): String = assessmentReason ?: when (measuredQuality()) {
        LocationDiagnosticQuality.UNAVAILABLE -> error ?: if (providerAvailable == false) "Provider unavailable" else "Observation unavailable"
        LocationDiagnosticQuality.UNKNOWN -> when {
            ageMillis == null -> "No active location fix observed"
            source != LocationManager.GPS_PROVIDER -> "Fix observed; satellite provenance unavailable for ${source ?: "unknown source"}"
            satelliteStatusSupported == false -> "Satellite status unsupported"
            satelliteStatusSupported == null -> "Waiting for satellite status"
            measurementsSupported != true -> "Raw GNSS measurements unsupported"
            horizontalAccuracyMeters == null -> "Horizontal accuracy unavailable"
            meanCn0DbHz == null -> "Carrier-to-noise unavailable"
            maxAgcDb == null -> "Raw GNSS AGC unavailable"
            else -> "Satellite fix details unavailable"
        }
        LocationDiagnosticQuality.DEGRADED -> when {
            ageMillis != null && ageMillis > 30_000 -> "Fix is stale"
            horizontalAccuracyMeters != null && horizontalAccuracyMeters > 100f -> "Horizontal accuracy is poor"
            usedSatelliteCount != null && usedSatelliteCount < 4 -> "Fewer than four satellites used"
            meanCn0DbHz != null && meanCn0DbHz < 20f -> "Low average carrier-to-noise"
            else -> "Fix quality is below the conservative good threshold"
        }
        LocationDiagnosticQuality.GOOD -> "Recent fix, accuracy, and satellite observations meet conservative thresholds"
    }
}

data class LocationDiagnosticEvent(
    val observedAtEpochMillis: Long,
    val source: String,
    val kind: String,
    val quality: String,
    val reason: String,
    val ageMillis: Long?,
    val horizontalAccuracyMeters: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val bearingAccuracyDegrees: Float?,
    val satelliteCount: Int?,
    val usedSatelliteCount: Int?,
    val meanCn0DbHz: Float?,
    val maxAgcDb: Float?
)

class BoundedLocationDiagnosticLog(private val capacity: Int = MAX_EVENTS) {
    private val events = ArrayDeque<LocationDiagnosticEvent>()

    @Synchronized
    fun append(event: LocationDiagnosticEvent) {
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<LocationDiagnosticEvent> = events.toList()

    @Synchronized
    fun clear() = events.clear()

    companion object {
        const val MAX_EVENTS = 512
    }
}

object LocationDiagnosticsProtocol {
    const val VERSION = 1
    const val MAX_MESSAGE_BYTES = 512
    const val HELLO = "location_diagnostics_hello"
    const val CAPABILITY = "location_diagnostics_capability"
    const val SNAPSHOT = "location_diagnostics_snapshot"
    const val STOP = "location_diagnostics_stop"
    private val UUID_PATTERN =
        Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

    fun newSession(): String = UUID.randomUUID().toString()

    fun isValidSession(value: String?): Boolean = value != null && UUID_PATTERN.matches(value)

    fun hello(session: String): JSONObject {
        require(isValidSession(session)) { "Invalid location diagnostics session" }
        return JSONObject().put("msgType", HELLO).put("version", VERSION).put("session", session)
    }

    fun stop(session: String): JSONObject {
        require(isValidSession(session)) { "Invalid location diagnostics session" }
        return JSONObject().put("msgType", STOP).put("version", VERSION).put("session", session)
    }

    fun parse(message: JSONObject, expectedType: String, expectedSession: String): JSONObject? {
        val bytes = message.toString().toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_MESSAGE_BYTES) return null
        if (message.optString("msg_type", message.optString("msgType")) != expectedType) return null
        if (message.optInt("version", -1) != VERSION ||
            message.optString("session") != expectedSession ||
            !isValidSession(expectedSession)
        ) return null
        if (hasPrivateField(message)) return null
        val envelopeFields = when (expectedType) {
            HELLO -> setOf("msg_type", "msgType", "version", "session")
            CAPABILITY -> setOf("msg_type", "msgType", "version", "session", "supported")
            SNAPSHOT -> setOf("msg_type", "msgType", "version", "session", "payload")
            STOP -> setOf("msg_type", "msgType", "version", "session")
            else -> return null
        }
        if (message.keys().asSequence().any { it !in envelopeFields }) return null
        if (expectedType == CAPABILITY && message.opt("supported") !is Boolean) return null
        if (expectedType == SNAPSHOT) {
            val payload = message.optJSONObject("payload") ?: return null
            if (!validSnapshot(payload)) return null
        }
        return message
    }

    private fun validSnapshot(payload: JSONObject): Boolean {
        if (payload.keys().asSequence().any { it !in SNAPSHOT_FIELDS }) return false
        if (payload.optString("source").length > 32 ||
            !payload.optString("source").matches(Regex("[A-Za-z0-9 _-]*"))
        ) return false
        if (payload.optString("reason").length > 80 || payload.optString("error").length > 80) return false
        val ranges = mapOf(
            "ageMs" to 86_400_000.0,
            "accuracyM" to 1_000_000.0,
            "speedMps" to 1_000.0,
            "speedAccuracyMps" to 1_000.0,
            "bearingAccuracyDeg" to 360.0,
            "satellites" to 256.0,
            "used" to 256.0,
            "meanCn0DbHz" to 100.0,
            "maxAgcDb" to 1_000.0
        )
        for ((key, maxValue) in ranges) {
            val raw = payload.opt(key)
            if (raw == null || raw == JSONObject.NULL) continue
            val minValue = if (key == "maxAgcDb") -200.0 else 0.0
            if (raw !is Number || !raw.toDouble().isFinite() ||
                raw.toDouble() !in minValue..maxValue
            ) return false
        }
        for (key in listOf("satelliteStatusSupported", "measurementsSupported", "providerAvailable")) {
            val raw = payload.opt(key)
            if (raw != null && raw != JSONObject.NULL && raw !is Boolean) return false
        }
        return payload.optString("quality").isEmpty() ||
            payload.optString("quality") in LocationDiagnosticQuality.entries.map { it.name }
    }

    private fun hasPrivateField(value: Any?): Boolean = when (value) {
        is JSONObject -> value.keys().asSequence().any { key ->
            key.lowercase() in PRIVATE_FIELDS || hasPrivateField(value.opt(key))
        }
        is JSONArray -> (0 until value.length()).any { hasPrivateField(value.opt(it)) }
        else -> false
    }

    private val PRIVATE_FIELDS = setOf(
        "lat", "latitude", "lon", "lng", "longitude", "uid", "deviceid", "address", "mac"
    )

    private val SNAPSHOT_FIELDS = setOf(
        "source", "ageMs", "accuracyM", "speedMps", "speedAccuracyMps", "bearingAccuracyDeg",
        "satellites", "used", "meanCn0DbHz", "maxAgcDb", "satelliteStatusSupported",
        "measurementsSupported", "providerAvailable", "quality", "reason", "error"
    )
}

class BondedLocationDiagnostics(private val context: Context) {
    private val tag = "WearTAK-LocationDiagnostics"
    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private val logDirectory = File(context.filesDir, "location-diagnostics")
    private val gson = Gson()
    private val log = BoundedLocationDiagnosticLog()
    private val _watch = MutableStateFlow(inactiveSample())
    val watch: StateFlow<LocationDiagnosticSample> = _watch.asStateFlow()
    private val _phone = MutableStateFlow(inactiveSample())
    val phone: StateFlow<LocationDiagnosticSample> = _phone.asStateFlow()
    @Volatile private var observationEnabled = false
    @Volatile private var session: String? = null
    @Volatile private var gnssStatusCallback: GnssStatus.Callback? = null
    @Volatile private var measurementsCallback: GnssMeasurementsEvent.Callback? = null
    @Volatile private var satelliteCount: Int? = null
    @Volatile private var usedSatelliteCount: Int? = null
    @Volatile private var meanCn0DbHz: Float? = null
    @Volatile private var maxAgcDb: Float? = null
    @Volatile private var lastLoggedKey: String? = null
    @Volatile private var lastSummaryTime: Long = 0L
    @Volatile private var lastPhoneLoggedKey: String? = null
    @Volatile private var lastPhoneSummaryTime: Long = 0L
    private val recoveryHysteresis = QualityRecoveryHysteresis()

    fun start(sessionId: String) {
        require(LocationDiagnosticsProtocol.isValidSession(sessionId)) { "Invalid diagnostic session" }
        if (session == sessionId && observationEnabled) return
        stop()
        session = sessionId
        observationEnabled = true
        _watch.value = LocationDiagnosticSample(
            assessmentReason = "Waiting for an existing watch fix in this diagnostic session"
        )
        _phone.value = LocationDiagnosticSample(
            assessmentReason = "Waiting for a phone snapshot in this diagnostic session"
        )
        observeGnss()
        record("session_started", "watch", _watch.value)
        record("session_started", "phone", _phone.value)
    }

    fun stop() {
        if (observationEnabled) {
            record("session_ended", "watch", _watch.value)
            record("session_ended", "phone", _phone.value)
        }
        observationEnabled = false
        session = null
        gnssStatusCallback?.let {
            try {
                locationManager.unregisterGnssStatusCallback(it)
            } catch (error: RuntimeException) {
                Log.w(tag, "Unable to unregister GNSS status callback", error)
            }
        }
        measurementsCallback?.let {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) locationManager.unregisterGnssMeasurementsCallback(it)
            } catch (error: RuntimeException) {
                Log.w(tag, "Unable to unregister GNSS measurements callback", error)
            }
        }
        gnssStatusCallback = null
        measurementsCallback = null
        satelliteCount = null
        usedSatelliteCount = null
        meanCn0DbHz = null
        maxAgcDb = null
        _phone.value = inactiveSample()
        lastPhoneLoggedKey = null
        lastPhoneSummaryTime = 0L
        _watch.value = inactiveSample()
        recoveryHysteresis.update(LocationDiagnosticQuality.UNKNOWN)
    }

    fun observeExistingFix(location: Location) {
        if (!observationEnabled) return
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val age = max(0L, (nowNanos - location.elapsedRealtimeNanos) / 1_000_000L)
        val statusSupported = satelliteCount != null
        val sample = LocationDiagnosticSample(
            source = location.provider ?: "unknown",
            ageMillis = age,
            horizontalAccuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
            speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
            speedAccuracyMetersPerSecond = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                location.hasSpeedAccuracy()
            ) location.speedAccuracyMetersPerSecond else null,
            bearingAccuracyDegrees = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                location.hasBearingAccuracy()
            ) location.bearingAccuracyDegrees else null,
            satelliteCount = satelliteCount,
            usedSatelliteCount = usedSatelliteCount,
            meanCn0DbHz = meanCn0DbHz,
            maxAgcDb = maxAgcDb,
            satelliteStatusSupported = if (statusSupported) true else null,
            measurementsSupported = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) true else false,
            providerAvailable = true
        )
        updateWatch(sample)
    }

    fun acceptPhoneSnapshot(message: JSONObject, expectedSession: String): Boolean {
        if (!observationEnabled || session != expectedSession) return false
        val valid = LocationDiagnosticsProtocol.parse(
            message, LocationDiagnosticsProtocol.SNAPSHOT, expectedSession
        ) ?: return false
        val sample = try {
            val p = valid.getJSONObject("payload")
            LocationDiagnosticSample(
                source = p.optString("source").takeIf(String::isNotBlank),
                ageMillis = p.optLongOrNull("ageMs"),
                horizontalAccuracyMeters = p.optDoubleOrNull("accuracyM")?.toFloat(),
                speedMetersPerSecond = p.optDoubleOrNull("speedMps")?.toFloat(),
                speedAccuracyMetersPerSecond = p.optDoubleOrNull("speedAccuracyMps")?.toFloat(),
                bearingAccuracyDegrees = p.optDoubleOrNull("bearingAccuracyDeg")?.toFloat(),
                satelliteCount = p.optIntOrNull("satellites"),
                usedSatelliteCount = p.optIntOrNull("used"),
                meanCn0DbHz = p.optDoubleOrNull("meanCn0DbHz")?.toFloat(),
                maxAgcDb = p.optDoubleOrNull("maxAgcDb")?.toFloat(),
                satelliteStatusSupported = p.optBoolean("satelliteStatusSupported"),
                measurementsSupported = p.optBoolean("measurementsSupported"),
                providerAvailable = p.optBoolean("providerAvailable"),
                error = p.optString("error").takeIf(String::isNotBlank),
                observedAtEpochMillis = System.currentTimeMillis(),
                assessedQuality = p.optString("quality").takeIf(String::isNotBlank)
                    ?.let { runCatching { LocationDiagnosticQuality.valueOf(it) }.getOrNull() },
                assessmentReason = p.optString("reason").takeIf(String::isNotBlank)
            )
        } catch (error: Exception) {
            Log.w(tag, "Discarding malformed phone diagnostics snapshot", error)
            return false
        }
        _phone.value = sample
        val key = "${sample.quality()}|${sample.reason()}"
        val now = SystemClock.elapsedRealtime()
        if (key != lastPhoneLoggedKey || now - lastPhoneSummaryTime >= SUMMARY_INTERVAL_MS) {
            lastPhoneLoggedKey = key
            lastPhoneSummaryTime = now
            record("observation", "phone", sample)
        }
        return true
    }

    fun eventMarker(kind: String) {
        require(kind == "entry" || kind == "exit") { "Unsupported location diagnostic marker" }
        val now = System.currentTimeMillis()
        val event = LocationDiagnosticEvent(
            now, "watch", kind, "UNKNOWN", "User marked $kind event",
            null, null, null, null, null, null, null, null, null
        )
        append(event)
    }

    fun watchSnapshot(sessionId: String): JSONObject {
        require(observationEnabled && session == sessionId) { "Diagnostic session is not active" }
        val sample = _watch.value
        val payload = JSONObject()
            .put("source", sample.source?.take(32)?.filter { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' } ?: JSONObject.NULL)
            .put("ageMs", sample.ageMillis ?: JSONObject.NULL)
            .put("accuracyM", sample.horizontalAccuracyMeters ?: JSONObject.NULL)
            .put("speedMps", sample.speedMetersPerSecond ?: JSONObject.NULL)
            .put("speedAccuracyMps", sample.speedAccuracyMetersPerSecond ?: JSONObject.NULL)
            .put("bearingAccuracyDeg", sample.bearingAccuracyDegrees ?: JSONObject.NULL)
            .put("satellites", sample.satelliteCount ?: JSONObject.NULL)
            .put("used", sample.usedSatelliteCount ?: JSONObject.NULL)
            .put("meanCn0DbHz", sample.meanCn0DbHz ?: JSONObject.NULL)
            .put("maxAgcDb", sample.maxAgcDb ?: JSONObject.NULL)
            .put("satelliteStatusSupported", sample.satelliteStatusSupported ?: JSONObject.NULL)
            .put("measurementsSupported", sample.measurementsSupported ?: JSONObject.NULL)
            .put("providerAvailable", sample.providerAvailable ?: JSONObject.NULL)
            .put("quality", sample.quality().name)
            .put("reason", sample.reason())
        sample.error?.let { payload.put("error", it.take(80)) }
        return JSONObject().put("msgType", LocationDiagnosticsProtocol.SNAPSHOT)
            .put("version", LocationDiagnosticsProtocol.VERSION)
            .put("session", sessionId)
            .put("payload", payload)
    }

    fun exportJson(): String {
        val events = loadDailyEvents() + log.snapshot()
        return gson.toJson(mapOf(
            "schemaVersion" to 1,
            "privacy" to "No exact coordinates, device identifiers, or raw satellite identifiers are stored.",
            "events" to events.takeLast(BoundedLocationDiagnosticLog.MAX_EVENTS)
        ))
    }

    fun clear() {
        log.clear()
        logDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.matches(Regex("location-\\d{4}-\\d{2}-\\d{2}\\.jsonl"))) {
                if (!file.delete()) Log.w(tag, "Unable to clear diagnostics log ${file.name}")
            }
        }
        lastLoggedKey = null
        lastSummaryTime = 0L
    }

    private fun updateWatch(sample: LocationDiagnosticSample) {
        val candidate = sample.quality()
        val state = recoveryHysteresis.update(candidate)
        val adjusted = sample.copy(
            assessedQuality = state,
            assessmentReason = if (state == candidate) sample.assessmentReason else "Recovery pending; quality remains $state"
        )
        _watch.value = adjusted
        val key = "${state.name}|${sample.reason()}"
        val now = SystemClock.elapsedRealtime()
        if (key != lastLoggedKey || now - lastSummaryTime >= SUMMARY_INTERVAL_MS) {
            lastLoggedKey = key
            lastSummaryTime = now
            record("observation", "watch", adjusted, state)
        }
    }

    @SuppressLint("MissingPermission")
    private fun observeGnss() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            updateWatch(LocationDiagnosticSample(
                providerAvailable = false,
                error = "Fine location permission not granted",
                satelliteStatusSupported = null
            ))
            return
        }
        try {
            val callback = object : GnssStatus.Callback() {
                override fun onSatelliteStatusChanged(status: GnssStatus) {
                    if (!observationEnabled || gnssStatusCallback !== this) return
                    var used = 0
                    var cn0Total = 0f
                    var cn0Count = 0
                    for (index in 0 until status.satelliteCount) {
                        if (status.usedInFix(index)) used++
                        val cn0 = status.getCn0DbHz(index)
                        if (cn0.isFinite()) {
                            cn0Total += cn0
                            cn0Count++
                        }
                    }
                    satelliteCount = status.satelliteCount
                    usedSatelliteCount = used
                    meanCn0DbHz = if (cn0Count > 0) cn0Total / cn0Count else null
                }

                override fun onStarted() {
                    if (!observationEnabled || gnssStatusCallback !== this) return
                    updateWatch(_watch.value.copy(
                        providerAvailable = true, assessedQuality = null, assessmentReason = null
                    ))
                }

                override fun onStopped() {
                    if (!observationEnabled || gnssStatusCallback !== this) return
                    val previous = _watch.value
                    val unavailable = previous.error != null || previous.providerAvailable == false
                    satelliteCount = null
                    usedSatelliteCount = null
                    meanCn0DbHz = null
                    maxAgcDb = null
                    updateWatch(_watch.value.copy(
                        satelliteCount = null, usedSatelliteCount = null, meanCn0DbHz = null,
                        maxAgcDb = null, satelliteStatusSupported = null,
                        assessedQuality = if (unavailable) LocationDiagnosticQuality.UNAVAILABLE
                            else LocationDiagnosticQuality.UNKNOWN,
                        assessmentReason = when {
                            unavailable -> previous.reason()
                            previous.ageMillis == null -> "GNSS receiver inactive; no location fix observed"
                            else -> "GNSS receiver inactive; existing fix retained"
                        }
                    ))
                }
            }
            gnssStatusCallback = callback
            locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
        } catch (error: SecurityException) {
            updateWatch(LocationDiagnosticSample(providerAvailable = false, error = "GNSS permission denied"))
            Log.w(tag, "GNSS status observation denied", error)
        } catch (error: RuntimeException) {
            updateWatch(LocationDiagnosticSample(providerAvailable = false, error = "GNSS status callback unavailable"))
            Log.w(tag, "GNSS status callback unavailable", error)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val callback = object : GnssMeasurementsEvent.Callback() {
                    override fun onGnssMeasurementsReceived(event: GnssMeasurementsEvent) {
                        if (!observationEnabled || measurementsCallback !== this) return
                        val agcValues = event.measurements.mapNotNull { measurement ->
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                                measurement.hasAutomaticGainControlLevelDb()
                            ) measurement.automaticGainControlLevelDb.toFloat() else null
                        }
                        maxAgcDb = agcValues.maxOrNull()
                    }

                    override fun onStatusChanged(status: Int) {
                        if (!observationEnabled || measurementsCallback !== this) return
                        if (status == STATUS_NOT_SUPPORTED) {
                            _watch.value = _watch.value.copy(measurementsSupported = false)
                        }
                    }
                }
                measurementsCallback = callback
                locationManager.registerGnssMeasurementsCallback(callback, Handler(Looper.getMainLooper()))
            } catch (error: SecurityException) {
                _watch.value = _watch.value.copy(measurementsSupported = false)
                Log.w(tag, "Raw GNSS measurement observation denied", error)
            } catch (error: RuntimeException) {
                _watch.value = _watch.value.copy(measurementsSupported = false)
                Log.w(tag, "Raw GNSS measurement callback unavailable", error)
            }
        } else _watch.value = _watch.value.copy(measurementsSupported = false)
    }

    private fun record(kind: String, source: String, sample: LocationDiagnosticSample,
                      quality: LocationDiagnosticQuality = sample.quality()) {
        append(LocationDiagnosticEvent(
            observedAtEpochMillis = sample.observedAtEpochMillis,
            source = source,
            kind = kind,
            quality = quality.name,
            reason = sample.reason(),
            ageMillis = sample.ageMillis,
            horizontalAccuracyMeters = sample.horizontalAccuracyMeters,
            speedMetersPerSecond = sample.speedMetersPerSecond,
            speedAccuracyMetersPerSecond = sample.speedAccuracyMetersPerSecond,
            bearingAccuracyDegrees = sample.bearingAccuracyDegrees,
            satelliteCount = sample.satelliteCount,
            usedSatelliteCount = sample.usedSatelliteCount,
            meanCn0DbHz = sample.meanCn0DbHz,
            maxAgcDb = sample.maxAgcDb
        ))
    }

    private fun append(event: LocationDiagnosticEvent) {
        log.append(event)
        try {
            if (!logDirectory.exists() && !logDirectory.mkdirs()) {
                Log.e(tag, "Unable to create diagnostics directory")
                return
            }
            val date = Instant.ofEpochMilli(event.observedAtEpochMillis)
                .atZone(ZoneOffset.UTC).toLocalDate()
            val target = File(logDirectory, "location-$date.jsonl")
            val line = gson.toJson(event) + "\n"
            if (target.length() + line.toByteArray(Charsets.UTF_8).size > MAX_DAILY_LOG_BYTES) {
                Log.w(tag, "Daily diagnostics log is full; dropping additional summary")
                return
            }
            target.appendText(line, Charsets.UTF_8)
            val cutoff = LocalDate.now(ZoneOffset.UTC).minusDays(7).toString()
            logDirectory.listFiles()?.filter {
                it.isFile && it.name.matches(Regex("location-\\d{4}-\\d{2}-\\d{2}\\.jsonl")) &&
                    it.name.substringAfter("location-").substringBefore(".") < cutoff
            }?.forEach { it.delete() }
        } catch (error: Exception) {
            Log.e(tag, "Unable to append diagnostics event", error)
        }
    }

    private fun loadDailyEvents(): List<LocationDiagnosticEvent> =
        try {
            logDirectory.listFiles()?.filter { it.isFile && it.name.startsWith("location-") }
                ?.sortedBy { it.name }?.flatMap { file ->
                    file.readLines(Charsets.UTF_8).mapNotNull { line ->
                        runCatching { gson.fromJson(line, LocationDiagnosticEvent::class.java) }.getOrNull()
                    }
                }.orEmpty()
        } catch (error: Exception) {
            Log.e(tag, "Unable to read diagnostics export", error)
            emptyList()
        }

    private fun JSONObject.optLongOrNull(key: String): Long? =
        if (!has(key) || isNull(key)) null else optLong(key).takeIf { it >= 0 }

    private fun JSONObject.optIntOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key).takeIf { it >= 0 }

    private fun JSONObject.optDoubleOrNull(key: String): Double? =
        if (!has(key) || isNull(key)) null else optDouble(key).takeIf { it.isFinite() && it >= 0.0 }

    companion object {
        private fun inactiveSample() = LocationDiagnosticSample(
            assessmentReason = "Diagnostics inactive; no active bonded Companion session"
        )
        private const val SUMMARY_INTERVAL_MS = 5 * 60 * 1000L
        private const val MAX_DAILY_LOG_BYTES = 256 * 1024L
    }
}

class QualityRecoveryHysteresis(private val recoverySamples: Int = 3) {
    var current: LocationDiagnosticQuality = LocationDiagnosticQuality.UNKNOWN
        private set
    private var candidate: LocationDiagnosticQuality? = null
    private var candidateCount = 0

    fun update(next: LocationDiagnosticQuality): LocationDiagnosticQuality {
        if (next == LocationDiagnosticQuality.UNAVAILABLE || next == LocationDiagnosticQuality.UNKNOWN) {
            current = next
            candidate = null
            candidateCount = 0
            return current
        }
        if (current == LocationDiagnosticQuality.UNKNOWN || current == LocationDiagnosticQuality.UNAVAILABLE ||
            next == LocationDiagnosticQuality.DEGRADED
        ) {
            current = next
            candidate = null
            candidateCount = 0
            return current
        }
        if (next == current) {
            candidate = null
            candidateCount = 0
            return current
        }
        if (candidate == next) candidateCount++ else {
            candidate = next
            candidateCount = 1
        }
        if (candidateCount >= recoverySamples) {
            current = next
            candidate = null
            candidateCount = 0
        }
        return current
    }
}
