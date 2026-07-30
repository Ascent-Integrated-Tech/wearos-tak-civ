package com.aitech.weartak_civ.cep.di

import android.content.Context
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.ProfileStore
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
import com.aitech.weartak_civ.cep.engine.action.CepActionDispatcher
import com.aitech.weartak_civ.cep.engine.action.LogActionHandler
import com.aitech.weartak_civ.cep.engine.action.WarnActionHandler
import com.aitech.weartak_civ.cep.engine.action.WarnLogActionHandler
import com.aitech.weartak_civ.cep.engine.config.CanonicalJson
import com.aitech.weartak_civ.cep.engine.config.CepConfigRegistry
import com.aitech.weartak_civ.cep.engine.config.CepDiagnosticRecord
import com.aitech.weartak_civ.cep.engine.config.CepDiagnosticSink
import com.aitech.weartak_civ.cep.engine.config.DiagnosticsCodec
import com.aitech.weartak_civ.cep.engine.config.ManagedConfigOverlayParser
import com.aitech.weartak_civ.cep.engine.config.ParsedManagedOverlay
import com.aitech.weartak_civ.cep.engine.config.ParserException
import com.aitech.weartak_civ.cep.engine.config.ReasonCodes
import com.aitech.weartak_civ.cep.engine.config.Resolved
import com.aitech.weartak_civ.cep.engine.config.SourceLayer
import com.aitech.weartak_civ.cep.engine.config.ValidationResult
import com.aitech.weartak_civ.cep.engine.lifecycle.CepEngineHolder
import com.aitech.weartak_civ.cep.plugin.PluginRegistry
import com.aitech.weartak_civ.cep.plugin.PluginTrust
import com.aitech.weartak_civ.cep.metric.MetricInspectionSnapshot
import java.io.File
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong

/**
 * CEP DI bootstrap and runtime apply orchestrator.
 *
 * Responsibilities (per WEAR-508 design):
 *  - Cold-start bootstrap: parse built-ins, hydrate cached managed overlay, validate +
 *    compose synchronously, fall back to built-ins on rejection with a persisted diagnostic.
 *  - Engine factory: build a fresh [CepEngine] with its own coroutine scope from a resolved
 *    [CepConfig]. The engine itself remains immutable post-construction.
 *  - Holder-mediated apply: [applyManagedOverlay] runs validate → build → warm-up → swap →
 *    cancel-old → persist applied state on a single dedicated apply lane (last-write-wins).
 *  - Diagnostics: persists structured rejection records via [DataRepository] so they
 *    survive process restart.
 *
 * `CepEngine` is intentionally NOT given an `applyConfig(...)` method. Runtime swap lives
 * here (per [Update v1.1 — U1 cleanup]).
 */
object CepProvider {
    private const val TAG = "CEP"

    private lateinit var appCtx: Context
    private lateinit var dataRepo: DataRepository
    private lateinit var registry: CepConfigRegistry

    /**
     * Dispatcher scope is global because CepActionDispatcher and ProfileStore are
     * registry-independent. Per-engine scopes are created in [buildEngine].
     */
    private val supportScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Single-threaded apply lane so recompose is serialized (per concurrency contract). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val applyDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val applyMutex = Mutex()

    /** Holder is the single ownership seam for the running engine. */
    private val holder = CepEngineHolder()

    /** Monotonic apply ticket; used by the apply lane to enforce last-write-wins. */
    private val applyTicket = AtomicLong(0L)
    @Volatile private var latestQueuedTicket: Long = 0L
    @Volatile private var latestAppliedHash: String = ""

    @Volatile private var dispatcher: CepActionDispatcher? = null
    @Volatile private var profileStore: ProfileStore? = null
    @Volatile private var diagnosticSink: PersistentDiagnosticSink? = null

    private lateinit var managedApplyExecutor: CepManagedApplyExecutor
    private lateinit var pluginRegistry: PluginRegistry
    private lateinit var pluginTrustGate: PluginTrust

