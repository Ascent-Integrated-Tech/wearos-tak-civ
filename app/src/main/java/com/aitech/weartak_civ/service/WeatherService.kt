/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.service

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.location.Location
import android.os.IBinder
import android.util.Log
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.presentation.MainActivity
import com.aitech.weartak_civ.cep.bus.CepBus
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.WaterPayload
import com.aitech.weartak_civ.cep.model.payloads.WindPayload
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/**
 * WeatherService periodically fetches weather from OpenWeather One Call 3.0,
 * gated by minimum time and minimum distance moved. This implementation only logs
 * parsed values and updates a latest snapshot field; it does not emit CEP events.
 */
class WeatherService : Service() {
	private val TAG = "WearTAK-" + WeatherService::class.java.simpleName

	private val serviceJob = SupervisorJob()
	private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
	private val httpClient by lazy {
		OkHttpClient.Builder()
			.callTimeout(20, TimeUnit.SECONDS)
			.connectTimeout(10, TimeUnit.SECONDS)
			.readTimeout(20, TimeUnit.SECONDS)
			.build()
	}

	// Configuration defaults (can later be wired to DataRepository/Knox)
	private var configuredPollIntervalMinutes: Long = 3L   // test default: 3 minutes
	private var hardMinimumIntervalMinutes: Long = 3L      // allow 3-minute minimum during testing
	private var minDistanceMeters: Double = 1500.0         // default
	private var popWetThreshold: Double = 0.5              // default

	// Backoff state (in ms)
	private var currentBackoffMillis: Long = TimeUnit.MINUTES.toMillis(configuredPollIntervalMinutes)
	private val maxBackoffMillis: Long = TimeUnit.MINUTES.toMillis(60)

	// Gating state
	private var lastSuccessfulFetchTimeMillis: Long? = null
	private var lastSuccessfulFetchLat: Double? = null
	private var lastSuccessfulFetchLon: Double? = null
	private var lastAttemptFetchTimeMillis: Long? = null

	// Access to shared repository
	private val dataRepository get() = MainActivity.dataRepository

	override fun onBind(intent: Intent?): IBinder? = null

	override fun onCreate() {
		super.onCreate()
		Log.i(TAG, "WeatherService onCreate()")
		isRunning.set(true)

		// Foreground for stability (low-importance like others)
		startForegroundServiceCompat(
			notificationId = WEATHER_SERVICE_NOTIFICATION_ID,
			importance = NotificationManager.IMPORTANCE_LOW
		)

		// Load any persisted/managed config if/when available (kept local for now)
		loadInitialConfig()
		Log.d(TAG, "WeatherService started; pollIntervalMin=$configuredPollIntervalMinutes, minDistanceMeters=$minDistanceMeters")

		// Fail-fast if API key missing
		val apiKey = OpenWeatherConfig.getApiKey()
		if (apiKey.isNullOrBlank()) {
			Log.e(TAG, "OpenWeather API key missing. WeatherService will stop.")
			stopSelfSafely()
			return
		}

		// Start background loop
		serviceScope.launch {
			runLoop(apiKey)
		}
	}

