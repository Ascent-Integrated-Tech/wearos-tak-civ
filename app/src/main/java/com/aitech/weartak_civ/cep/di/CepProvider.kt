package com.aitech.weartak_civ.cep.di

import android.content.Context
import com.aitech.weartak_civ.WearTakApplication
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.ProfileStore
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.metrics.HrRestingMetricAdapter
import com.aitech.weartak_civ.cep.engine.metrics.HrvRmssdMetricAdapter
import com.aitech.weartak_civ.cep.engine.action.CepActionDispatcher
import com.aitech.weartak_civ.cep.engine.action.LogActionHandler
import com.aitech.weartak_civ.cep.engine.action.WarnActionHandler
import com.aitech.weartak_civ.cep.engine.action.WarnLogActionHandler
import com.aitech.weartak_civ.data.DataRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

object CepProvider {
    private lateinit var appCtx: Context
    fun init(context: Context) { appCtx = context.applicationContext }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile private var engine: CepEngine? = null
    @Volatile private var dispatcher: CepActionDispatcher? = null
    @Volatile private var profileStore: ProfileStore? = null

    fun getOrCreateEngine(context: Context): CepEngine {
        return engine ?: synchronized(this) {
            engine ?: buildEngine(context).also { engine = it }
        }
    }

    fun getActionDispatcher(): CepActionDispatcher {
        return dispatcher ?: synchronized(this) {
            dispatcher ?: CepActionDispatcher(
                scope = scope,
                handlers = listOf(
                LogActionHandler(appScope = scope),
                WarnActionHandler(appScope = scope),
                WarnLogActionHandler(appScope = scope)
            )
            ).also { dispatcher = it }
        }
    }

    fun getProfile(context: Context = appCtx): ProfileStore {
        return profileStore ?: synchronized(this) {
            profileStore ?: ProfileStore(context.applicationContext).also { profileStore = it }
        }
    }

    private fun buildEngine(context: Context): CepEngine {
        val config = RuleCompiler.loadFromRaw(context)

        //val dataRepo = DataRepository(context.applicationContext)

        val dataRepo = (context as WearTakApplication).repo

        val yearOfBirth = kotlinx.coroutines.runBlocking {
            dataRepo.getYearOfBirth.first()
        }

        val hrvConfig = runBlocking { dataRepo.getHrvCepConfigOnce() }

        return CepEngine(
            context.applicationContext,
            scope,
            config,
            yearOfBirth = yearOfBirth,
            dataRepository = dataRepo,
            metricAdaptersOverride = listOf(
                HrRestingMetricAdapter(),
                HrvRmssdMetricAdapter(hrvConfig)
            )
        )
    }
}


