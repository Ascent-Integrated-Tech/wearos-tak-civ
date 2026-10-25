package com.aitech.weartak_civ.service

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.annotation.Keep
import com.aitech.weartak_civ.BuildConfig
import com.aitech.weartak_civ.data.DataRepository
import com.google.gson.Gson
import com.google.gson.JsonParseException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

@Keep
enum class DiagnosticEvent {
    ENABLED, DISABLED, PROCESS_START, JAVA_CRASH, ACTIVITY_RESUMED, ACTIVITY_STOPPED,
    BATTERY, LOCATION_REQUEST, LOCATION_ACCEPTED, LOCATION_REJECTED, LOCATION_TIMEOUT,
    LOCATION_FAILURE, LOCATION_COALESCED, LOCATION_STOPPED, OBSERVATION_SCHEDULED, REPORT_POLICY,
    CONFIGURATION, SPEED_VALIDITY,
    PLI_ATTEMPT, PLI_RESULT, POWER_CHANGED, WAKE_LOCK, CONNECTIVITY, SYNC, COMMAND,
    CHAT, SENSOR, SERVICE_REQUEST, APP_EVENT, SUBSCRIBER_FAILURE, QUEUE_OVERFLOW
}

@Keep
enum class DiagnosticMetric {
    SOURCE, KIND, DURATION_MS, INTERVAL_SECONDS, REPORT_SECONDS, OBSERVATION_SECONDS,
    BATTERY_PERCENT, DOCK, CHARGING, RESULT, SCREEN, COUNT, AGGREGATED, ACTIVE,
    POWER_THROTTLED, DOCK_THROTTLED, FOOT_SECONDS, VEHICLE_SECONDS, STATIONARY_SECONDS,
    ALERT_SECONDS, CONSTANT_SECONDS, DYNAMIC, LOCATION_ENABLED, PHYSIO_ENABLED, HR_ENABLED
}

@Keep
internal data class DiagnosticRecord(
    val schema: Int = 1,
    val version: String = BuildConfig.VERSION_NAME,
    val versionCode: Int = BuildConfig.VERSION_CODE,
    val sourceCommit: String = BuildConfig.GIT_COMMIT_SHORT,
    val policy: String = "dynamic-speed-observation-v1",
    val wallTimeMs: Long,
    val elapsedMs: Long,
    val event: DiagnosticEvent,
    val metrics: Map<DiagnosticMetric, Long>
)

internal class DiagnosticJournal(
    private val directory: () -> File,
    private val maxBytes: Long = 256 * 1024L,
    private val retentionMs: Long = 3 * 24 * 60 * 60 * 1000L,
    private val wallClock: () -> Long = System::currentTimeMillis
) {
    private val gson = Gson()
    private val firstTimestamp = mutableMapOf<String, Long>()

    private fun parseRecord(line: String): DiagnosticRecord {
        try {
            val record = gson.fromJson(line, DiagnosticRecord::class.java)
                ?: throw IOException("Diagnostics file contains an empty record")
            if (record.schema != 1 || record.wallTimeMs <= 0 || record.elapsedMs < 0)
                throw IOException("Diagnostics file contains invalid metadata")
            return record
        } catch (error: JsonParseException) {
            throw IOException("Diagnostics file is incomplete; retrieve original JSONL files", error)
        }
    }

    private fun firstWallTime(file: File): Long = firstTimestamp.getOrPut(file.name) {
        if (file.length() > maxBytes) throw IOException("Diagnostics file exceeds storage limit")
        file.bufferedReader().use { reader ->
            reader.readLine()?.let { parseRecord(it).wallTimeMs } ?: wallClock()
        }
    }

    private fun files(prune: Boolean = true): List<File> {
        val root = directory()
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Diagnostics storage unavailable")
        return (2 downTo 0).map { File(root, if (it == 0) "events.jsonl" else "events.$it.jsonl") }
            .also { files ->
                files.filter { prune && it.exists() && wallClock() - firstWallTime(it) > retentionMs }.forEach {
                    if (!it.delete()) throw IOException("Cannot expire diagnostics")
                    firstTimestamp.remove(it.name)
                }
            }
    }

    @Synchronized
    fun append(record: DiagnosticRecord) {
        val line = gson.toJson(record) + "\n"
        val bytes = line.toByteArray(Charsets.UTF_8)
        if (bytes.size > maxBytes) throw IOException("Diagnostics event exceeds storage limit")
        val files = files()
        if (files.last().length() + bytes.size > maxBytes) {
            if (files.first().exists() && !files.first().delete()) throw IOException("Cannot rotate diagnostics")
            for (index in 1 until files.size) {
                val source = files[index]
                if (source.exists() && !source.renameTo(files[index - 1])) throw IOException("Cannot rotate diagnostics")
            }
            firstTimestamp.clear()
        }
        if (!files.last().exists()) firstTimestamp[files.last().name] = record.wallTimeMs
        files.last().appendBytes(bytes)
    }

    @Synchronized
    fun exportJson(): String {
        val records = files().filter { it.exists() }.flatMap { file ->
            if (file.length() > maxBytes) throw IOException("Diagnostics file exceeds storage limit")
            file.useLines { lines -> lines.map(::parseRecord).toList() }
        }
        return gson.toJson(mapOf("schema" to 1, "events" to records))
    }

    @Synchronized
    fun clear() {
        files(prune = false).filter { it.exists() }.forEach {
            if (!it.delete()) throw IOException("Cannot clear diagnostics")
        }
        firstTimestamp.clear()
    }
}

