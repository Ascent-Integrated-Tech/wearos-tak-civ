// File: app/src/main/java/com/aitech/weartak_civ/service/ReactiveServiceManager.kt
package com.aitech.weartak_civ.service

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import androidx.core.content.ContextCompat
import com.aitech.weartak_civ.Utils.startForegroundServiceCompat
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.service.LicenseManager.validateLicense
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.util.EnumMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ReactiveServiceManager is a Foreground service orchestrator
 * responsible for starting and stopping WearTAK services. The
 * manager reacts to state changes exposed by ServiceConfiguration
 * and reconciles those against what is actually running.
 *
 * Each service has three layers of state:
 *   1. UserSetting (intent): the raw toggle/MDM config (e.g., enablePhysio == true).
 *   2. PolicyGates (environment): dynamic gates such as license, permissions. Extensible
 *      to future gates like power management, service hierarchies.
 *   3. RuntimeState: whether the Android Service is actually running.
 *
 * Desired = UserSetting AND PolicyGates
 *   - If Desired == true and RuntimeState == false → start the service.
 *   - If Desired == false and RuntimeState == true → stop the service.
 *
 * Manager behavior:
 *   - seeds Desired state once at startup
 *   - observes Desired flows and reconciles them to RuntimeState
 *   - observes License changes and reconciles states based on their policy gates.
 *   - always starts dependencies before dependents
 *   - always stops dependents before dependencies
 *   - runs independent of any Activity lifecycle
 *
 * Note: PHYSIO is a virtual group (UI only) and is not directly
 * orchestrated as a service. Its children (e.g., HeartRate, SkinTemp,
 * ActivityRecognition) are reconciled individually.
 */
class ReactiveServiceManager : Service() {

    companion object {
        private const val TAG = "WearTAK-ReactiveServiceMgr"
        private const val CHANNEL_ID = "ServiceManager_channel"
        private const val CHANNEL_NAME = "Service Manager"
        private const val NOTIF_ID = 77
        val isRunning = AtomicBoolean(false)
    }

