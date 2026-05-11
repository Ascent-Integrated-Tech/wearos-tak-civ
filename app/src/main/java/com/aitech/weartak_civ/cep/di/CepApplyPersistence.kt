package com.aitech.weartak_civ.cep.di

import com.aitech.weartak_civ.data.DataRepository

/**
 * Persistence seam for [CepManagedApplyExecutor] so apply orchestration can be unit-tested
 * without a full [DataRepository].
 */
internal interface CepApplyPersistence {
    suspend fun storeCepConfigurationRawJson(raw: String)
    suspend fun getCepSustainOverridesOnce(): DataRepository.CepSustainOverrides
    suspend fun clearCepSustainOverridesStorage()
    suspend fun storeCepSustainOverridesDual(main: Map<String, Long>, warn: Map<String, Long>)
    suspend fun storeCepConfigurationApplied(hash: String, atMs: Long)
    suspend fun shouldConsumeCepResetEpoch(epoch: Long): Boolean
    suspend fun advanceCepResetEpochMirror(epoch: Long): Boolean
}

internal class RepoCepApplyPersistence(private val repo: DataRepository) : CepApplyPersistence {
    override suspend fun storeCepConfigurationRawJson(raw: String) =
        repo.storeCepConfigurationRawJson(raw)

    override suspend fun getCepSustainOverridesOnce(): DataRepository.CepSustainOverrides =
        repo.getCepSustainOverridesOnce()

    override suspend fun clearCepSustainOverridesStorage() =
        repo.clearCepSustainOverridesStorage()

    override suspend fun storeCepSustainOverridesDual(main: Map<String, Long>, warn: Map<String, Long>) =
        repo.storeCepSustainOverridesDual(main, warn)

    override suspend fun storeCepConfigurationApplied(hash: String, atMs: Long) =
        repo.storeCepConfigurationApplied(hash, atMs)

    override suspend fun shouldConsumeCepResetEpoch(epoch: Long): Boolean =
        repo.shouldConsumeCepResetEpoch(epoch)

    override suspend fun advanceCepResetEpochMirror(epoch: Long): Boolean =
        repo.advanceCepResetEpochMirror(epoch)
}