	override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
		Log.i(TAG, "WeatherService onStartCommand()")
		return START_NOT_STICKY
	}

	override fun onDestroy() {
		super.onDestroy()
		Log.i(TAG, "WeatherService onDestroy()")
		isRunning.set(false)
		serviceScope.cancel()
		stopForeground(STOP_FOREGROUND_REMOVE)
	}

	private fun stopSelfSafely() {
		try {
			stopForeground(STOP_FOREGROUND_REMOVE)
		} catch (_: Throwable) {
		}
		stopSelf()
	}

	private fun loadInitialConfig() {
		// In the future, fetch these from DataRepository/Knox/MDM.
		// Enforce hard minimum interval.
		configuredPollIntervalMinutes = max(configuredPollIntervalMinutes, hardMinimumIntervalMinutes)
		currentBackoffMillis = TimeUnit.MINUTES.toMillis(configuredPollIntervalMinutes)
	}

	private suspend fun runLoop(apiKey: String) {
		// On first loop iteration, allow an immediate attempt if gates pass.
		while (isRunning.get()) {
			val now = System.currentTimeMillis()
			val latestLoc: android.location.Location? = dataRepository.latestLocation.value

			if (latestLoc == null) {
				Log.w(TAG, "No latest location; skipping weather fetch.")
				delay(currentBackoffMillis)
				continue
			}

			val effectiveIntervalMillis = TimeUnit.MINUTES.toMillis(max(configuredPollIntervalMinutes, hardMinimumIntervalMinutes))
			val timeGatePass = timeGateAllows(now, effectiveIntervalMillis)
			val distanceGatePass = distanceGateAllows(latestLoc)

			if (!timeGatePass) {
				val nextDelay = computeNextTimeDelay(now, effectiveIntervalMillis)
				Log.i(TAG, "Skipping fetch (interval gate). Next check in ${nextDelay / 1000}s")
				delay(nextDelay)
				continue
			}

			if (!distanceGatePass) {
				// Distance unchanged enough; check again in a short interval to avoid battery drain.
				val shortDelay = min(effectiveIntervalMillis, TimeUnit.MINUTES.toMillis(1))
				Log.i(TAG, "Skipping fetch (distance < ${minDistanceMeters}m). Recheck in ${shortDelay / 1000}s")
				delay(shortDelay)
				continue
			}

			// Passed gates → attempt fetch
			lastAttemptFetchTimeMillis = now
			val startNs = System.nanoTime()
			val result = try {
				Log.d(TAG, "Making OpenWeather request for lat=${latestLoc.latitude}, lon=${latestLoc.longitude}")
				val url = buildOneCallUrl(
					lat = latestLoc.latitude,
					lon = latestLoc.longitude,
					units = "metric",
					exclude = "minutely,daily,alerts",
					apiKey = apiKey
				)
				Log.d(TAG, "Request URL: $url")
				val request = Request.Builder().url(url).get().build()
				httpClient.newCall(request).execute()
			} catch (t: Throwable) {
				Log.d(TAG, "Request error: ${t.message}")
				Log.e(TAG, "HTTP request failed: ${t.message}", t)
				null
			}
			val latencyMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

			if (result == null) {
				handleFailureBackoff("Network error", latencyMs)
				continue
			}

			result.use { resp ->
				Log.d(TAG, "Received HTTP response code=${resp.code} in ${latencyMs}ms")
				if (!resp.isSuccessful) {
					Log.d(TAG, "Non-success response: code=${resp.code}")
					handleFailureBackoff("HTTP ${resp.code}", latencyMs)
					return@use
				}
				val bodyStr = resp.body?.string()
				if (bodyStr.isNullOrBlank()) {
					Log.d(TAG, "Empty body in response")
					handleFailureBackoff("Empty body", latencyMs)
					return@use
				}

				try {
					val snapshot = WeatherExtractor.parseOneCall(bodyStr)
					// Update snapshot with actual requested lat/lon if API responds w/o them
					val completed = snapshot.copy(
						lat = latestLoc.latitude,
						lon = latestLoc.longitude,
						tsUtcMillis = System.currentTimeMillis()
					)
					latest = completed

					// Update success state
					lastSuccessfulFetchTimeMillis = now
					lastSuccessfulFetchLat = latestLoc.latitude
					lastSuccessfulFetchLon = latestLoc.longitude
					currentBackoffMillis = TimeUnit.MINUTES.toMillis(configuredPollIntervalMinutes) // reset

					// Structured success summary log
					Log.i(TAG, "Weather fetch OK, latency=${latencyMs}ms " +
							"wind=${completed.windSpeedMps ?: -1.0}mps " +
							"humidity=${completed.humidityPct ?: -1}% " +
							"wet=${completed.wet} " +
							"rain1h=${completed.rain1hMm ?: 0.0}mm " +
							"snow1h=${completed.snow1hMm ?: 0.0}mm " +
							"pop=${completed.pop ?: -1.0}")

					// Emit into CEP: wind and wet topics
					try {
						// WIND
						val windSpeed = completed.windSpeedMps?.toFloat()
						val windDir = completed.windDeg?.toFloat()
						if (windSpeed != null || windDir != null) {
							CepBus.emit(
								CepEvent(
									topic = Topics.WIND,
									payload = WindPayload(speedMps = windSpeed, directionDeg = windDir)
								)
							)
						}
						// WET (precip/wetness)
						CepBus.emit(
							CepEvent(
								topic = Topics.WATER,
								payload = WaterPayload(wet = completed.wet, temperatureC = null)
							)
						)
					} catch (t: Throwable) {
						Log.e(TAG, "Failed CEP emit (wind/wet)", t)
					}
				} catch (je: JSONException) {
					Log.d(TAG, "JSON parse error: ${je.message}")
					Log.e(TAG, "JSON parse error: ${je.message}", je)
					handleFailureBackoff("Parse error", latencyMs)
					return@use
				} catch (t: Throwable) {
					Log.d(TAG, "Unexpected parse error: ${t.message}")
					Log.e(TAG, "Unexpected parse error: ${t.message}", t)
					handleFailureBackoff("Parse error", latencyMs)
					return@use
				}
			}

			// After success, wait until next cycle based on interval (not backoff)
			val intervalMs = TimeUnit.MINUTES.toMillis(configuredPollIntervalMinutes)
			delay(intervalMs)
		}
	}

	private fun timeGateAllows(nowMs: Long, effectiveIntervalMs: Long): Boolean {
		val last = lastSuccessfulFetchTimeMillis ?: return true
		val since = nowMs - last
		return since >= effectiveIntervalMs
	}

	private fun computeNextTimeDelay(nowMs: Long, effectiveIntervalMs: Long): Long {
		val last = lastSuccessfulFetchTimeMillis ?: return 0L
		val since = nowMs - last
		return if (since >= effectiveIntervalMs) 0L else (effectiveIntervalMs - since)
	}

	private fun distanceGateAllows(latestLoc: Location): Boolean {
		val prevLat = lastSuccessfulFetchLat
		val prevLon = lastSuccessfulFetchLon
		if (prevLat == null || prevLon == null) {
			return true // No prior successful location; allow
		}
		val dist = haversineDistanceMeters(prevLat, prevLon, latestLoc.latitude, latestLoc.longitude)
		return dist >= minDistanceMeters
	}

	private fun handleFailureBackoff(reason: String, latencyMs: Long) {
		Log.w(TAG, "Weather fetch failed ($reason), latency=${latencyMs}ms; " +
				"backing off from ${currentBackoffMillis / 1000}s")
		currentBackoffMillis = min(currentBackoffMillis * 2, maxBackoffMillis)
	}

	private fun buildOneCallUrl(
		lat: Double,
		lon: Double,
		units: String,
		exclude: String,
		apiKey: String
	): HttpUrl {
		return HttpUrl.Builder()
			.scheme("https")
			.host("api.openweathermap.org")
			.addPathSegment("data")
			.addPathSegment("3.0")
			.addPathSegment("onecall")
			.addQueryParameter("lat", lat.toString())
			.addQueryParameter("lon", lon.toString())
			.addQueryParameter("units", units)
			.addQueryParameter("exclude", exclude)
			.addQueryParameter("appid", apiKey)
			.build()
	}

	private fun haversineDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
		val R = 6371000.0 // meters
		val dLat = Math.toRadians(lat2 - lat1)
		val dLon = Math.toRadians(lon2 - lon1)
		val a = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
		val c = 2 * atan2(sqrt(a), sqrt(1 - a))
		return R * c
	}

	companion object {
		private const val WEATHER_SERVICE_NOTIFICATION_ID = 1137
		val isRunning: AtomicBoolean = AtomicBoolean(false)

		data class WeatherSnapshot(
			val tsUtcMillis: Long,
			val lat: Double,
			val lon: Double,
			val windSpeedMps: Double?,
			val windDeg: Int?,
			val windGustMps: Double?,
			val humidityPct: Int?,
			val tempC: Double?,
			val feelsLikeC: Double?,
			val pressureHpa: Int?,
			val pop: Double?,
			val rain1hMm: Double?,
			val snow1hMm: Double?,
			val wet: Boolean,
			val precipType: String?, // "rain" | "snow" | "mixed" | null
			val conditionMain: String?,
			val conditionDesc: String?,
			val source: String = "openweather.onecall3"
		)

		@Volatile var latest: WeatherSnapshot? = null
		@JvmStatic fun getLatestSnapshot(): WeatherSnapshot? = latest
	}

	/**
	 * Helper to retrieve API keys and related config without hardcoding.
	 */
	internal object OpenWeatherConfig {
		fun getApiKey(): String? {
			return try {
				// Prefer BuildConfig field if present (reflection avoids compile error when absent)
				val buildConfig = com.aitech.weartak_civ.BuildConfig::class.java
				val field = buildConfig.getField("OPENWEATHER_API_KEY")
				(field.get(null) as? String)?.takeIf { it.isNotBlank() }
			} catch (_: Throwable) {
				null
			}
		}
	}

	/**
	 * Extractor for unit-testable JSON parsing.
	 */
	internal object WeatherExtractor {
		fun parseOneCall(json: String): Companion.WeatherSnapshot {
			val root = JSONObject(json)
			val current = root.getJSONObject("current")

			val windSpeed = current.optDoubleOrNull("wind_speed")
			val windDeg = current.optIntOrNull("wind_deg")
			// current.wind_gust is optional; hourly[0].wind_gust may also exist
			val currentWindGust = current.optDoubleOrNull("wind_gust")

			val humidity = current.optIntOrNull("humidity")
			val temp = current.optDoubleOrNull("temp")
			val feelsLike = current.optDoubleOrNull("feels_like")
			val pressure = current.optIntOrNull("pressure")
			val clouds = current.optIntOrNull("clouds") // not included in snapshot but available if needed
			val visibility = current.optIntOrNull("visibility") // not included in snapshot but available if needed

			// Precip now
			val rain1h = current.optNestedDoubleOrNull("rain", "1h")
			val snow1h = current.optNestedDoubleOrNull("snow", "1h")

			// Weather condition
			var conditionMain: String? = null
			var conditionDesc: String? = null
			var conditionId: Int? = null
			current.optJSONArray("weather")?.let { arr ->
				if (arr.length() > 0) {
					val w0 = arr.optJSONObject(0)
					if (w0 != null) {
						conditionMain = w0.optStringOrNull("main")
						conditionDesc = w0.optStringOrNull("description")
						conditionId = w0.optIntOrNull("id")
					}
				}
			}

			// Hourly[0]
			val hourlyArr: JSONArray? = root.optJSONArray("hourly")
			var pop: Double? = null
			var hourlyGust: Double? = null
			if (hourlyArr != null && hourlyArr.length() > 0) {
				val h0 = hourlyArr.optJSONObject(0)
				if (h0 != null) {
					pop = h0.optDoubleOrNull("pop")
					hourlyGust = h0.optDoubleOrNull("wind_gust")
				}
			}

			val windGust = currentWindGust ?: hourlyGust

			// Determine wet state and precip type
			val wet = ((rain1h != null && rain1h > 0.0) || (snow1h != null && snow1h > 0.0) || (pop != null && pop >= 0.5))
			val precipType: String? = when {
				(rain1h != null && rain1h > 0.0) && (snow1h != null && snow1h > 0.0) -> "mixed"
				(rain1h != null && rain1h > 0.0) -> "rain"
				(snow1h != null && snow1h > 0.0) -> "snow"
				else -> null
			}

			return Companion.WeatherSnapshot(
				tsUtcMillis = System.currentTimeMillis(),
				lat = Double.NaN,   // caller should override with actual request coords
				lon = Double.NaN,   // caller should override with actual request coords
				windSpeedMps = windSpeed,
				windDeg = windDeg,
				windGustMps = windGust,
				humidityPct = humidity,
				tempC = temp,
				feelsLikeC = feelsLike,
				pressureHpa = pressure,
				pop = pop,
				rain1hMm = rain1h,
				snow1hMm = snow1h,
				wet = wet,
				precipType = precipType,
				conditionMain = conditionMain,
				conditionDesc = conditionDesc,
				source = "openweather.onecall3"
			)
		}

		private fun JSONObject.optStringOrNull(name: String): String? =
			if (has(name) && !isNull(name)) optString(name) else null

		private fun JSONObject.optIntOrNull(name: String): Int? =
			if (has(name) && !isNull(name)) optInt(name) else null

		private fun JSONObject.optDoubleOrNull(name: String): Double? =
			if (has(name) && !isNull(name)) optDouble(name) else null

		private fun JSONObject.optNestedDoubleOrNull(objName: String, field: String): Double? {
			if (!has(objName) || isNull(objName)) return null
			val nested = optJSONObject(objName) ?: return null
			if (!nested.has(field) || nested.isNull(field)) return null
			return try { nested.getDouble(field) } catch (_: Throwable) { null }
		}
	}
}



