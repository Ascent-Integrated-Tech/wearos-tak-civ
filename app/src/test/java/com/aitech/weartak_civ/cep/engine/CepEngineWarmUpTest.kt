package com.aitech.weartak_civ.cep.engine

import android.app.Application
import com.aitech.weartak_civ.cep.engine.action.AlertRegistry
import com.aitech.weartak_civ.data.DataRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import io.mockk.verify

@RunWith(RobolectricTestRunner::class)
class CepEngineWarmUpTest {

    @After
    fun tearDown() {
        AlertRegistry.clearAll()
    }

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
        every { repo.getEnableImmersionAlerts } returns flowOf(false)
        every { repo.getEnableLowAtmPressureAlert } returns flowOf(false)
        every { repo.getEnableHighAtmPressureAlert } returns flowOf(false)
        coEvery { repo.getCepSustainOverridesOnce() } returns DataRepository.CepSustainOverrides(
            main = mapOf("rule_x" to 42L),
            warn = emptyMap()
        )
        return repo
    }

    private fun newEngine(repo: DataRepository): CepEngine {
        val app: Application = RuntimeEnvironment.getApplication()
        return CepEngine(
            appContext = app,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
            config = CepConfig(
                tickHz = 1,
                features = emptyList(),
                rules = emptyList(),
                ruleEvalIntervalMs = 1000
            ),
            dataRepository = repo
        )
    }

    @Test
    fun warmUpDoesNotClearGlobalAlertsBeforeAuthorityTransfer() = runBlocking {
        val repo = mockRepo()
        val engine = newEngine(repo)
        AlertRegistry.tryActivate(
            ruleId = "pressure_low_atm",
            priority = 1,
            uid = "uid-1",
            exitConditions = emptyList(),
            exitLogic = "AND",
            exitSustainSec = 0
        )
        AlertRegistry.tryActivate(
            ruleId = "pressure_dunk_detected",
            priority = 1,
            uid = "uid-2",
            exitConditions = emptyList(),
            exitLogic = "AND",
            exitSustainSec = 0
        )

        engine.warmUp()

        assertNotNull(AlertRegistry.get("pressure_low_atm"))
        assertNotNull(AlertRegistry.get("pressure_dunk_detected"))

        engine.applyPressureAlertHygiene()

        assertNull(AlertRegistry.get("pressure_low_atm"))
        assertNull(AlertRegistry.get("pressure_dunk_detected"))
        engine.cancel()
    }

    @Test
    fun startDoesNotReReadWarmUpState() = runBlocking {
        val repo = mockRepo()
        val engine = newEngine(repo)

        engine.warmUp()
        engine.start()

        coVerify(exactly = 1) { repo.getCepSustainOverridesOnce() }
        verify(exactly = 1) { repo.getEnableImmersionAlerts }
        verify(exactly = 1) { repo.getEnableLowAtmPressureAlert }
        verify(exactly = 1) { repo.getEnableHighAtmPressureAlert }
        engine.cancel()
    }
}
