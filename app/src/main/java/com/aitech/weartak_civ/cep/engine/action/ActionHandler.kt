package com.aitech.weartak_civ.cep.engine.action

interface ActionHandler {
    val type: String
    suspend fun handle(ctx: TriggerContext)
}


