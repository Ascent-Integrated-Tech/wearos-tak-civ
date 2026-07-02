package com.aitech.weartak_civ.cep.engine.action

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class CepActionDispatcher(
    private val scope: CoroutineScope,
    handlers: List<ActionHandler>,
    private val throttleMsBySeverity: Map<Severity, Long> = mapOf(
        Severity.INFO to 0L,
        Severity.WARN to 5_000L,
        Severity.ALERT to 10_000L,
        Severity.EMERGENCY to 15_000L
    )
) {
    private val handlerByType = handlers.associateBy { it.type }.toMutableMap()

    private val queue = MutableSharedFlow<TriggerContext>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    private val lastFire = ConcurrentHashMap<String, Long>()

    init {
        scope.launch(Dispatchers.Default) {
            queue.collect { ctx ->
                if (shouldSkip(ctx)) return@collect
                handlerByType[ctx.action.type]?.handle(ctx)
                remember(ctx)
            }
        }
    }

    suspend fun dispatch(ctx: TriggerContext) {
        queue.emit(ctx)
    }
    fun register(h: ActionHandler) { handlerByType[h.type] = h }

    private fun key(ctx: TriggerContext) =
        "${ctx.ruleId}|${ctx.action.type}|${ctx.action.severity}|${ctx.action.tags.hashCode()}"

    private fun shouldSkip(ctx: TriggerContext): Boolean {
        val now = System.currentTimeMillis()
        val k = key(ctx)
        val last = lastFire[k] ?: return false
        val thr = throttleMsBySeverity[ctx.action.severity] ?: 0L
        return (now - last) < thr
    }
    private fun remember(ctx: TriggerContext) { lastFire[key(ctx)] = System.currentTimeMillis() }

    fun resetForTest() {
        lastFire.clear()
    }
}

