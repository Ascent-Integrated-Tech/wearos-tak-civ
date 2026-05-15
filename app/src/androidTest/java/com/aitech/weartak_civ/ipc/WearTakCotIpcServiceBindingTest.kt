package com.aitech.weartak_civ.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.weartak.ipcservice.IWearTakAppCotListener
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class WearTakCotIpcServiceBindingTest {
    @Test
    fun bindsAndAcceptsIcdSamplePayload() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val binderRef = AtomicReference<IWearTakAppCotListener?>()
        val connected = CountDownLatch(1)

        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                binderRef.set(IWearTakAppCotListener.Stub.asInterface(service))
                connected.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) {
                binderRef.set(null)
            }
        }

        val intent = Intent(AtosIpcConstants.ACTION_START).setPackage(context.packageName)
        val bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        assertTrue("Expected bindService() to succeed", bound)
        assertTrue("Expected service connection", connected.await(5, TimeUnit.SECONDS))

        val service = binderRef.get()
        assertNotNull(service)

        val sampleXml = """
            <event version="2.0" uid="atos-plugin-4" type="a-f-G-U-C-I-I"
                time="2021-10-25T15:52:40.61Z"
                start="2021-10-25T15:52:40.61Z"
                stale="2021-10-25T15:57:40.61Z"
                how="m-g">
                <point lat="35.7985556" lon="-78.6577434"
                    hae="110.750658846955"
                    ce="9999999" le="9999999" />
                <detail>
                    <contact callsign="hodor" />
                    <track course="22" speed="0.025" vspeed="1.0" />
                    <__atos tag_type="Vehicle" color="Maroon" tag_uid="4"
                        commandlink="CL-12345" temp_c="5" voltage="3.6" alarm="0">
                        <attributes Vehicle_Type="tacoma" />
                    </__atos>
                    <link relation="p-p" uid="android-device" type="a-f-g"/>
                    <archive />
                </detail>
            </event>
        """.trimIndent()

        service?.receiveCotFromPlugin(sampleXml)
        service?.broadcastCot(sampleXml)

        context.unbindService(connection)
    }
}
