/*
 * Copyright (C) 2026, Ascent Integrated Tech. All rights reserved.
 *
 * The copyright to the computer software herein is the property of Ascent Integrated Tech.
 * This software may not be used and/or copied without the explicit written permission
 * of Ascent Integrated Tech, and only in accordance with the terms and conditions stipulated
 * in the license agreement and/or contract under which the software has been supplied.
 */

package com.aitech.weartak_civ.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class RawPpgRepository {

    data class RawPpgFrame(
        val timestampMs: Long,
        val sampleRateHz: Int,
        val wavelength: String,
        val samples: IntArray
    )

    private val _rawPpgFrames = MutableSharedFlow<RawPpgFrame>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val rawPpgFrames: SharedFlow<RawPpgFrame> = _rawPpgFrames

    fun tryEmit(frame: RawPpgFrame): Boolean = _rawPpgFrames.tryEmit(frame)
}