/**
 * Only compiled event names and numeric, allowlisted metrics enter the journal. Never pass
 * payloads, exception messages, locations, peer identifiers or network addresses to this API.
 */
class AppDiagnostics internal constructor(private val application: Application) :
    Application.ActivityLifecycleCallbacks {
    private val preferences = application.getSharedPreferences("app_diagnostics", Context.MODE_PRIVATE)
    private val mutableEnabled = MutableStateFlow(preferences.getBoolean("enabled", false))
    val enabled = mutableEnabled.asStateFlow()
    private val mutableFailure = MutableStateFlow(false)
    val storageFailed = mutableFailure.asStateFlow()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private sealed interface Pending {
        data class Write(val generation: Long, val record: DiagnosticRecord) : Pending
        data class Barrier(val completion: CompletableDeferred<Unit>) : Pending
    }
    private val queue = Channel<Pending>(128)
    private val generation = AtomicLong()
    private val dropped = AtomicLong()
    private val storageLock = Any()
    private val rateLock = Any()
    private data class Rate(val at: Long, val metrics: Map<DiagnosticMetric, Long>, var skipped: Long = 0)
    private data class RateKey(
        val event: DiagnosticEvent, val kind: Long?, val source: Long?,
        val active: Long?, val result: Long?, val dock: Long?, val charging: Long?
    )
    private val rates = mutableMapOf<RateKey, Rate>()
    private val journal = DiagnosticJournal({
        application.getExternalFilesDir("diagnostics") ?: throw IOException("Diagnostics storage unavailable")
    })
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var crashHandler: Thread.UncaughtExceptionHandler? = null

    init {
        scope.launch {
            for (pending in queue) {
                if (pending is Pending.Barrier) {
                    pending.completion.complete(Unit)
                    continue
                }
                val write = pending as Pending.Write
                synchronized(storageLock) {
                    if (enabled.value && write.generation == generation.get()) {
                        if (storageFailed.value) {
                            dropped.incrementAndGet()
                            return@synchronized
                        }
                        storage {
                            val lost = dropped.getAndSet(0)
                            if (lost > 0) journal.append(record(DiagnosticEvent.QUEUE_OVERFLOW,
                                mapOf(DiagnosticMetric.COUNT to lost)))
                            journal.append(write.record)
                        }
                    }
                }
            }
        }
        if (enabled.value) {
            attach()
            enqueue(DiagnosticEvent.PROCESS_START)
        }
    }

    private fun record(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long> = emptyMap()) =
        DiagnosticRecord(wallTimeMs = System.currentTimeMillis(), elapsedMs = SystemClock.elapsedRealtime(),
            event = event, metrics = metrics.toMap())

    private fun storage(action: () -> Unit) {
        try {
            action()
            mutableFailure.value = false
        } catch (error: IOException) {
            storageFailure(error)
        } catch (error: SecurityException) {
            storageFailure(error)
        }
    }

    private fun storageFailure(error: Exception) {
        if (!mutableFailure.value) Log.e("WearTAK-Diagnostics", "Diagnostics storage failed", error)
        mutableFailure.value = true
    }

    private fun attach() {
        application.registerActivityLifecycleCallbacks(this)
        EventBus.getDefault().register(this)
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        val inherited = previousCrashHandler ?: return
        lateinit var handler: Thread.UncaughtExceptionHandler
        handler = Thread.UncaughtExceptionHandler { thread, error ->
            // Each installation captures its predecessor; later third-party wrappers cannot form a cycle.
            try {
                synchronized(storageLock) {
                    if (enabled.value && crashHandler === handler)
                        storage { journal.append(record(DiagnosticEvent.JAVA_CRASH)) }
                }
            } finally {
                inherited.uncaughtException(thread, error)
            }
        }
        crashHandler = handler
        Thread.setDefaultUncaughtExceptionHandler(handler)
    }

    private fun detach() {
        application.unregisterActivityLifecycleCallbacks(this)
        EventBus.getDefault().unregister(this)
        if (Thread.getDefaultUncaughtExceptionHandler() === crashHandler)
            Thread.setDefaultUncaughtExceptionHandler(previousCrashHandler)
        crashHandler = null
    }

    internal fun bindRepository(repo: DataRepository) {
        scope.launch {
            enabled.collectLatest { recording ->
                if (!recording) return@collectLatest
                val fields = listOf(
                    DiagnosticMetric.FOOT_SECONDS to repo.getOnFootReportingInterval.map { it.toLong() },
                    DiagnosticMetric.VEHICLE_SECONDS to repo.getVehicleReportingInterval.map { it.toLong() },
                    DiagnosticMetric.STATIONARY_SECONDS to repo.getStationaryReportingInterval.map { it.toLong() },
                    DiagnosticMetric.ALERT_SECONDS to repo.getWhileAlertingReportingInterval.map { it.toLong() },
                    DiagnosticMetric.CONSTANT_SECONDS to repo.getConstantReportingInterval.map { it.toLong() },
                    DiagnosticMetric.DYNAMIC to repo.getEnableDynamicReportingStrategy.map { if (it) 1L else 0L },
                    DiagnosticMetric.SOURCE to repo.getLocationInput.map { it?.ordinal?.toLong() ?: -1L },
                    DiagnosticMetric.LOCATION_ENABLED to repo.getEnableLocationService.map { if (it) 1L else 0L },
                    DiagnosticMetric.PHYSIO_ENABLED to repo.getEnablePhysiologicalServices.map { if (it) 1L else 0L },
                    DiagnosticMetric.HR_ENABLED to repo.getEnableHeartRateService.map { if (it) 1L else 0L }
                )
                combine(fields.map { it.second }) { values ->
                    fields.mapIndexed { index, field -> field.first to values[index] }.toMap()
                }.distinctUntilChanged().catch { error ->
                    when (error) {
                        is IOException -> storageFailure(error)
                        is SecurityException -> storageFailure(error)
                        else -> throw error
                    }
                }.collect { configuration ->
                    capture(DiagnosticEvent.CONFIGURATION, configuration)
                    val battery = mapOf(
                        DiagnosticMetric.DOCK to repo.dockType.value.ordinal.toLong(),
                        DiagnosticMetric.CHARGING to if (repo.chargingState.value) 1L else 0L
                    ) + repo.latestBatteryPercentage.value?.let {
                        mapOf(DiagnosticMetric.BATTERY_PERCENT to it.toLong())
                    }.orEmpty()
                    capture(DiagnosticEvent.BATTERY, battery)
                }
            }
        }
    }

    // Call storage/settings operations on IO, like the existing diagnostic export path.
    suspend fun setEnabled(value: Boolean) {
        if (!value) awaitPendingWrites()
        synchronized(storageLock) {
            if (enabled.value == value) return
            if (!preferences.edit().putBoolean("enabled", value).commit())
                throw IOException("Cannot save Diagnostics preference")
            if (!value) {
                storage {
                    flushAggregates()
                    journal.append(record(DiagnosticEvent.DISABLED))
                }
            }
            generation.incrementAndGet()
            mutableEnabled.value = value
            synchronized(rateLock) { rates.clear() }
            if (value) {
                attach()
                enqueue(DiagnosticEvent.ENABLED)
            } else detach()
        }
    }

    private fun enqueue(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long> = emptyMap()) {
        if (queue.trySend(Pending.Write(generation.get(), record(event, metrics))).isFailure) dropped.incrementAndGet()
    }

    private fun capture(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long>) {
        if (!enabled.value) return
        synchronized(rateLock) {
            if (!enabled.value) return
            val now = SystemClock.elapsedRealtime()
            val key = RateKey(event, metrics[DiagnosticMetric.KIND], metrics[DiagnosticMetric.SOURCE],
                metrics[DiagnosticMetric.ACTIVE], metrics[DiagnosticMetric.RESULT],
                metrics[DiagnosticMetric.DOCK], metrics[DiagnosticMetric.CHARGING])
            val prior = rates[key]
            if (prior != null && now - prior.at < 5_000L) {
                rates[key] = prior.copy(metrics = metrics.toMap(), skipped = prior.skipped + 1)
                return
            }
            if (prior == null && rates.size >= 256) {
                dropped.incrementAndGet()
                return
            }
            rates[key] = Rate(now, metrics.toMap())
            enqueue(event, metrics + (DiagnosticMetric.COUNT to (1 + (prior?.skipped ?: 0))))
        }
    }

    private fun flushAggregates() {
        val lost = dropped.getAndSet(0)
        if (lost > 0) journal.append(record(DiagnosticEvent.QUEUE_OVERFLOW,
            mapOf(DiagnosticMetric.COUNT to lost)))
        val pending = synchronized(rateLock) {
            val pending = rates.filterValues { it.skipped > 0 }.map { (key, rate) ->
                record(key.event, rate.metrics +
                    mapOf(DiagnosticMetric.COUNT to rate.skipped, DiagnosticMetric.AGGREGATED to 1L))
            }
            rates.clear()
            pending
        }
        pending.forEach(journal::append)
    }

    private suspend fun awaitPendingWrites() {
        val barrier = CompletableDeferred<Unit>()
        queue.send(Pending.Barrier(barrier))
        barrier.await()
    }

    suspend fun exportJson(): String {
        awaitPendingWrites()
        return synchronized(storageLock) {
            try {
                flushAggregates()
                journal.exportJson().also { mutableFailure.value = false }
            } catch (error: IOException) {
                storageFailure(error)
                throw error
            } catch (error: SecurityException) {
                storageFailure(error)
                throw error
            }
        }
    }

    fun clear() = synchronized(storageLock) {
        synchronized(rateLock) {
            generation.incrementAndGet()
            dropped.set(0)
            rates.clear()
        }
        try {
            journal.clear()
            mutableFailure.value = false
        } catch (error: IOException) {
            storageFailure(error)
            throw error
        } catch (error: SecurityException) {
            storageFailure(error)
            throw error
        }
    }

    internal fun close() = synchronized(storageLock) {
        if (enabled.value) detach()
        scope.cancel()
        queue.close()
    }

    @Subscribe(threadMode = ThreadMode.POSTING)
    fun onAppEvent(event: Any) {
        val category = when (event.javaClass.name) {
            "com.aitech.weartak_civ.events.NwConnEvent",
            "com.aitech.weartak_civ.events.TakServerConnStatusEvent" -> DiagnosticEvent.CONNECTIVITY
            "com.aitech.weartak_civ.events.PowerManagerEvent" -> DiagnosticEvent.POWER_CHANGED
            "com.aitech.weartak_civ.events.DittoDocumentEvent",
            "com.aitech.weartak_civ.events.SitxServiceEventBus" -> DiagnosticEvent.SYNC
            "com.aitech.weartak_civ.events.SitxServiceCommandBus" -> DiagnosticEvent.COMMAND
            "com.aitech.weartak_civ.events.TakChatEvent" -> DiagnosticEvent.CHAT
            "com.aitech.weartak_civ.events.IsDeviceWornEvent" -> DiagnosticEvent.SENSOR
            "com.aitech.weartak_civ.events.InteractionPliRefresh",
            "com.aitech.weartak_civ.events.MessageEvent" -> DiagnosticEvent.APP_EVENT
            "org.greenrobot.eventbus.SubscriberExceptionEvent" -> DiagnosticEvent.SUBSCRIBER_FAILURE
            else -> return
        }
        val metrics = when (event) {
            is com.aitech.weartak_civ.events.NwConnEvent -> mapOf(
                DiagnosticMetric.ACTIVE to if (event.isConn) 1L else 0L,
                DiagnosticMetric.KIND to event.transport.toLong()
            )
            is com.aitech.weartak_civ.events.TakServerConnStatusEvent ->
                mapOf(DiagnosticMetric.RESULT to event.connEvent.toLong())
            is com.aitech.weartak_civ.events.PowerManagerEvent -> mapOf(
                DiagnosticMetric.REPORT_SECONDS to CotDispatchService.actualReportingIntervalSecs.toLong(),
                DiagnosticMetric.POWER_THROTTLED to if (PowerManagerService.observationPowerThrottled) 1L else 0L,
                DiagnosticMetric.DOCK_THROTTLED to if (PowerManagerService.observationDockThrottled) 1L else 0L
            )
            else -> emptyMap()
        }
        capture(category, metrics)
    }

    private fun screen(activity: Activity): Long = when {
        activity.javaClass.simpleName.contains("Map") -> 1L
        activity.javaClass.simpleName.contains("Settings") -> 2L
        activity.javaClass.simpleName.contains("Chat") -> 3L
        activity.javaClass.simpleName.contains("Main") -> 4L
        else -> 0L
    }
    override fun onActivityResumed(activity: Activity) =
        capture(DiagnosticEvent.ACTIVITY_RESUMED, mapOf(DiagnosticMetric.SCREEN to screen(activity)))
    override fun onActivityStopped(activity: Activity) =
        capture(DiagnosticEvent.ACTIVITY_STOPPED, mapOf(DiagnosticMetric.SCREEN to screen(activity)))
    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        @Volatile private var instance: AppDiagnostics? = null
        fun initialize(application: Application): AppDiagnostics =
            instance ?: synchronized(this) {
                instance ?: AppDiagnostics(application).also { instance = it }
            }
        fun record(event: DiagnosticEvent, metrics: Map<DiagnosticMetric, Long> = emptyMap()) {
            instance?.capture(event, metrics)
        }
    }
}
