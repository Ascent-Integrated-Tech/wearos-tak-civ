package com.aitech.weartak_civ.provider

import android.location.Location
import android.app.Application
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RepoLocationProviderTest {
    @Test
    fun convertsValidFixWithAccuracy() {
        val fix = Location("test").apply {
            latitude = 37.5
            longitude = -122.25
            accuracy = 5f
        }

        val mapped = fix.toMapLibreLocationOrNull()

        assertNotNull(mapped)
        assertEquals(-122.25, mapped!!.position.longitude, 0.0)
        assertEquals(37.5, mapped.position.latitude, 0.0)
        assertEquals(5.0, mapped.accuracy, 0.0)
    }

    @Test
    fun rejectsMissingAccuracy() {
        val fix = Location("test").apply {
            latitude = 37.5
            longitude = -122.25
        }

        assertNull(fix.toMapLibreLocationOrNull())
    }

    @Test
    fun rejectsInvalidCoordinatesAndAccuracy() {
        val invalidLatitudes = listOf(Double.NaN, Double.POSITIVE_INFINITY, -90.01, 90.01)
        invalidLatitudes.forEach { latitude ->
            val fix = Location("test").apply {
                this.latitude = latitude
                longitude = 0.0
            }
            assertNull(fix.toMapLibreLocationOrNull())
        }

        val invalidLongitudes = listOf(Double.NaN, Double.NEGATIVE_INFINITY, -180.01, 180.01)
        invalidLongitudes.forEach { longitude ->
            val fix = Location("test").apply {
                latitude = 0.0
                this.longitude = longitude
            }
            assertNull(fix.toMapLibreLocationOrNull())
        }

        val invalidAccuracy = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            accuracy = Float.NaN
        }
        assertNull(invalidAccuracy.toMapLibreLocationOrNull())

        val zeroAccuracy = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            accuracy = 0f
        }
        assertNull(zeroAccuracy.toMapLibreLocationOrNull())
    }

    @Test
    fun ignoresNonFiniteOptionalMotionValues() {
        val fix = Location("test").apply {
            latitude = 0.0
            longitude = 0.0
            accuracy = 5f
            speed = Float.NaN
        }

        val mapped = fix.toMapLibreLocationOrNull()

        assertNotNull(mapped)
        assertNull(mapped!!.speed)
        assertNull(mapped.bearing)
    }
}
