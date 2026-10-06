package com.aitech.weartak_civ

import com.aitech.weartak_civ.Utils.SettingsUtils.CategoryRolesEntry
import com.aitech.weartak_civ.Utils.SettingsUtils.selectableRoleCategories
import org.junit.Assert.assertEquals
import org.junit.Test

class RoleCategoriesTest {
    @Test
    fun removesFesAndPreservesOtherCategoriesAndRoles() {
        val mil = CategoryRolesEntry("MIL", listOf("Medic", "Team Member"))
        val leo = CategoryRolesEntry("LEO", listOf("Command Post", "TOC"))
        val custom = CategoryRolesEntry("Custom", listOf("Custom Role"))
        val fes = CategoryRolesEntry("FES", listOf("Firefighter"))

        assertEquals(
            listOf(mil, leo, custom),
            selectableRoleCategories(listOf(mil, fes, leo, custom, fes))
        )
    }

    @Test
    fun returnsEmptyForFesOnlySoInitializationCanLoadDefaults() {
        assertEquals(
            emptyList<CategoryRolesEntry>(),
            selectableRoleCategories(listOf(CategoryRolesEntry("FES", listOf("Firefighter"))))
        )
    }

    @Test
    fun preservesEmptyListSoInitializationCanLoadDefaults() {
        assertEquals(emptyList<CategoryRolesEntry>(), selectableRoleCategories(emptyList()))
    }
}