    fun init(context: Context) {
        appCtx = context.applicationContext
        dataRepo = (appCtx as WearTakApplication).repo
        diagnosticSink = PersistentDiagnosticSink(dataRepo, supportScope)
        registry = bootstrapRegistry(appCtx)
        pluginTrustGate = PluginTrust(appCtx, dataRepo)
        pluginRegistry = PluginRegistry(
            repo = dataRepo,
            trust = pluginTrustGate,
            onBootstrapParseFailure = { raw, reasonCode, message ->
                recordRejection(
                    context = CepDiagnosticContext(
                        source = SourceLayer.BOOTSTRAP,
                        reasonCode = reasonCode,
                        envelope = CepDiagnosticEnvelope(
                            payloadHash = CanonicalJson.hashRaw(raw),
                        ),
                        outcome = CepDiagnosticOutcome(
                            effectiveBefore = latestAppliedHash,
                            effectiveAfter = latestAppliedHash,
                            message = "cached plugin overlay rejected at boot: $message",
                        )
                    )
                )
            }
        ).also {
            runBlocking { it.bootstrap() }
        }
        managedApplyExecutor = CepManagedApplyExecutor(
            registry = registry,
            persistence = RepoCepApplyPersistence(dataRepo),
            holder = holder,
            appCtx = appCtx,
            buildEngine = { ctx, cfg -> buildEngine(ctx, cfg) },
            diagnostics = object : CepApplyDiagnostics {
                override fun recordRejection(context: CepDiagnosticContext) {
                    this@CepProvider.recordRejection(context)
                }

                override fun recordInfo(context: CepDiagnosticContext) {
                    this@CepProvider.recordInfo(context)
                }
            },
            clearAlertsForRemovedRules = { removed, reason ->
                clearAlertsForRemovedRules(removed, reason)
            },
            pluginContributionsProvider = { pluginRegistry.contributions() }
        )
    }

    /** Same [PluginTrust] used by overlay ingest and registry; used for AIDL bind-time admission. */
    fun pluginTrust(): PluginTrust {
        check(::pluginTrustGate.isInitialized) { "CepProvider.init must be called before plugin bind" }
        return pluginTrustGate
    }

    /**
     * Cold-start: parse built-ins, build the registry, attempt to hydrate any cached
     * managed overlay, fall back to built-ins on rejection. Always returns a registry
     * configured against the parsed built-in surface — i.e. the registry itself can never
     * be in an unhealthy state because of a bad cached overlay.
     */
    private fun bootstrapRegistry(context: Context): CepConfigRegistry {
        val builtIn = RuleCompiler.loadFromRaw(context)
        val defaultCadence = RuleCompiler.EngineCadence(
            tickHz = builtIn.tickHz,
            ruleEvalIntervalMs = builtIn.ruleEvalIntervalMs
        )
        return CepConfigRegistry(builtIn = builtIn, defaultCadence = defaultCadence)
    }

