package com.aitech.weartak_civ.service

import android.app.AlarmManager
import android.app.Application
import android.os.SystemClock
import com.aitech.weartak_civ.Utils
import com.aitech.weartak_civ.data.DataRepository
import com.aitech.weartak_civ.presentation.MainActivity
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class LocationAlarmTest {
    private lateinit var alarms: AlarmManager

    @Before fun setup() {
        MainActivity.device_uid = "location-alarm-test"
        MainActivity.versionNumber = "test"
        MainActivity.mainActivity = Robolectric.buildActivity(MainActivity::class.java).get()
        MainActivity.dataRepository = DataRepository(RuntimeEnvironment.getApplication())
        alarms = MainActivity.mainActivity.getSystemService(AlarmManager::class.java)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        CotDispatchService.stationaryLocation = false
    }

    @After fun cleanup() {
        Utils.cancelGlobalTimer(MainActivity.mainActivity)
        CotDispatchService.stationaryLocation = false
    }

    @Test fun movingObservationIsExactButNotAnAlarmClockOrIdleBypass() {
        val now = SystemClock.elapsedRealtime()
        Utils.runTimerJob(60_000L)
        val scheduled = shadowOf(alarms).peekNextScheduledAlarm()
        assertNotNull(scheduled)
        assertEquals(AlarmManager.ELAPSED_REALTIME_WAKEUP, scheduled.type)
        assertEquals(now + 60_000L - Utils.REDUCE_ALARM_TIME_MS, scheduled.triggerAtTime)
        assertNull(alarms.nextAlarmClock)
        assertFalse(scheduled.allowWhileIdle)
    }

    @Test fun stationaryObservationIsInexactAndCancelableWithoutLeavingAUserAlarm() {
        CotDispatchService.stationaryIntervalSeconds = 300
        Utils.startGlobalTimer(300_000L)
        val scheduled = shadowOf(alarms).peekNextScheduledAlarm()
        assertNotNull(scheduled)
        assertNotEquals(ShadowAlarmManager.WINDOW_EXACT, scheduled.windowLengthMs)
        assertFalse(scheduled.allowWhileIdle)
        assertNull(alarms.nextAlarmClock)
        Utils.cancelGlobalTimer(MainActivity.mainActivity)
        assertNull(shadowOf(alarms).peekNextScheduledAlarm())
    }

    @Test fun missingExactPermissionFallsBackToDeferrableObservation() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        Utils.startGlobalTimer(60_000L)
        assertNotNull(shadowOf(alarms).peekNextScheduledAlarm())
        assertNull(alarms.nextAlarmClock)
    }

    @Test fun reprofileReplacesPendingAlarmAndDestroyCancelsIt() {
        Utils.startGlobalTimer(60_000L)
        CotDispatchService.stationaryIntervalSeconds = 300
        Utils.startGlobalTimer(300_000L)
        assertEquals(1, shadowOf(alarms).scheduledAlarms.size)
        val service = Robolectric.buildService(LocationService::class.java).get()
        service.onDestroy()
        assertNull(shadowOf(alarms).peekNextScheduledAlarm())
    }
}
