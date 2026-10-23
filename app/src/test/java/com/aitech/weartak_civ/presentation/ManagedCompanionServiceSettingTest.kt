package com.aitech.weartak_civ.presentation

import org.junit.Assert.assertEquals
import org.junit.Test

class ManagedCompanionServiceSettingTest {
    @Test fun missingRestrictionUsesCurrentPreferenceWithoutWritingStaleSnapshot() {
        var persisted = false

        persisted = true
        val decision = resolveManagedCompanionServiceSetting(
            managedOverride = null,
            persistedValue = false
        )
        if (decision.persistManagedOverride) persisted = decision.enabled

        assertEquals(false, decision.enabled)
        assertEquals(true, persisted)
    }

    @Test fun explicitManagedFalseOverridesEnabledPreference() {
        assertEquals(
            ManagedCompanionServiceSetting(enabled = false, persistManagedOverride = true),
            resolveManagedCompanionServiceSetting(managedOverride = false, persistedValue = true)
        )
    }

    @Test fun explicitManagedTrueOverridesDisabledPreference() {
        assertEquals(
            ManagedCompanionServiceSetting(enabled = true, persistManagedOverride = true),
            resolveManagedCompanionServiceSetting(managedOverride = true, persistedValue = false)
        )
    }
}