    /**
     * Synchronously produce the cold-start [Resolved] for engine creation.
     * If the cached overlay is malformed/invalid, persists a diagnostic and falls back to
     * built-ins. Engine startup is never delayed by an overlay rejection.
     */
    private fun resolveColdStart(): Resolved {
        val cachedRaw = runBlocking { dataRepo.getCepConfigurationRawJsonOnce() }
        val effectiveBefore = "" // before any apply this run
        if (cachedRaw.isBlank()) {
            return composeBuiltinsOnly()
        }
        val parsed = try {
            ManagedConfigOverlayParser.parse(cachedRaw, registry.configBounds)
        } catch (e: ParserException) {
            recordRejection(
                context = CepDiagnosticContext(
                    source = SourceLayer.BOOTSTRAP,
                    reasonCode = e.reasonCode,
                    envelope = CepDiagnosticEnvelope(
                        payloadHash = CanonicalJson.hashRaw(cachedRaw),
                    ),
                    outcome = CepDiagnosticOutcome(
                        effectiveBefore = effectiveBefore,
                        effectiveAfter = effectiveBefore,
                        fallbackDecision = CepDiagnosticRecord.FALLBACK_TO_BUILTINS,
                        message = "cached overlay rejected at boot: ${e.message}",
                    )
                )
            )
            return composeBuiltinsOnly()
        }

        val pluginContrib = runBlocking { pluginRegistry.contributions() }
        return when (val v = registry.compose(parsed, pluginContrib)) {
            is ValidationResult.Ok -> v.resolved
            is ValidationResult.Err -> {
                recordRejection(
                    context = CepDiagnosticContext(
                        source = SourceLayer.BOOTSTRAP,
                        reasonCode = v.reasonCode,
                        envelope = CepDiagnosticEnvelope(
                            schemaVersion = parsed.schemaVersion,
                            payloadHash = parsed.payloadHash,
                            tenant = parsed.tenant,
                            issuer = parsed.issuer,
                        ),
                        outcome = CepDiagnosticOutcome(
                            effectiveBefore = effectiveBefore,
                            effectiveAfter = effectiveBefore,
                            fallbackDecision = CepDiagnosticRecord.FALLBACK_TO_BUILTINS,
                            message = "cached overlay invalid at boot: ${v.message}",
                        )
                    )
                )
                composeBuiltinsOnly()
            }
        }
    }

    private fun composeBuiltinsOnly(): Resolved {
        return when (val r = registry.composeWithoutManagedOverlay()) {
            is ValidationResult.Ok -> r.resolved
            is ValidationResult.Err -> error("Built-in CEP config failed validation: ${r.message}")
        }
    }

    fun getActionDispatcher(): CepActionDispatcher {
        return dispatcher ?: synchronized(this) {
            dispatcher ?: CepActionDispatcher(
                scope = supportScope,
                handlers = listOf(
                    LogActionHandler(appScope = supportScope),
                    WarnActionHandler(appScope = supportScope),
                    WarnLogActionHandler(appScope = supportScope)
                )
            ).also { dispatcher = it }
        }
    }

    fun getProfile(context: Context = appCtx): ProfileStore {
        return profileStore ?: synchronized(this) {
            profileStore ?: ProfileStore(context.applicationContext).also { profileStore = it }
        }
    }

    /**
     * Returns the running engine, building and starting one synchronously the first time it
     * is requested. Subsequent calls return the holder's authoritative reference.
     */
    fun getOrCreateEngine(context: Context): CepEngine {
        val existing = holder.current()
        if (existing != null) return existing
        return synchronized(this) {
            holder.current() ?: bootstrapEngine(context)
        }
    }

    /** Authoritative read of the running engine, or null before cold start. */
    fun currentEngine(): CepEngine? = holder.current()

    /** Read-only baseline inspection for the in-app Beta Features screen. */
    suspend fun inspectBaselineMetrics(): Map<String, MetricInspectionSnapshot> =
        currentEngine()?.inspectBaselineMetrics().orEmpty()

    /** Force and persist all baseline cores without clearing metric history. */
    suspend fun recalculateAllBaselineMetrics(): Map<String, MetricInspectionSnapshot> =
        currentEngine()?.recalculateAllBaselineMetrics().orEmpty()

    /** Creates a local ZIP copy of metric history and cached results. */
    suspend fun exportBaselineMetrics(): File? =
        currentEngine()?.exportBaselineMetrics()

    /** Performs the existing explicit user-metrics reset. */
    suspend fun resetBaselineMetricsForUserChange(): Boolean {
        val engine = currentEngine() ?: return false
        engine.resetAndReinitializeMetricsForUserChange()
        return true
    }

    /** Returns the configured registry — useful for tests and for ManagedConfigApplyWorker. */
    fun registry(): CepConfigRegistry = registry

