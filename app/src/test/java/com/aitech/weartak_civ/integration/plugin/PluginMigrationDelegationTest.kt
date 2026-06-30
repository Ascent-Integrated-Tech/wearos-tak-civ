package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.work.Data
import androidx.work.WorkerParameters
import com.aitech.weartak_civ.cep.di.CepProvider
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * WEAR-517 migration bridge: legacy entry points must delegate into [PluginIngressPort]
 * (or [PluginIngressLocator] test override) without duplicating business rules.
 */
@RunWith(RobolectricTestRunner::class)
class PluginMigrationDelegationTest {

    @Before
    fun setup() {
        PluginIngressLocator.clearTestOverride()
    }

    @After
    fun tearDown() {
        PluginIngressLocator.clearTestOverride()
    }

    @Test
    fun pluginIngestReceiver_delegatesToIngress() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        PluginIngressLocator.testOverride = ingress
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        val intent = Intent(PluginIngestReceiver.ACTION_PLUGIN_SAMPLE)
        PluginIngestReceiver().onReceive(ctx, intent)
        verify(exactly = 1) { ingress.onLegacyPluginSampleIntent(ctx, intent) }
    }

    @Test
    fun pluginOverlayReceiver_delegatesToIngress() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        PluginIngressLocator.testOverride = ingress
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        val intent = Intent(PluginOverlayReceiver.ACTION_PLUGIN_OVERLAY)
        PluginOverlayReceiver().onReceive(ctx, intent)
        verify(exactly = 1) { ingress.onLegacyPluginOverlayIntent(ctx, intent) }
    }

    @Test
    fun pluginPackageRemovedReceiver_delegatesToIngress() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        PluginIngressLocator.testOverride = ingress
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.applicationContext } returns ctx
        val intent = Intent(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply {
            data = Uri.parse("package:com.removed.plugin")
        }
        PluginPackageRemovedReceiver().onReceive(ctx, intent)
        verify(exactly = 1) { ingress.onPackageFullyRemoved("com.removed.plugin") }
    }

    @Test
    fun pluginOverlayApplyWorker_callsIngressApplyOverlayFromWorker() {
        val ingress = mockk<PluginIngressPort>(relaxed = true)
        coEvery {
            ingress.applyOverlayFromWorker(any(), any(), any(), any(), any())
        } returns CepProvider.ApplyResult.NoOp
        PluginIngressLocator.testOverride = ingress
        val appCtx = mockk<Context>(relaxed = true)
        every { appCtx.applicationContext } returns appCtx
        val data = Data.Builder()
            .putString(PluginOverlayApplyWorker.KEY_MODE, PluginOverlayApplyWorker.MODE_UPSERT)
            .putString(PluginOverlayApplyWorker.KEY_RAW_OVERLAY, "{}")
            .putString(PluginOverlayApplyWorker.KEY_PLUGIN_ID, "com.p")
            .putInt(PluginOverlayApplyWorker.KEY_SENDER_UID, 1)
            .putStringArray(PluginOverlayApplyWorker.KEY_SENDER_PACKAGES, arrayOf("com.p"))
            .build()
        val params = mockk<WorkerParameters>(relaxed = true)
        every { params.inputData } returns data
        runBlocking {
            PluginOverlayApplyWorker(appCtx, params).doWork()
        }
        coVerify(exactly = 1) {
            ingress.applyOverlayFromWorker(
                mode = PluginOverlayApplyWorker.MODE_UPSERT,
                raw = "{}",
                pluginId = "com.p",
                senderUid = 1,
                senderPackages = setOf("com.p"),
            )
        }
    }
}
