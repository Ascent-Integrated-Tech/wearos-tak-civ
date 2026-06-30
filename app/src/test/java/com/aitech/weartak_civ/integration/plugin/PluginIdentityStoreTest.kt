package com.aitech.weartak_civ.integration.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginIdentityStoreTest {

    @Test
    fun resolvesDeclaredPluginIdAndKeepsItAcrossDisconnectedRefreshes() {
        val store = PluginIdentityStore()
        store.rememberDeclaredPluginId("com.example.plugin", "com.example.plugin.real")

        assertEquals("com.example.plugin", store.resolvePackageName("com.example.plugin.real"))

        store.rememberPackage("com.example.plugin")

        assertEquals("com.example.plugin.real", store.lastKnownPluginId("com.example.plugin"))
        assertEquals("com.example.plugin", store.resolvePackageName("com.example.plugin.real"))
    }

    @Test
    fun hideAndUnhideOperateOnPackageNameNotPluginId() {
        val store = PluginIdentityStore()
        store.rememberDeclaredPluginId("com.example.plugin", "com.example.plugin.real")

        assertTrue(store.setHidden("com.example.plugin", true))
        assertTrue(store.visiblePackageNames(listOf("com.example.plugin")).isEmpty())

        assertTrue(store.setHidden("com.example.plugin", false))
        assertEquals(listOf("com.example.plugin"), store.visiblePackageNames(listOf("com.example.plugin")))
    }

    @Test
    fun removePackageEvictsDeclaredPluginIdResolution() {
        val store = PluginIdentityStore()
        store.rememberDeclaredPluginId("com.example.plugin", "com.example.plugin.real")

        assertTrue(store.removePackage("com.example.plugin"))
        assertNull(store.resolvePackageName("com.example.plugin.real"))
    }
}
