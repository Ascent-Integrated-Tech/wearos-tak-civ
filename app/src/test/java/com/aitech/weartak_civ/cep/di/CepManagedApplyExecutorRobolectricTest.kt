package com.aitech.weartak_civ.cep.di

import android.app.Application
import com.aitech.weartak_civ.cep.engine.CepConfig
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.config.CepConfigRegistry
import com.aitech.weartak_civ.cep.engine.config.SourceLayer
import com.aitech.weartak_civ.cep.engine.lifecycle.CepEngineHolder
import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.Topics
import com.aitech.weartak_civ.cep.model.payloads.HeartRatePayload
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class CepManagedApplyExecutorRobolectricTest {

    private class RecordingPersistence : CepApplyPersistence {
        val rawWrites = mutableListOf<String>()
        var appliedWrites = 0
        var lastAppliedHash: String? = null
        var mirrorEpoch: Long = 0L
        var sustainClearCount = 0

        override suspend fun storeCepConfigurationRawJson(raw: String) {
            rawWrites.add(raw)
        }

        override suspend fun getCepSustainOverridesOnce(): DataRepository.CepSustainOverrides =
            DataRepository.CepSustainOverrides(mapOf("rule_x" to 42L), emptyMap())

        override suspend fun clearCepSustainOverridesStorage() {
            sustainClearCount++
        }

        override suspend fun storeCepSustainOverridesDual(
            main: Map<String, Long>,
            warn: Map<String, Long>
        ) = Unit

        override suspend fun storeCepConfigurationApplied(hash: String, atMs: Long) {
            appliedWrites++
            lastAppliedHash = hash
        }

        override suspend fun shouldConsumeCepResetEpoch(epoch: Long): Boolean = epoch > mirrorEpoch

        override suspend fun advanceCepResetEpochMirror(epoch: Long): Boolean {
            if (epoch > mirrorEpoch) {
                mirrorEpoch = epoch
                return true
            }
            return false
        }
    }

    private object NoopDiag : CepApplyDiagnostics {
        override fun recordRejection(
            source: SourceLayer,
            reasonCode: String,
            schemaVersion: Int?,
            payloadHash: String?,
            effectiveBefore: String?,
            effectiveAfter: String?,
            fallbackDecision: String?,
            tenant: String?, issuer: String?,
            message: String?
        ) = Unit

        override fun recordInfo(
            source: SourceLayer,
            reasonCode: String,
            schemaVersion: Int?,
            payloadHash: String?,
            effectiveBefore: String?,
            effectiveAfter: String?,
            tenant: String?, issuer: String?
        ) = Unit
    }

    private fun overlayJson(cepResetEpoch: Long): String = """
        {"schemaVersion":1,"cepResetEpoch":$cepResetEpoch,"rulesMode":"MERGE","featuresMode":"MERGE",
        "clearCepRules":false,"clearCepFeatures":false,"metadata":{},
        "disabledRuleIds":[],"disabledFeatureIds":[],
        "features":[],"rules":[]}
    """.trimIndent()

    private fun mockRepo(): DataRepository {
        val repo = mockk<DataRepository>(relaxed = true)
        every { repo.getYearOfBirth } returns flowOf(1990)
        every { repo.getRestingHeartRate } returns flowOf(60)
        every { repo.getRestingHrHighAlertBpmThreshold } returns flowOf(120)
        every { repo.getRestingHrLowAlertBpmThreshold } returns flowOf(50)
        every { repo.getExertionThresholdWarning } returns flowOf(50)
        every { repo.getExertionThresholdAlert } returns flowOf(80)
        every { repo.getHighAtmPressureAlertThreshold } returns flowOf(950)
        every { repo.getLowAtmPressureAlertThreshold } returns flowOf(100)
        every { repo.getEnableImmersionAlerts } returns flowOf(true)
        every { repo.getEnableLowAtmPressureAlert } returns flowOf(true)
        every { repo.getEnableHighAtmPressureAlert } returns flowOf(true)
        coEvery { repo.getCepSustainOverridesOnce() } returns DataRepository.CepSustainOverrides(
            mapOf("rule_x" to 42L),
            emptyMap()
        )
        return repo
    }

    private fun newExecutor(
        app: Application,
        persistence: CepApplyPersistence,
        holder: CepEngineHolder,
    ): CepManagedApplyExecutor {
        val builtIn = CepConfig(1, features = emptyList(), rules = emptyList(), ruleEvalIntervalMs = 1000)
        val cadence = RuleCompiler.EngineCadence(1, 1000)
        val registry = CepConfigRegistry(builtIn = builtIn, defaultCadence = cadence)
        val repo = mockRepo()
        return CepManagedApplyExecutor(
            registry = registry,
            persistence = persistence,
            holder = holder,
            appCtx = app,
            buildEngine = { ctx, cfg ->
                CepEngine(
                    appContext = ctx.applicationContext,
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
                    config = cfg,
                    yearOfBirth = 1990,
                    dataRepository = repo
                )
            },
            diagnostics = NoopDiag,
            clearAlertsForRemovedRules = { _, _ -> }
        )
    }

    @Test
    fun resetWithUnchangedDefinitionalHashStillSwapsAdvancesMirrorAndClearsSustain() {
        runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val persistence = RecordingPersistence()
        val holder = CepEngineHolder()
        val ex = newExecutor(app, persistence, holder)

        val r1 = ex.execute(1L, overlayJson(0L), effectiveBefore = "", isSuperseded = { false })
        assertTrue(r1 is CepProvider.ApplyResult.Applied)
        val h1 = (r1 as CepProvider.ApplyResult.Applied).effectiveHash

        val eng1 = holder.current()!!
        eng1.ingestForWindowTesting(
            CepEvent(Topics.HEART_RATE, HeartRatePayload(77.0), ts = 12_000L)
        )
        assertEquals(77.0, eng1.peekNumericRingLast(Topics.HEART_RATE)!!, 0.01)

        val r2 = ex.execute(2L, overlayJson(1L), effectiveBefore = h1, isSuperseded = { false })
        assertTrue(r2 is CepProvider.ApplyResult.Applied)
        assertEquals(h1, (r2 as CepProvider.ApplyResult.Applied).effectiveHash)
        assertNotSame(eng1, holder.current())
        assertEquals(77.0, holder.current()!!.peekNumericRingLast(Topics.HEART_RATE)!!, 0.01)
        assertEquals(1L, persistence.mirrorEpoch)
        assertTrue(persistence.sustainClearCount >= 1)
        assertTrue(persistence.appliedWrites >= 2)

        holder.current()?.cancel()
        }
    }

    @Test
    fun definitionalNoOpWithoutResetSkipsSwapAndDoesNotAdvanceMirror() {
        runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val persistence = RecordingPersistence()
        val holder = CepEngineHolder()
        val ex = newExecutor(app, persistence, holder)

        val r1 = ex.execute(1L, overlayJson(0L), effectiveBefore = "", isSuperseded = { false })
        val h1 = (r1 as CepProvider.ApplyResult.Applied).effectiveHash
        val eng1 = holder.current()!!

        val r2 = ex.execute(2L, overlayJson(0L), effectiveBefore = h1, isSuperseded = { false })
        assertTrue(r2 is CepProvider.ApplyResult.NoOp)
        assertEquals(eng1, holder.current())
        assertEquals(0L, persistence.mirrorEpoch)

        holder.current()?.cancel()
        }
    }

    @Test
    fun parseRejectionLeavesMirrorAndAppliedWritesUnchanged() {
        runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val persistence = RecordingPersistence()
        val holder = CepEngineHolder()
        val ex = newExecutor(app, persistence, holder)

        val r = ex.execute(1L, """{"schemaVersion":2,"cepResetEpoch":5}""", "", { false })
        assertTrue(r is CepProvider.ApplyResult.Rejected)
        assertEquals(0L, persistence.mirrorEpoch)
        assertEquals(0, persistence.appliedWrites)
        assertTrue(holder.current() == null)
        }
    }

    @Test
    fun composeRejectionWithPendingResetDoesNotAdvanceMirrorOrSwap() {
        runBlocking {
            val app = RuntimeEnvironment.getApplication()
            val persistence = RecordingPersistence()
            val holder = CepEngineHolder()
            val ex = newExecutor(app, persistence, holder)
            val bad = """
                {"schemaVersion":1,"cepResetEpoch":1,"rulesMode":"MERGE","featuresMode":"MERGE",
                "clearCepRules":false,"clearCepFeatures":false,"metadata":{},
                "engine":{"window_tick_ms":1,"rule_eval_interval_ms":1},
                "disabledRuleIds":[],"disabledFeatureIds":[],
                "features":[],"rules":[]}
            """.trimIndent()
            val r = ex.execute(1L, bad, "", { false })
            assertTrue(r is CepProvider.ApplyResult.Rejected)
            assertEquals(0L, persistence.mirrorEpoch)
            assertEquals(0, persistence.appliedWrites)
            assertEquals(0, persistence.sustainClearCount)
            assertTrue(holder.current() == null)
        }
    }
}
