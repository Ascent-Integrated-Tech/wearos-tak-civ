package com.aitech.weartak_civ.service

import java.io.FilterInputStream
import java.io.InputStream

internal class ProcessingInputStream(input: InputStream) : FilterInputStream(input) {
    override fun read(): Int = AppDiagnostics.measureProcessing(
        ProcessingStage.TCP_READ,
        bytes = { if (it >= 0) 1L else 0L },
    ) { `in`.read() }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        AppDiagnostics.measureProcessing(
            ProcessingStage.TCP_READ,
            bytes = { it.coerceAtLeast(0).toLong() },
        ) { `in`.read(buffer, offset, length) }
}
