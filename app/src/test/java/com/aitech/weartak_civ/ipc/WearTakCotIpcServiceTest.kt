package com.aitech.weartak_civ.ipc

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import com.weartak.ipcservice.IWearTakAppCotListener
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBinder

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class WearTakCotIpcServiceTest {
    private lateinit var service: WearTakCotIpcService
    private val packages = mockk<PackageManager>()

    @Before fun setup() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getPackageManager(): PackageManager = packages
        }
        service = WearTakCotIpcService()
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java)
            .apply { isAccessible = true }.invoke(service, context)
    }

    @Test fun bindingDoesNotMistakeMainThreadIdentityForClient() {
        ShadowBinder.setCallingUid(123)
        every { packages.getPackagesForUid(123) } returns arrayOf("untrusted.app")
        assertNotNull(service.onBind(Intent()))
    }

    @Test fun actualAtosTransactionCallerIsAccepted() {
        ShadowBinder.setCallingUid(234)
        every { packages.getPackagesForUid(234) } returns arrayOf(AtosIpcConstants.EXPECTED_ATOS_PACKAGE)
        service.enforceCallerAllowed()
    }

    @Test fun everyBinderMethodRejectsUntrustedCallerBeforeReadingPayload() {
        ShadowBinder.setCallingUid(345)
        every { packages.getPackagesForUid(345) } returns arrayOf("untrusted.app")
        val client = IWearTakAppCotListener.Stub.asInterface(service.onBind(Intent()))
        assertThrows(SecurityException::class.java) { client.receiveCotFromPlugin("<event/>") }
        assertThrows(SecurityException::class.java) { client.broadcastCot("<event/>") }
    }

    @Test fun previouslyAllowedBindingCannotAuthorizeAnotherUid() {
        every { packages.getPackagesForUid(234) } returns arrayOf(AtosIpcConstants.EXPECTED_ATOS_PACKAGE)
        every { packages.getPackagesForUid(345) } returns null
        ShadowBinder.setCallingUid(234)
        service.enforceCallerAllowed()
        val client = IWearTakAppCotListener.Stub.asInterface(service.onBind(Intent()))
        ShadowBinder.setCallingUid(345)
        assertThrows(SecurityException::class.java) { client.broadcastCot("<event/>") }
    }

    @Test fun enrollmentNeverEntersPreDispatchXmlLogsOrPluginForwarding() {
        ShadowBinder.setCallingUid(234)
        every { packages.getPackagesForUid(234) } returns arrayOf(AtosIpcConstants.EXPECTED_ATOS_PACKAGE)
        val client = IWearTakAppCotListener.Stub.asInterface(service.onBind(Intent()))
        for (xml in listOf(
            "<event><remarks>#weartakcommand enrollserver Ops,host,8089,u,fake-secret</remarks></event>",
            "<event><remarks>&#35;weartakcommandenrollserver Ops,host,8089,u,fake-secret</remarks></event>",
            "<event><remarks>#weartakcommandenrollserver malformed,fake-secret")) {
            // The Application fixture has no plugin host: forwarding would also fail this transaction.
            client.receiveCotFromPlugin(xml)
            client.broadcastCot(xml)
        }
        assertTrue(org.robolectric.shadows.ShadowLog.getLogs().none {
            it.msg.contains("fake-secret") || it.msg.contains("payload full") || it.msg.contains("payload preview")
        })
    }
}
