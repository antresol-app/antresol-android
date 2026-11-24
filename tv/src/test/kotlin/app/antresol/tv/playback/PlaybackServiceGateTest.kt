package app.antresol.tv.playback

import androidx.media3.common.Player
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Who may do everything with the exported media session, and what every other app's controller keeps. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlaybackServiceGateTest {
    private val ownUid = 10_123

    @Test
    fun theSystemAndThisAppAreAllowedEverything() {
        assertTrue(isPrivilegedController(trusted = true, uid = 1000, ownUid = ownUid))
        assertTrue(isPrivilegedController(trusted = false, uid = ownUid, ownUid = ownUid))
    }

    @Test
    fun anotherAppIsNotEvenWithOurPackageName() {
        // The package name is not an input: a controller can write any name into its own request.
        assertFalse(isPrivilegedController(trusted = false, uid = 10_456, ownUid = ownUid))
        assertFalse(isPrivilegedController(trusted = false, uid = -1, ownUid = ownUid))
    }

    @Test
    fun otherAppsKeepTheTransportButCannotQueueItems() {
        val commands = GUEST_PLAYER_COMMANDS
        for (command in listOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
        )) {
            assertTrue("command $command", commands.contains(command))
        }
        assertFalse(commands.contains(Player.COMMAND_SET_MEDIA_ITEM))
        assertFalse(commands.contains(Player.COMMAND_CHANGE_MEDIA_ITEMS))
    }
}
