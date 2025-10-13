package com.aitech.weartak_civ.cep.bus

import com.aitech.weartak_civ.cep.model.CepEvent
import com.aitech.weartak_civ.cep.model.CepPayload
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow

object CepBus {
    // Hot stream (producers will emit later from services)
    val events = MutableSharedFlow<CepEvent<out CepPayload>>(
        replay = 0,
        extraBufferCapacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
}