    /** Persisted diagnostic sink. */
    fun diagnostics(): CepDiagnosticSink = requireNotNull(diagnosticSink) {
        "CepProvider.init(...) must be called before diagnostics()"
    }

    /**
     * First-run engine construction. Resolves the cold-start config, builds an engine,
     * warms it up synchronously, installs it into the holder, and starts it. Mirrors the
     * holder-mediated swap path but with no prior engine to cancel.
     */
    private fun bootstrapEngine(context: Context): CepEngine {
        val resolved = resolveColdStart()
        val engine = buildEngine(context, resolved.engineConfig)
        runBlocking { engine.warmUp() }
        check(holder.install(engine)) { "CepEngineHolder already initialized before bootstrap" }
        runBlocking { engine.applyPressureAlertHygiene() }
        runBlocking { engine.applyPhysioAlertHygiene() }
        // The cold-start install IS an authoritative install — set the in-memory hash
        // BEFORE start() launches coroutines so any concurrent managed-config worker that
        // reads `latestAppliedHash` sees the correct baseline.
        latestAppliedHash = resolved.effectiveHash
        engine.start()
        // Persist baseline applied-hash so subsequent applies have a stable point of
        // comparison for no-op detection across process restarts. Per the design, applied
        // state is post-swap-only; the cold-start install qualifies as becoming
        // authoritative (the engine the system actually ran).
        runBlocking {
            dataRepo.storeCepConfigurationApplied(resolved.effectiveHash, System.currentTimeMillis())
        }
        return engine
    }

    /**
     * Factory builder per [Update v1.1 — U1 cleanup]. Constructs a NEW engine instance with
     * its own coroutine scope. Old and new engines must not share scopes — this is what
     * makes "fully cancelled before the new ones become authoritative" enforceable.
     */
    fun buildEngine(context: Context, config: CepConfig): CepEngine {
        val freshScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val ctx = context.applicationContext
        val app = ctx as WearTakApplication
        return CepEngine(
            appContext = ctx,
            scope = freshScope,
            config = config,
            dataRepository = app.repo
        )
    }

    /**
     * Apply a managed overlay payload. Returns an [ApplyResult] describing the outcome.
     * Designed to be called from `ManagedConfigApplyWorker` on the WorkManager dispatcher;
     * internally serializes through [applyMutex] on a single-parallelism dispatcher to enforce
     * last-write-wins (per [Update v1.1 — U10]).
     *
     * Empty `rawPayload` means "managed overlay is now empty" and is processed by recomposing
     * built-ins-only. The cached raw is cleared as part of the pre-swap persistence step.
     */
    suspend fun applyManagedOverlay(
        rawPayload: String?
    ): ApplyResult = withContext(applyDispatcher) {
        val ticket = applyTicket.incrementAndGet()
        latestQueuedTicket = ticket
        applyMutex.withLock {
            // Last-write-wins: if a newer ticket arrived while we were waiting for the lock,
            // we abandon this one cleanly without touching the engine.
            if (ticket != latestQueuedTicket) {
                return@withLock ApplyResult.Superseded
            }
            evictExpiredPluginOverlays()
            doApplyLocked(ticket, rawPayload?.takeUnless { ManagedConfigOverlayParser.isEmpty(it) })
        }
    }

