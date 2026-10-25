package com.aitech.weartak_civ.service

import android.Manifest
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.os.SystemClock
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class BondedLocationDiagnosticsTest {
    private fun goodObservation() = LocationDiagnosticSample(
        source = "gps",
        ageMillis = 2_000L,
        horizontalAccuracyMeters = 8f,
        satelliteCount = 12,
        usedSatelliteCount = 8,
        meanCn0DbHz = 29f,
        maxAgcDb = 1.5f,
        satelliteStatusSupported = true,
        measurementsSupported = true,
        providerAvailable = true
    )

    @Test
    fun absentGnssAndRawMeasurementsAreUnknownRatherThanGood() {
        assertEquals(LocationDiagnosticQuality.UNKNOWN, goodObservation()
            .copy(satelliteStatusSupported = false).quality())
        assertEquals(LocationDiagnosticQuality.UNKNOWN, goodObservation()
            .copy(maxAgcDb = null).quality())
        assertEquals(LocationDiagnosticQuality.UNKNOWN, LocationDiagnosticSample().quality())
    }

    @Test
    fun staleInaccurateOrPermissionDeniedObservationsAreConservative() {
        assertEquals(LocationDiagnosticQuality.DEGRADED, goodObservation()
            .copy(ageMillis = 31_000L).quality())
        assertEquals(LocationDiagnosticQuality.DEGRADED, goodObservation()
            .copy(horizontalAccuracyMeters = 150f).quality())
        assertEquals(LocationDiagnosticQuality.UNAVAILABLE, goodObservation()
            .copy(providerAvailable = false, error = "Fine location permission not granted").quality())
    }

    @Test
    fun recoveryRequiresThreeGoodSamplesAndDegradationIsImmediate() {
        val hysteresis = QualityRecoveryHysteresis()
        assertEquals(LocationDiagnosticQuality.DEGRADED, hysteresis.update(LocationDiagnosticQuality.DEGRADED))
        assertEquals(LocationDiagnosticQuality.DEGRADED, hysteresis.update(LocationDiagnosticQuality.GOOD))
        assertEquals(LocationDiagnosticQuality.DEGRADED, hysteresis.update(LocationDiagnosticQuality.GOOD))
        assertEquals(LocationDiagnosticQuality.GOOD, hysteresis.update(LocationDiagnosticQuality.GOOD))
        assertEquals(LocationDiagnosticQuality.DEGRADED, hysteresis.update(LocationDiagnosticQuality.DEGRADED))
    }

    @Test
    fun stoppedDiagnosticSessionRejectsSnapshotsFromItsOldSession() {
        val diagnostics = BondedLocationDiagnostics(RuntimeEnvironment.getApplication())
        val session = LocationDiagnosticsProtocol.newSession()
        diagnostics.start(session)
        assertTrue(diagnostics.watchSnapshot(session).has("payload"))

        diagnostics.stop()

        assertFalse(diagnostics.acceptPhoneSnapshot(JSONObject(), session))
        assertEquals(LocationDiagnosticQuality.UNKNOWN, diagnostics.watch.value.quality())
    }

    @Test
    fun inactiveDiagnosticsDoNotPretendToWaitForSatellitesOrObserveOperationalFixes() {
        val diagnostics = BondedLocationDiagnostics(RuntimeEnvironment.getApplication())
        val inactiveWatch = diagnostics.watch.value
        val inactivePhone = diagnostics.phone.value
        assertEquals("Diagnostics inactive; no active bonded Companion session", inactiveWatch.reason())
        assertEquals(inactiveWatch.reason(), inactivePhone.reason())
        assertNull(inactiveWatch.providerAvailable)
        diagnostics.observeExistingFix(Location("gps").apply {
            accuracy = 5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        assertEquals(inactiveWatch, diagnostics.watch.value)
        assertEquals(inactivePhone, diagnostics.phone.value)
    }

    @Test
    fun fusedFixIsNotAssessedUsingUnrelatedSatelliteMeasurements() {
        val sample = goodObservation().copy(source = "fused")
        assertEquals(LocationDiagnosticQuality.UNKNOWN, sample.quality())
        assertEquals("Fix observed; satellite provenance unavailable for fused", sample.reason())
        assertEquals(2_000L, sample.ageMillis)
        assertEquals(8f, sample.horizontalAccuracyMeters)
    }

    @Test
    fun sessionsClearOldProvenanceAndFenceLateGnssCallbacks() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val diagnostics = BondedLocationDiagnostics(app)
        val firstSession = LocationDiagnosticsProtocol.newSession()
        diagnostics.start(firstSession)
        val callbackField = BondedLocationDiagnostics::class.java
            .getDeclaredField("gnssStatusCallback").apply { isAccessible = true }
        val oldCallback = callbackField.get(diagnostics) as GnssStatus.Callback
        diagnostics.observeExistingFix(Location("fused").apply {
            accuracy = 7f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        assertEquals("fused", diagnostics.watch.value.source)
        oldCallback.onStopped()
        assertEquals("fused", diagnostics.watch.value.source)
        assertEquals(7f, diagnostics.watch.value.horizontalAccuracyMeters)
        assertEquals("GNSS receiver inactive; existing fix retained", diagnostics.watch.value.reason())
        diagnostics.stop()
        val stopped = diagnostics.watch.value
        oldCallback.onStarted()
        oldCallback.onStopped()
        assertEquals(stopped, diagnostics.watch.value)
        assertEquals(stopped.reason(), diagnostics.phone.value.reason())

        diagnostics.start(LocationDiagnosticsProtocol.newSession())
        try {
            val waiting = diagnostics.watch.value
            assertEquals("Waiting for an existing watch fix in this diagnostic session", waiting.reason())
            assertEquals("Waiting for a phone snapshot in this diagnostic session", diagnostics.phone.value.reason())
            assertNull(waiting.source)
            assertNull(waiting.ageMillis)
            oldCallback.onStarted()
            oldCallback.onStopped()
            assertEquals(waiting, diagnostics.watch.value)
        } finally { diagnostics.stop() }
    }

    @Test
    fun diagnosticMessagesAreVersionedSessionBoundedAndPrivacyAllowlisted() {
        val session = LocationDiagnosticsProtocol.newSession()
        val hello = LocationDiagnosticsProtocol.hello(session)
        assertEquals(LocationDiagnosticsProtocol.HELLO,
            LocationDiagnosticsProtocol.parse(hello, LocationDiagnosticsProtocol.HELLO, session)
                ?.getString("msgType"))
        assertNull(LocationDiagnosticsProtocol.parse(hello, LocationDiagnosticsProtocol.HELLO,
            LocationDiagnosticsProtocol.newSession()))
        val stop = LocationDiagnosticsProtocol.stop(session)
        assertEquals(LocationDiagnosticsProtocol.STOP,
            LocationDiagnosticsProtocol.parse(stop, LocationDiagnosticsProtocol.STOP, session)
                ?.getString("msgType"))
        assertNull(LocationDiagnosticsProtocol.parse(stop, LocationDiagnosticsProtocol.STOP,
            LocationDiagnosticsProtocol.newSession()))

        val snapshot = JSONObject()
            .put("msg_type", LocationDiagnosticsProtocol.SNAPSHOT)
            .put("version", LocationDiagnosticsProtocol.VERSION)
            .put("session", session)
            .put("payload", JSONObject().put("source", "gps").put("ageMs", 1_000)
                .put("accuracyM", 5).put("satellites", 8).put("used", 6)
                .put("satelliteStatusSupported", true).put("measurementsSupported", true)
                .put("providerAvailable", true).put("meanCn0DbHz", 25)
                .put("maxAgcDb", 1).put("quality", "GOOD"))
        assertTrue(LocationDiagnosticsProtocol.parse(snapshot, LocationDiagnosticsProtocol.SNAPSHOT, session) != null)
        snapshot.getJSONObject("payload").put("lat", 47.1)
        assertNull(LocationDiagnosticsProtocol.parse(snapshot, LocationDiagnosticsProtocol.SNAPSHOT, session))
        val tooLarge = JSONObject().put("msg_type", LocationDiagnosticsProtocol.HELLO)
            .put("version", LocationDiagnosticsProtocol.VERSION).put("session", session)
            .put("reason", "x".repeat(LocationDiagnosticsProtocol.MAX_MESSAGE_BYTES))
        assertNull(LocationDiagnosticsProtocol.parse(tooLarge, LocationDiagnosticsProtocol.HELLO, session))
    }

    @Test
    fun localLogIsBoundedAndStoresOnlyQualitySummaries() {
        val log = BoundedLocationDiagnosticLog(capacity = 2)
        val sample = goodObservation()
        repeat(3) { index ->
            log.append(LocationDiagnosticEvent(
                observedAtEpochMillis = index.toLong(),
                source = "watch",
                kind = "observation",
                quality = "UNKNOWN",
                reason = "summary",
                ageMillis = null,
                horizontalAccuracyMeters = 10f,
                speedMetersPerSecond = null,
                speedAccuracyMetersPerSecond = null,
                bearingAccuracyDegrees = null,
                satelliteCount = null,
                usedSatelliteCount = null,
                meanCn0DbHz = null,
                maxAgcDb = null
            ))
        }
        val saved = log.snapshot()
        assertEquals(2, saved.size)
        assertEquals(1L, saved.first().observedAtEpochMillis)
        val fields = LocationDiagnosticEvent::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.any { it.equals("lat", true) || it.equals("lon", true) ||
            it.contains("deviceId", true) || it.equals("uid", true) })
    }

    @Test
    fun diagnosticsPreferencePersistsIndependentlyOfDeveloperMode() = runBlocking {
        MainActivity.device_uid = "test-watch"
        val repository = DataRepository(RuntimeEnvironment.getApplication() as Context)
        repository.storeEnableWearTakDevMode(false)
        repository.storeBondedLocationDiagnosticsEnabled(false)

        repository.storeBondedLocationDiagnosticsEnabled(true)
        assertTrue(repository.getBondedLocationDiagnosticsEnabled.first())
        assertFalse(repository.getEnableWearTakDevMode.first())

        repository.storeEnableWearTakDevMode(true)
        assertTrue(repository.getBondedLocationDiagnosticsEnabled.first())
        repository.storeBondedLocationDiagnosticsEnabled(false)
        assertTrue(repository.getEnableWearTakDevMode.first())
    }
}
