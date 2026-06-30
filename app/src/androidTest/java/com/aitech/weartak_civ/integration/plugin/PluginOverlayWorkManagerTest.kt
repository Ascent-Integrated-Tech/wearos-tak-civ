package com.aitech.weartak_civ.integration.plugin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Configuration
import androidx.work.testing.WorkManagerTestInitHelper
import com.aitech.weartak_civ.integration.plugin.PluginOverlayApplyWorker.Companion.KEY_MODE
import com.aitech.weartak_civ.integration.plugin.PluginOverlayApplyWorker.Companion.KEY_PLUGIN_ID
import com.aitech.weartak_civ.integration.plugin.PluginOverlayApplyWorker.Companion.KEY_RAW_OVERLAY
import com.aitech.weartak_civ.integration.plugin.PluginOverlayApplyWorker.Companion.KEY_SENDER_PACKAGES
import com.aitech.weartak_civ.integration.plugin.PluginOverlayApplyWorker.Companion.KEY_SENDER_UID
import com.aitech.weartak_civ.integration.plugin.PluginOverlayApplyWorker.Companion.MODE_UPSERT
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginOverlayWorkManagerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private var lastWorkNames: List<String> = emptyList()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        workManager = runCatching { WorkManager.getInstance(context) }.getOrElse {
            WorkManagerTestInitHelper.initializeTestWorkManager(
                context,
                Configuration.Builder().setMinimumLoggingLevel(android.util.Log.DEBUG).build()
            )
            WorkManager.getInstance(context)
        }
    }

    @Test
    fun distinctPluginIdsKeepDistinctUniqueWorkEntries() {
        val workA = workFor("com.acme.plugin.a")
        val workB = workFor("com.acme.plugin.b")
        val nameA = PluginOverlayReceiver.uniqueWorkNameFor("com.acme.plugin.a")
        val nameB = PluginOverlayReceiver.uniqueWorkNameFor("com.acme.plugin.b")
        lastWorkNames = listOf(nameA, nameB)

        workManager.enqueueUniqueWork(nameA, ExistingWorkPolicy.REPLACE, workA)
        workManager.enqueueUniqueWork(nameB, ExistingWorkPolicy.REPLACE, workB)

        val infosA = workManager.getWorkInfosForUniqueWork(nameA).get(10, TimeUnit.SECONDS)
        val infosB = workManager.getWorkInfosForUniqueWork(nameB).get(10, TimeUnit.SECONDS)

        assertTrue(infosA.isNotEmpty())
        assertTrue(infosB.isNotEmpty())
    }

    @After
    fun tearDown() {
        lastWorkNames.forEach { workManager.cancelUniqueWork(it) }
        lastWorkNames = emptyList()
    }

    private fun workFor(pluginId: String) = OneTimeWorkRequestBuilder<PluginOverlayApplyWorker>()
        .setInitialDelay(1, TimeUnit.HOURS)
        .setInputData(
            androidx.work.workDataOf(
                KEY_MODE to MODE_UPSERT,
                KEY_RAW_OVERLAY to """
                    {
                      "schemaVersion":1,
                      "pluginId":"$pluginId",
                      "leaseSec":600,
                      "features":[],
                      "rules":[]
                    }
                """.trimIndent(),
                KEY_PLUGIN_ID to pluginId,
                KEY_SENDER_UID to 11050,
                KEY_SENDER_PACKAGES to arrayOf(pluginId),
            )
        )
        .build()
}