    /**
     * Apply a plugin overlay broadcast. The receiver MUST have already resolved
     * [senderUid] and [senderPackages] from `Binder.getCallingUid()` + `getPackagesForUid`
     * so the trust gate can bind the claimed `pluginId` to the actual broadcaster's package
     * identity (closing the first-handshake spoofability gap).
     */
    suspend fun applyPluginOverlay(
        rawOverlayJson: String,
        senderUid: Int,
        senderPackages: Set<String>,
    ): ApplyResult = withContext(applyDispatcher) {
        val ticket = applyTicket.incrementAndGet()
        latestQueuedTicket = ticket
        applyMutex.withLock {
            if (ticket != latestQueuedTicket) return@withLock ApplyResult.Superseded
            evictExpiredPluginOverlays()
            val parsed = try {
                pluginRegistry.upsertFromRaw(rawOverlayJson, senderUid, senderPackages)
            } catch (e: ParserException) {
                recordRejection(
                    context = CepDiagnosticContext(
                        source = SourceLayer.PLUGIN,
                        reasonCode = e.reasonCode,
                        envelope = CepDiagnosticEnvelope(
                            payloadHash = CanonicalJson.hashRaw(rawOverlayJson),
                        ),
                        outcome = CepDiagnosticOutcome(
                            effectiveBefore = latestAppliedHash,
                            effectiveAfter = latestAppliedHash,
                            fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                            message = "plugin overlay rejected: ${e.message}",
                        )
                    )
                )
                return@withLock ApplyResult.Rejected(e.reasonCode, e.message ?: "")
            }
            val managedRaw = dataRepo.getCepConfigurationRawJsonOnce().takeUnless { ManagedConfigOverlayParser.isEmpty(it) }
            val result = managedApplyExecutor.execute(
                rawPayload = managedRaw,
                effectiveBefore = latestAppliedHash,
                isSuperseded = { ticket != latestQueuedTicket },
                source = SourceLayer.PLUGIN,
                pluginId = parsed.pluginId,
                pluginVersion = parsed.pluginVersion
            )
            if (result is ApplyResult.Applied) latestAppliedHash = result.effectiveHash
            result
        }
    }

    /**
     * Plugin-explicit unregister + recompose. The plugin-side alert hygiene contract
     * (§545–550) is satisfied because the apply executor computes
     * `previousRules - newEngine.ruleIds` after the swap and runs
     * [clearAlertsForRemovedRules] over the diff — same path the managed apply lane uses.
     */
    suspend fun unregisterPluginOverlay(pluginId: String): ApplyResult = withContext(applyDispatcher) {
        val ticket = applyTicket.incrementAndGet()
        latestQueuedTicket = ticket
        applyMutex.withLock {
            if (ticket != latestQueuedTicket) return@withLock ApplyResult.Superseded
            evictExpiredPluginOverlays()
            pluginRegistry.unregister(pluginId)
            val managedRaw = dataRepo.getCepConfigurationRawJsonOnce().takeUnless { ManagedConfigOverlayParser.isEmpty(it) }
            val result = managedApplyExecutor.execute(
                rawPayload = managedRaw,
                effectiveBefore = latestAppliedHash,
                isSuperseded = { ticket != latestQueuedTicket },
                source = SourceLayer.PLUGIN,
                pluginId = pluginId
            )
            if (result is ApplyResult.Applied) latestAppliedHash = result.effectiveHash
            result
        }
    }

    /**
     * Package-removed eviction. Unregisters every plugin currently owned by [packageName]
     * (pluginId == packageName per design §477) and triggers a recompose so alert hygiene
     * runs on rules contributed by the uninstalled plugin.
     */
    suspend fun evictPluginByPackage(packageName: String): ApplyResult? = withContext(applyDispatcher) {
        if (packageName.isBlank()) return@withContext null
        evictExpiredPluginOverlays()
        val known = pluginRegistry.knownPluginIds()
        // pluginId == sender package name in v1, so a direct match is the only eviction key.
        if (packageName !in known) return@withContext null
        unregisterPluginOverlay(packageName)
    }

