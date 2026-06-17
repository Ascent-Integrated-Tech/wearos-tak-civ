/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class CrmRepository {

    enum class CrmBand {
        RED,
        YELLOW,
        GREEN
    }

    enum class CrmSource {
        NONE,
        HEURISTIC,
        MODEL
    }

    data class CrmState(
        val score: Int? = null,
        val band: CrmBand? = null,
        val updatedAtMs: Long? = null,
        val source: CrmSource = CrmSource.NONE,
        val isProvisional: Boolean = true,
        val signalReady: Boolean = false,
        val status: String = "idle"
    )

    private val _crmState = MutableStateFlow(CrmState())
    val crmState: StateFlow<CrmState> = _crmState.asStateFlow()

    suspend fun updateState(state: CrmState) {
        _crmState.emit(state)
    }
}
