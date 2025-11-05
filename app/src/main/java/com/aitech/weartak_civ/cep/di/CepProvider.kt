package com.aitech.weartak_civ.cep.di

import android.content.Context
import com.aitech.weartak_civ.cep.engine.CepEngine
import com.aitech.weartak_civ.cep.engine.ProfileStore
import com.aitech.weartak_civ.cep.engine.RuleCompiler
import com.aitech.weartak_civ.cep.engine.action.CepActionDispatcher
import com.aitech.weartak_civ.cep.engine.action.LogActionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
                handlers = listOf(LogActionHandler(appScope = scope))
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
        return CepEngine(context.applicationContext, scope, config)
    }
}


