package com.aitech.weartak_civ.service

internal class PpgSampleBuffer {
    private val samples = mutableListOf<Float>()

    @Synchronized fun add(sample: Float) { samples.add(sample) }
    @Synchronized fun clear() { samples.clear() }
    @Synchronized fun drain(): List<Float> = samples.toList().also { samples.clear() }
}
