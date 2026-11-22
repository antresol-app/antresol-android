package app.antresol.ui.ways

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The way each title plays with «Play», kept on the device. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DeviceWayDefaultsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun aChosenWayIsRememberedPerTitleAndSurvivesANewInstance() {
        var now = 1_000L
        val defaults = DeviceWayDefaults(context) { now++ }
        assertNull(defaults.get("movie", 603))
        defaults.set("movie", 603, "alpha/watch")
        defaults.set("series", 603, "beta/other")
        assertEquals("alpha/watch", defaults.get("movie", 603))
        assertEquals("beta/other", defaults.get("series", 603))
        defaults.set("movie", 603, "alpha/second")
        assertEquals("alpha/second", DeviceWayDefaults(context).get("movie", 603))
    }

    @Test
    fun theOldestTitlesGoWhenThereAreTooMany() {
        var now = 1L
        val defaults = DeviceWayDefaults(context) { now++ }
        repeat(DeviceWayDefaults.MAX_ENTRIES + 5) { defaults.set("movie", it.toLong(), "alpha/watch") }
        assertNull("the first is gone", defaults.get("movie", 0))
        assertEquals("alpha/watch", defaults.get("movie", DeviceWayDefaults.MAX_ENTRIES + 4L))
        val kept = (0 until DeviceWayDefaults.MAX_ENTRIES + 5).count { defaults.get("movie", it.toLong()) != null }
        assertEquals(DeviceWayDefaults.MAX_ENTRIES, kept)
    }
}