    /*
     * Ordering and race-free states matter more than parallelism, so we use
     * our own single thread dispatcher for management (start/stop) of services
    */
    private val serviceLoop = Executors
        .newSingleThreadExecutor { r -> Thread(r, "svc--service-loop").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope  = CoroutineScope(SupervisorJob() + serviceLoop)

    private lateinit var registry: ServiceRegistry
    private lateinit var config: ServiceConfiguration

    // Snapshot of all the current service states (is this service supposed to be enabled or disabled)
    private val desiredServicesStateMap = EnumMap<ServiceType, Boolean?>(ServiceType::class.java)

    // PHYSIO is a virtual service, representing a group of services (UI only). We want to only manage real services (YYY::class.java) that are present in the registry.
    private lateinit var serviceTypesList: List<ServiceType>

    // Support legacy data repository by lazily resolving repo from main App; fallback if needed.
    private val repo by lazy {
        try {
            (applicationContext as WearTakApplication).repo
        } catch (_: Throwable) {
            DataRepository(applicationContext)
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "ReactiveServiceManager onCreate() Start...")
        isRunning.set(true)
        registry         = ServiceRegistry()
        config           = ServiceConfiguration(applicationContext, repo)
        serviceTypesList = registry.getAllServiceTypes()

        startForegroundServiceCompat(
            NOTIF_ID,
            importance = android.app.NotificationManager.IMPORTANCE_LOW,
            channelId = CHANNEL_ID,
            channelName = CHANNEL_NAME
        )

        scope.launch {
            seedOnce()
            observeStateChanges()
            startManagedConfigUpdateCollector(repo, registry)
            pollPermissions()
            collectLicenseKey()
        }
        Log.i(TAG, "ReactiveServiceManager onCreate() End...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning.set(false)
        stopAll()
        scope.cancel()
        serviceLoop.close()
        stopForeground(STOP_FOREGROUND_DETACH)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- Seeding & Observing ----------
    private suspend fun seedOnce() {
        // Initialize service state snapshot for real services
        serviceTypesList.forEach { type ->
            desiredServicesStateMap[type] = config.getServiceEnabledFlow(type).first()
        }
        // One initial reconciliation after seeding on bring up
        reconcileStates(RecType.INIT)
    }

    private val reconcileTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    @OptIn(FlowPreview::class)
    private fun observeStateChanges() {
        // set up watchers
        ServiceType.entries.forEach { type ->
            config.getServiceEnabledFlow(type)
                .distinctUntilChanged()
                .onEach { shouldRun ->
                    // avoid duplicate writes
                    if (desiredServicesStateMap[type] == shouldRun) return@onEach
                    desiredServicesStateMap[type] = shouldRun

                    // signal that "some change happened"
                    reconcileTrigger.tryEmit(Unit)
                }
                .launchIn(scope)
        }

        // coalesce all changes into a single reconcile
        reconcileTrigger
            .debounce(300)
            .onEach {
                reconcileStates(reason = RecType.STATE_CHANGE)
            }
            .launchIn(scope)

        // One-time snapshot logging if you still want it:
        reconcileStates(reason = RecType.OTHER)
    }

    // Simple + effective: lightweight polling uses negligible battery, while subscribing to every permission type is far more complex since Android provides no single universal receiver.
    private fun pollPermissions() {
        scope.launch {
            while (isActive) {
                delay(5_000) // every 5s
                reconcileStates(reason = RecType.PERMISSIONS_POLL)
            }
        }
    }

    enum class RecType {
        KNOX,
        PERMISSIONS_POLL,
        STATE_CHANGE,
        INIT,
        OTHER
    }

    // Determine whether to start or stop each services. Checks service's permissions, running status, dependencies status
    private fun reconcileStates(reason: RecType) {
        val order = topoOrder()

        var change = false
        val changedTypes = mutableListOf<ServiceType>() // only want to log states of services that have changed.
        fun boolStr(b: Boolean) = if (b) "true " else "false"
        var log = false
        if (reason == RecType.PERMISSIONS_POLL) {
            log = false
        }

        // START phase: dependencies before dependents (bottom up)
        order.forEach { t ->
            if (!serviceTypesList.contains(t)) return@forEach
            val desired = desiredServicesStateMap[t] == true
            val running = registry.isServiceRunning(t)
            val available = config.isServiceAvailable(t)
            val depsRunning = depsSatisfied(t)

            if (desired && !running && available && depsRunning) {
                startOne(t)
                changedTypes += t; change = true
            }
            if (log) {
                Log.i(
                    TAG,
                    String.format(
                        "Reconcile %-5s %-20s | DESIRED: %-5s | RUNNING: %-5s | AVAILABLE: %-5s | DEPS RUNNING: %-5s | CHANGE: %-5s",
                        "START",
                        t.toString().padEnd(20),
                        boolStr(desired),
                        boolStr(running),
                        boolStr(available),
                        boolStr(depsRunning),
                        boolStr(change)
                    )
                )
            }

        }

        // STOP phase: dependents before dependencies (top down)
        order.asReversed().forEach { t ->
            if (!serviceTypesList.contains(t)) return@forEach
            val desired = desiredServicesStateMap[t] == true
            val running = registry.isServiceRunning(t)
            val available = config.isServiceAvailable(t)

            if (running && (!desired || !available)) {
                Log.w(TAG, "Stopping $t (desired=$desired, available=${config.isServiceAvailable(t)})")
                stopOne(t)
                changedTypes += t; change = true
            }
            if (log) {
                Log.i(
                    TAG,
                    String.format(
                        "Reconcile %-5s %-20s | DESIRED: %-5s | RUNNING: %-5s | AVAILABLE: %-5s | CHANGE: %-5s",
                        "STOP",
                        t.toString().padEnd(20),
                        boolStr(desired),
                        boolStr(running),
                        boolStr(available),
                        boolStr(change)
                    )
                )
            }
        }

        if (log) Log.i(TAG, "changedTypes = $changedTypes")

        if (change) {
            scope.launch {
                repo.updateServicesStateFlow(snapshotRunningServices())
                snapshotAllStages(repo, registry)

                when (reason) {
                    RecType.KNOX -> logKnoxMgdServicesWithDiff(repo, registry, reason, changedTypes)
                    RecType.PERMISSIONS_POLL -> logChangedRowsWithDiffs(repo, registry, reason, changedTypes)
                    RecType.STATE_CHANGE -> logChangedRowsWithDiffs(repo, registry, reason, changedTypes)
                    RecType.INIT -> logFullTableWithDiffs(repo, registry, reason)
                    RecType.OTHER -> logFullTableWithDiffs(repo, registry, reason)
                }
            }
        }
    }

    private fun collectLicenseKey() {
        scope.launch {
            repo.getLicense.collect { license ->
                validateLicense(license)
                if (!LicenseManager.isLicenseValid()) {
                    Log.w(TAG, "License is invalid, stopping services if not already stopped.")
                } else {
                    Log.i(TAG, "Collected a valid License. Starting services if not already running.")
                }
                reconcileStates(RecType.OTHER)
            }
        }
    }

    private fun depsSatisfied(t: ServiceType): Boolean =
        t.dependencies.all { d ->
            // Only consider real services as dependencies; virtual ones (like PHYSIO) are not in registry
            if (!serviceTypesList.contains(d)) return@all true
            desiredServicesStateMap[d] == true && registry.isServiceRunning(d)
        }

    private fun startOne(t: ServiceType) {
        val cls = registry.getServiceClass(t) ?: return
        Log.i(TAG, "ReactiveServiceManager Starting: ${t.name}")
        val intent = Intent(this, cls)
        if (t.isForegroundService) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        registry.setServiceRunning(t, true)
    }

    private fun stopOne(t: ServiceType) {
        val cls = registry.getServiceClass(t) ?: return
        Log.i(TAG, "ReactiveServiceManager Stopping: ${t.name}")
        stopService(Intent(this, cls))
        registry.setServiceRunning(t, false)
    }

    private fun stopAll() {
        Log.i(TAG, "ReactiveServiceManager stopAll()")
        // Stop dependents before dependencies.
        topoOrder().asReversed().forEach { t ->
            if (!serviceTypesList.contains(t)) return@forEach
            if (registry.isServiceRunning(t)) {
                stopOne(t)
            }
        }
        scope.launch {
            logFullServiceStatesDebug(repo, registry, "Stopping all Services()")
        }
    }
    // ---------- Topological order (deps-first), cached in-memory ----------

    private var cachedTopo: List<ServiceType>? = null

    private fun topoOrder(): List<ServiceType> {
        cachedTopo?.let { return it }
        val result = mutableListOf<ServiceType>()
        val visited = mutableSetOf<ServiceType>()

        fun dfs(n: ServiceType) {
            if (!visited.add(n)) return
            n.dependencies.forEach(::dfs)
            result += n
        }

        // Build over the full enum graph, but only real nodes are acted upon later
        ServiceType.entries.forEach(::dfs)

        val ordered = result.distinct()
        cachedTopo = ordered
        return ordered
    }

    // ---------- Public state (optional helpers for UI/debug) ----------

//    data class ServiceState(
//        val type: ServiceType,   // identifier
//        val available: Boolean,  // service can only start if it is available --> licensed, permissions granted, not running, dependencies are up, and this service is desired.
//        val licensed: Boolean,    // if no license is required, set to always true.
//        val permsGranted: Boolean, // android manifest permissions are granted.
//        val running: Boolean,     // set by individual service oncreate() after starting
//        val desired: Boolean,     // Request from UI, ManagedConfigs. Available
//        val dependencies: List<ServiceType> = emptyList(),  // services that must be started
//    )

    private val lastSnapshot: EnumMap<ServiceType, ServiceDebugState?> =
        EnumMap(ServiceType::class.java)

    private fun sym(b: Boolean?): String = when (b) {
        null -> "—"
        true -> "T"
        false -> "F"
    }

    private fun cell(prev: Boolean?, curr: Boolean?): String =
        if (prev == curr) sym(curr) else "${sym(prev)} → ${sym(curr)}"

    private fun normalizedRow(
        snapRow: ServiceDebugState?,        // may be null if no record
        desiredNow: Boolean,                // live
        runningNow: Boolean                 // live
    ): ServiceDebugState {
        return ServiceDebugState(
            type          = snapRow?.type ?: ServiceType.TAK_CONNECT, // type is only for identity; not printed
            remoteKnox    = snapRow?.remoteKnox,       // nullable
            localCompanion= snapRow?.localCompanion,   // nullable
            configUpdate  = snapRow?.configUpdate,     // nullable
            desired       = desiredNow,                // always shown (live)
            running       = runningNow,                // always shown (live)
            dependencies  = emptyList()
        )
    }

    private fun printHeader(reason: RecType, changed: List<ServiceType>?) {
        val header0 = "         |------- MgdCfgUpdate --------|----- ReSrvMgr ----|"
        val header1  = "SERVICE | R-KNOX  | LOCAL   | PSTORE  | DESIRED | RUNNING |"
        val sep    = "-".repeat(header1.length)
        val ctx    = buildString {
            append("Reason for Reconcile: ").append(reason)
            if (!changed.isNullOrEmpty()) append(" | Changed: ").append(changed.joinToString { it.name })
        }
        Log.v(TAG, sep)
        Log.i(TAG, ctx)
        Log.v(TAG, header0)
        Log.v(TAG, header1)
        Log.v(TAG, sep)
    }

    private fun printRow(t: ServiceType, prev: ServiceDebugState?, curr: ServiceDebugState) {
        Log.v(
            TAG,
            String.format(
                "%-7s | %-7s | %-7s | %-7s | %-7s | %-7s |",
                t.name.take(7),
                cell(prev?.remoteKnox,    curr.remoteKnox),
                cell(prev?.localCompanion,curr.localCompanion),
                cell(prev?.configUpdate,  curr.configUpdate),
                cell(prev?.desired,       curr.desired),
                cell(prev?.running,       curr.running)
            )
        )
    }

    fun logKnoxMgdServicesWithDiff(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: RecType
    ) {
        val snap = repo.serviceDebugStateFlow.value
        printHeader(reason, changed = null)

        val newSnapshot: EnumMap<ServiceType, ServiceDebugState?> = EnumMap(ServiceType::class.java)

        ServiceType.entries.forEach { t ->
            val desiredNow = (desiredServicesStateMap[t] == true)
            val runningNow = registry.isServiceRunning(t)
            val curr = normalizedRow(snap[t], desiredNow, runningNow)
            val prev = lastSnapshot[t]
            if (t.isKnoxMgdService) {
                printRow(t, prev, curr)
            }
            // store a fresh copy for future diffs
            newSnapshot[t] = curr.copy()
        }

        lastSnapshot.clear()
        lastSnapshot.putAll(newSnapshot)
    }


    fun logKnoxMgdServicesWithDiff(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: RecType,
        changed: List<ServiceType> // from reconcileStates()
    ) {
        val snap = repo.serviceDebugStateFlow.value
        val newSnapshot: EnumMap<ServiceType, ServiceDebugState?> = EnumMap(ServiceType::class.java)

        // Build current rows first
        val currentRows: Map<ServiceType, ServiceDebugState> =
            ServiceType.entries.associateWith { t ->
                normalizedRow(
                    snapRow    = snap[t],
                    desiredNow = (desiredServicesStateMap[t] == true),
                    runningNow = registry.isServiceRunning(t)
                )
            }

        // Determine actual changed rows (use hint to keep it tight, but verify)
        val actualChanged = changed.filter { t ->
            val prev = lastSnapshot[t]
            val curr = currentRows[t]!!
            rowChanged(prev, curr)
        }

        if (actualChanged.isEmpty()) return

        printHeader(reason, changed = actualChanged)

        actualChanged.forEach { t ->
            val prev = lastSnapshot[t]
            val curr = currentRows[t]!!
            if (t.isKnoxMgdService) {
                printRow(t, prev, curr)
            }
        }

        // Update full lastSnapshot with fresh copies
        ServiceType.entries.forEach { t -> newSnapshot[t] = currentRows[t]!!.copy() }
        lastSnapshot.clear()
        lastSnapshot.putAll(newSnapshot)
    }


    fun logFullTableWithDiffs(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: RecType
    ) {
        val snap = repo.serviceDebugStateFlow.value
        printHeader(reason, changed = null)

        val newSnapshot: EnumMap<ServiceType, ServiceDebugState?> = EnumMap(ServiceType::class.java)

        ServiceType.entries.forEach { t ->
            val desiredNow = (desiredServicesStateMap[t] == true)
            val runningNow = registry.isServiceRunning(t)
            val curr = normalizedRow(snap[t], desiredNow, runningNow)
            val prev = lastSnapshot[t]
            printRow(t, prev, curr)
            // store a fresh copy for future diffs
            newSnapshot[t] = curr.copy()
        }

        lastSnapshot.clear()
        lastSnapshot.putAll(newSnapshot)
    }

    private fun rowChanged(prev: ServiceDebugState?, curr: ServiceDebugState): Boolean {
        return prev?.remoteKnox     != curr.remoteKnox     ||
                prev?.localCompanion != curr.localCompanion ||
                prev?.configUpdate   != curr.configUpdate   ||
                prev?.desired        != curr.desired        ||
                prev?.running        != curr.running
    }

    fun logChangedRowsWithDiffs(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: RecType,
        changed: List<ServiceType> // from reconcileStates()
    ) {
        val snap = repo.serviceDebugStateFlow.value
        val newSnapshot: EnumMap<ServiceType, ServiceDebugState?> = EnumMap(ServiceType::class.java)

        // Build current rows first
        val currentRows: Map<ServiceType, ServiceDebugState> =
            ServiceType.entries.associateWith { t ->
                normalizedRow(
                    snapRow    = snap[t],
                    desiredNow = (desiredServicesStateMap[t] == true),
                    runningNow = registry.isServiceRunning(t)
                )
            }

        // Determine actual changed rows (use hint to keep it tight, but verify)
        val actualChanged = changed.filter { t ->
            val prev = lastSnapshot[t]
            val curr = currentRows[t]!!
            rowChanged(prev, curr)
        }

        if (actualChanged.isEmpty()) return

        printHeader(reason, changed = actualChanged)

        actualChanged.forEach { t ->
            val prev = lastSnapshot[t]
            val curr = currentRows[t]!!
            printRow(t, prev, curr)
        }

        // Update full lastSnapshot with fresh copies
        ServiceType.entries.forEach { t -> newSnapshot[t] = currentRows[t]!!.copy() }
        lastSnapshot.clear()
        lastSnapshot.putAll(newSnapshot)
    }

    data class ServiceDebugState(
        val type: ServiceType,
        val remoteKnox: Boolean?,       // nullable => null means “no restriction configured remotely”
        val localCompanion: Boolean?,   // MainActivity companion object states
        val configUpdate: Boolean?,     // remoteKnox ?: localPersisted → in MainActivity handleMgdConfigUpdate(), what we store if it has changed.
        val desired: Boolean?,          //  desiredServicesStateMap (does someone want this to run?)
        val running: Boolean,           // ServiceRegistry.isServiceRunning(type)  (is it actually running → permissions checked, license checked, dependent services are up).
        val dependencies: List<ServiceType> = emptyList()   // list of parent services (e.g. Chat depends on TakConnect).
    )

    data class ServiceState(
        val type: ServiceType,    // service identifier
        val running: Boolean,     // set by individual service oncreate() after starting
        val desired: Boolean,     // Request from UI, ManagedConfigs. Available
        val dependencies: List<ServiceType> = emptyList()  // services that must be started
    )

//    fun snapshotStates(): List<ServiceState> =
//        ServiceType.entries.map { t ->
//            ServiceState(
//                type = t,
//                desired = desiredServicesStateMap[t] == true,
//                running = if (serviceTypesList.contains(t)) registry.isServiceRunning(t) else false,
//                dependencies = t.dependencies,
//            )
//        }

    private fun snapshotRunningServices(): EnumMap<ServiceType, Boolean> =
        EnumMap<ServiceType, Boolean>(ServiceType::class.java).apply {
            put(ServiceType.TAK_CONNECT,           TAKConnectService.isRunning.get())
            put(ServiceType.COT_DISPATCH,          CotDispatchService.isRunning.get())
            put(ServiceType.LOCATION,              LocationService.isRunning.get())
            put(ServiceType.HEART_RATE,            HeartrateService.isRunning.get())
            put(ServiceType.SKIN_TEMP,             SkinTempService.isRunning.get())
            put(ServiceType.PHYSIO,                desiredServicesStateMap.get(ServiceType.PHYSIO))
            put(ServiceType.DITTO,                 DittoService.isRunning.get())
            put(ServiceType.CHAT,                  ChatService.isRunning.get())
            put(ServiceType.BLE_BEACON,            BleBeaconService.isRunning.get())
            put(ServiceType.MOVEMENT_DETECTION,    MovementDetectionService.isRunning.get())
            put(ServiceType.ACTIVITY_RECOGNITION,  ActivityRecognitionService.isRunning.get())
            put(ServiceType.OFF_BODY_DETECT,       OffBodyDetectService.isRunning.get())
            put(ServiceType.BATTERY,               BatteryService.isRunning.get())
            put(ServiceType.POWER_MGR,             PowerManagerService.isRunning.get())
        }

    /*
    private suspend fun logSnapshot() {
        val snapshot = snapshotStates()
        snapshotAllStages(repo, registry)

//        Log.d(TAG, "ReactiveServiceManager logSnapshot() \n=== Current Service Snapshot ===")
//        snapshot.forEach { s ->
//            Log.d(
//                TAG,
//                String.format(
//                    "%-22s desired=%-5s running=%-5s deps=%s",
//                    s.type.name,
//                    s.desired,
//                    s.running,
//                    if (s.dependencies.isEmpty()) "-" else s.dependencies.joinToString()
//                )
//            )
//        }
    }

     */

    private suspend fun publishDesiredSnapshot(repo: DataRepository) {
        val desired = EnumMap<ServiceType, Boolean>(ServiceType::class.java)
        ServiceType.entries.forEach { t ->
            desired[t] = (desiredServicesStateMap[t] == true)
        }
        repo.updateDebugStageDesired(desired)
    }

    private suspend fun publishRunningSnapshot(repo: DataRepository, registry: ServiceRegistry) {
        val running = EnumMap<ServiceType, Boolean>(ServiceType::class.java)
        ServiceType.entries.forEach { t ->
            running[t] = registry.isServiceRunning(t)
        }
        repo.updateDebugStageRunning(running)
    }

    suspend fun snapshotAllStages(repo: DataRepository, registry: ServiceRegistry) {
        publishDesiredSnapshot(repo)
        publishRunningSnapshot(repo, registry)
//        logServiceDebug(repo) // optional: log immediately after publishing
    }

    /*
    suspend fun logServiceDebug(repo: DataRepository) {
        val snap = repo.serviceDebugStateFlow.value
        val header = "SERVICE | R-KNOX | LOCAL | CONFIG | DESIRED | RUNNING"
        Log.v(TAG, header)
        ServiceType.entries.forEach { t ->
            val s = snap[t]
            if (s != null) {
                Log.v(TAG, String.format(
                    "%-7s | %-6s | %-5s | %-6s | %-7s | %-7s",
                    t.name.take(7),
                    formatBool(s.remoteKnox),
                    formatBool(s.localPersisted),
                    formatBool(s.configUpdate),
                    formatBool(s.desired),
                    formatBool(s.running)
                ))
            } else {
                Log.v(TAG, String.format("%-7s | (no record)", t.name.take(7)))
            }
        }
    }
*/

    /**
     * Logs a single table of all services.
     * - remoteKnox/localPersisted/configUpdate: show "—" when no record exists.
     * - desired: computed live from desiredServicesStateMap (true/false).
     * - running: queried live from ServiceRegistry (true/false).
     *
     * @param reason  A short message describing *why* we are logging (e.g., "ManagedConfigUpdate applied")
     * @param changed Optionally list the services that triggered this log (purely informational)
     */
    fun logFullServiceStatesDebug(
        repo: DataRepository,
        registry: ServiceRegistry,
        reason: String,
        changed: List<ServiceType>? = null
    ) {
        val snap = repo.serviceDebugStateFlow.value   // EnumMap<ServiceType, ServiceDebugState>
        val header = "SERVICE | R-KNOX | LOCAL | CONFIG | DESIRED | RUNNING"
        val context = buildString {
            append("Reason: ").append(reason)
            if (!changed.isNullOrEmpty()) {
                append(" | Changed: ").append(changed.joinToString { it.name })
            }
        }
        val separator = "-".repeat(header.length)

        Log.v(TAG, context)
        Log.v(TAG, header)
        Log.v(TAG, separator)

        ServiceType.entries.forEach { t ->
            val row = snap[t]  // may be null if no record was ever written

            // show "—" when row is missing; desired & running are always shown
            val remoteKnox   = row?.remoteKnox                // Boolean?
            val localPersist = row?.localCompanion            // Boolean?
            val configUpdate = row?.configUpdate              // Boolean?
            val desiredNow   = (desiredServicesStateMap[t] == true) // Boolean
            val runningNow   = registry.isServiceRunning(t)          // Boolean

            Log.v(TAG, String.format(
                "%-7s | %-6s | %-5s | %-6s | %-7s | %-7s",
                t.name.take(7),
                sym(remoteKnox),
                sym(localPersist),
                sym(configUpdate),
                sym(desiredNow),
                sym(runningNow)
            ))
        }
    }

    fun startManagedConfigUpdateCollector(repo: DataRepository, registry: ServiceRegistry) {
        scope.launch {
            repo.serviceDebugStateFlow
                // only collect config update so isRunning and desired state updates don't trigger
                .map { snap -> snap.mapValues { it.value?.configUpdate } }
                .distinctUntilChanged() // avoid repeats of same map
                .collect {
                    logKnoxMgdServicesWithDiff(
                        repo = repo,
                        registry = registry,
                        reason = RecType.KNOX
                    )
                }
        }
    }

    fun has(requiredPerms: List<String>): Boolean {
        val missingPerms = requiredPerms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPerms.isNotEmpty()) {
            Log.w(TAG, "We are missing permissions to run this Service!: $missingPerms")
            return false
        } else {
            Log.i(TAG, "All permissions for Service are granted!")
            return true
        }
    }

    // ---------- Enum (PHYSIO is virtual; never scheduled to run directly, it is a UI toggle that triggers sub services) ----------

    enum class ServiceType(
        val isForegroundService: Boolean = false,
        val dependencies: List<ServiceType> = emptyList(),
        val isKnoxMgdService: Boolean
    ) {
        TAK_CONNECT(true, isKnoxMgdService = false),
        CHAT(true, dependencies = listOf(TAK_CONNECT), isKnoxMgdService = true),
        COT_DISPATCH(true, isKnoxMgdService = false),
        LOCATION(true, isKnoxMgdService = true),
        PHYSIO(isKnoxMgdService = true), // virtual group: not in registry
        HEART_RATE(true, dependencies = listOf(PHYSIO), isKnoxMgdService = true),
        SKIN_TEMP(true, dependencies = listOf(PHYSIO), isKnoxMgdService = true),
        ACTIVITY_RECOGNITION(true, dependencies = listOf(PHYSIO), isKnoxMgdService = true),
        DITTO(true, isKnoxMgdService = true),
        BLE_BEACON(true, isKnoxMgdService = true),
        MOVEMENT_DETECTION(true, isKnoxMgdService = false),
        OFF_BODY_DETECT(true, isKnoxMgdService = false),
        BATTERY(true, isKnoxMgdService = false),
        POWER_MGR(true, isKnoxMgdService = false)
    }
}