    /**
     * Plugin sample ingress (design §414–432). The receiver has already validated drift +
     * rate-limit + sender binding; this entry runs the trust gate (cert pin) and routes the
     * accepted sample into the engine ingest path so `FeatureStore` / `WindowStore` rings
     * pick it up.
     */
    suspend fun ingestPluginSample(
        pluginId: String,
        topic: String,
        tsMs: Long,
        value: Double,
        senderUid: Int,
        senderPackages: Set<String>,
    ): IngestResult {
        return try {
            // Re-run trust at submission time. Sender-package binding is already guaranteed
            // by the receiver, but cert pinning may still reject if the package's signer has
            // changed since first handshake.
            pluginTrustGate.enforce(pluginId, senderUid, senderPackages)
            val engine = currentEngine() ?: return IngestResult.Dropped("engine_not_ready")
            engine.ingest(topic, value, tsMs)
            IngestResult.Accepted
        } catch (e: ParserException) {
            recordRejection(
                context = CepDiagnosticContext(
                    source = SourceLayer.PLUGIN,
                    reasonCode = e.reasonCode,
                    envelope = CepDiagnosticEnvelope(
                        plugin = CepDiagnosticPlugin(pluginId = pluginId),
                    ),
                    outcome = CepDiagnosticOutcome(
                        effectiveBefore = latestAppliedHash,
                        effectiveAfter = latestAppliedHash,
                        fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                        message = "plugin sample rejected: ${e.message}",
                    )
                )
            )
            IngestResult.Dropped(e.reasonCode)
        }
    }

    /**
     * Helper for the receiver layer to surface a structured diagnostic when an inbound
     * broadcast fails the sender-package binding check before any worker is enqueued.
     */
    fun recordPluginSenderRejection(
        action: String,
        claimedPluginId: String?,
        senderUid: Int,
        senderPackages: Set<String>,
        reasonCode: String = ReasonCodes.PLUGIN_ID_PACKAGE_MISMATCH,
    ) {
        if (!::dataRepo.isInitialized) return // pre-init: drop quietly to avoid crashing tests
        recordRejection(
            context = CepDiagnosticContext(
                source = SourceLayer.PLUGIN,
                reasonCode = reasonCode,
                envelope = CepDiagnosticEnvelope(
                    plugin = CepDiagnosticPlugin(pluginId = claimedPluginId),
                ),
                outcome = CepDiagnosticOutcome(
                    effectiveBefore = latestAppliedHash,
                    effectiveAfter = latestAppliedHash,
                    fallbackDecision = CepDiagnosticRecord.FALLBACK_KEPT_PREVIOUS,
                    message = "plugin sender uid=$senderUid pkgs=${senderPackages.joinToString()} " +
                        "claimed pluginId=$claimedPluginId on action=$action",
                )
            )
        )
    }

    sealed class IngestResult {
        data object Accepted : IngestResult()
        data class Dropped(val reasonCode: String) : IngestResult()
    }

    private suspend fun doApplyLocked(ticket: Long, rawPayload: String?): ApplyResult {
        val effectiveBefore = latestAppliedHash
        val result = managedApplyExecutor.execute(
            rawPayload = rawPayload,
            effectiveBefore = effectiveBefore,
            isSuperseded = { ticket != latestQueuedTicket },
            source = SourceLayer.MANAGED_CONFIG
        )
        when (result) {
            is ApplyResult.Applied -> latestAppliedHash = result.effectiveHash
            else -> Unit
        }
        return result
    }

    /**
     * Clear active alerts whose rules were removed by recompose / disable list / reset.
     * Per the design's `Active-alert hygiene` section, removing a rule must:
     *  1. clear the rule's active alert state in [AlertRegistry]
     *  2. emit downstream clear/cancel behavior per channel policy
     *  3. retain shared `WindowStore` topic rings (the new engine adopts them)
     */
    private fun clearAlertsForRemovedRules(removedRules: Set<String>, reason: String) {
        if (removedRules.isEmpty()) return
        var cleared = 0
        for (ruleId in removedRules) {
            val alert = AlertRegistry.get(ruleId) ?: continue
            // Run the same channel-clear path used by CepEngine.checkAndClearAlerts so that
            // TAK retract / local UI dismiss / SMS no-retract behavior is consistent.
            supportScope.launch {
                runCatching {
                    if (alert.priority == 1) {
                        dataRepo.clearPhysioAndAutoGenAlertRequestUid(alert.uid)
                    } else {
                        dataRepo.removePhysioAndAutoGenAlertByUid(alert.uid)
                    }
                    AlertRegistry.clear(ruleId)
                    dataRepo.addAlertClearedEventCep(alert.uid)
                }
            }
            cleared++
        }
        android.util.Log.i(TAG, "Cleared $cleared alerts for removed rules: reason=$reason")
    }

