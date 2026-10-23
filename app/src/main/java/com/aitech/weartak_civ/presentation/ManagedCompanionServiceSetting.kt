package com.aitech.weartak_civ.presentation

internal data class ManagedCompanionServiceSetting(
    val enabled: Boolean,
    val persistManagedOverride: Boolean
)

internal fun resolveManagedCompanionServiceSetting(
    managedOverride: Boolean?,
    persistedValue: Boolean
): ManagedCompanionServiceSetting = ManagedCompanionServiceSetting(
    enabled = managedOverride ?: persistedValue,
    persistManagedOverride = managedOverride != null
)
