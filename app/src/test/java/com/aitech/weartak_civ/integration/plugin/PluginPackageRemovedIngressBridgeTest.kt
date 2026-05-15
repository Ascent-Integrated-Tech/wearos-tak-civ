package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import io.mockk.verify
import io.mockk.mockk
import io.mockk.every
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PluginPackageRemovedIngressBridgeTest {

    @Before
    fun setup() {
        PluginIngressLocator.clearTestOverride()
    }

    @After
    fun tearDown() {
        PluginIngressLocator.clearTestOverride()
    }

    @Test
    fun packageRemoved_withReplacingTrue_doesNotCallIngress() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        PluginIngressLocator.testOverride = ingress
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            data = Uri.parse("package:com.plugin")
            putExtra(Intent.EXTRA_REPLACING, true)
        }
        PluginPackageRemovedReceiver().onReceive(ctx, intent)
        verify(exactly = 0) { ingress.onPackageFullyRemoved(any()) }
    }

    @Test
    fun packageFullyRemoved_withValidUri_callsIngress() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        PluginIngressLocator.testOverride = ingress
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        val intent = Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
            data = Uri.parse("package:com.evict.me")
        }
        PluginPackageRemovedReceiver().onReceive(ctx, intent)
        verify(exactly = 1) { ingress.onPackageFullyRemoved("com.evict.me") }
    }

    @Test
    fun missingPackageData_doesNotCallIngress() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        PluginIngressLocator.testOverride = ingress
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        PluginPackageRemovedReceiver().onReceive(ctx, Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED))
        verify(exactly = 0) { ingress.onPackageFullyRemoved(any()) }
    }

    @Test
    fun unknownAction_doesNotCallIngress() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        PluginIngressLocator.testOverride = ingress
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        PluginPackageRemovedReceiver().onReceive(ctx, Intent("other.action"))
        verify(exactly = 0) { ingress.onPackageFullyRemoved(any()) }
    }
}