    private suspend fun evictExpiredPluginOverlays() {
        val evicted = pluginRegistry.evictExpired()
        if (evicted.isNotEmpty()) {
            android.util.Log.i(TAG, "Evicted expired plugin overlays: ${evicted.joinToString()}")
        }
    }

    private fun recordRejection(context: CepDiagnosticContext) {
        val record = CepDiagnosticRecord(
            timestamp = System.currentTimeMillis(),
            sourceLayer = context.source,
            reasonCode = context.reasonCode,
            schemaVersion = context.envelope.schemaVersion,
            payloadHash = context.envelope.payloadHash,
            effectiveHashBefore = context.outcome.effectiveBefore,
            effectiveHashAfter = context.outcome.effectiveAfter,
            fallbackDecision = context.outcome.fallbackDecision,
            pluginId = context.envelope.plugin.pluginId,
            pluginVersion = context.envelope.plugin.pluginVersion,
            tenant = context.envelope.tenant,
            issuer = context.envelope.issuer,
            message = context.outcome.message
        )
        diagnosticSink?.record(record)
    }

    private fun recordInfo(context: CepDiagnosticContext) {
        val record = CepDiagnosticRecord(
            timestamp = System.currentTimeMillis(),
            sourceLayer = context.source,
            reasonCode = context.reasonCode,
            schemaVersion = context.envelope.schemaVersion,
            payloadHash = context.envelope.payloadHash,
            effectiveHashBefore = context.outcome.effectiveBefore,
            effectiveHashAfter = context.outcome.effectiveAfter,
            fallbackDecision = null,
            pluginId = context.envelope.plugin.pluginId,
            pluginVersion = context.envelope.plugin.pluginVersion,
            tenant = context.envelope.tenant,
            issuer = context.envelope.issuer,
            message = context.outcome.message
        )
        diagnosticSink?.record(record)
    }

    /** Apply outcome surfaced to callers (eg. WorkManager worker). */
    sealed class ApplyResult {
        data class Applied(val effectiveHash: String, val atMs: Long) : ApplyResult()
        data object NoOp : ApplyResult()
        data class Rejected(val reasonCode: String, val message: String) : ApplyResult()
        data object Superseded : ApplyResult()
    }
}

/**
 * Persistent diagnostic ring backed by [DataRepository]. Maintains a hot in-memory copy plus
 * a bounded persisted JSON document; the persisted form survives restart per
 * [Update v1.1 — U7].
 */
private class PersistentDiagnosticSink(
    private val repo: DataRepository,
    private val scope: CoroutineScope,
    private val capacity: Int = 64
) : CepDiagnosticSink {

    private val ring = ArrayDeque<CepDiagnosticRecord>(capacity)
    private val mutex = Mutex()

    init {
        // Hydrate hot cache from disk once, off-thread.
        scope.launch {
            val raw = runCatching { repo.getCepDiagnosticsOnce() }.getOrNull()
            val records = DiagnosticsCodec.decode(raw)
            mutex.withLock {
                ring.clear()
                for (r in records.takeLast(capacity)) ring.addLast(r)
            }
        }
    }

    override fun record(record: CepDiagnosticRecord) {
        scope.launch {
            mutex.withLock {
                if (ring.size >= capacity) ring.removeFirst()
                ring.addLast(record)
                runCatching { repo.storeCepDiagnostics(DiagnosticsCodec.encode(ring.toList())) }
            }
        }
    }

    override fun recent(): List<CepDiagnosticRecord> {
        // Best-effort snapshot. Fine for diagnostics surfaces.
        return ring.toList()
    }
}
