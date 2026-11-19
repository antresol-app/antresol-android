package app.antresol.tv.autostart

import app.antresol.core.model.Channel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** «Где остановился» into a live channel: whose record it is, what plays, and how long the server is waited for. */
@OptIn(ExperimentalCoroutinesApi::class)
class ChannelAutostartTest {
    private val record = LastChannel(serverId = "srv", userId = 1, historyId = 20, channelId = 3)
    private val channels = listOf(Channel(id = 2, number = 2), Channel(id = 3, number = 3), Channel(id = 7, number = 1))

    @Test
    fun theRecordIsOnlyForTheProfileThatMadeIt() {
        assertEquals(record, ChannelAutostart.recordFor(record, "srv", 1, historyId = 20, historyKnown = true))
        assertNull(ChannelAutostart.recordFor(record, "other", 1, historyId = 20, historyKnown = true))
        assertNull(ChannelAutostart.recordFor(record, "srv", 2, historyId = 20, historyKnown = true))
        // Another separate history (or the profile's own) since: not this viewer's channel.
        assertNull(ChannelAutostart.recordFor(record, "srv", 1, historyId = null, historyKnown = true))
        // Offline the history is not known yet: the profile decides.
        assertEquals(record, ChannelAutostart.recordFor(record, "srv", 1, historyId = null, historyKnown = false))
    }

    @Test
    fun lastPlaysTheChannelThatWasOnScreen() = runTest {
        assertEquals(ChannelStart.Play(3), ChannelAutostart.resumeLast(record) { channels })
    }

    @Test
    fun lastWithoutAChannelLeavesTheAppAsItWas() = runTest {
        // A movie or an episode was last (or nothing): the server is not even asked.
        assertEquals(ChannelStart.Stay, ChannelAutostart.resumeLast(null) { error("not asked") })
    }

    @Test
    fun aChannelThatIsGoneOrNoAnswerOpensTheTvTab() = runTest {
        assertEquals(ChannelStart.OpenTv, ChannelAutostart.resumeLast(record.copy(channelId = 99)) { channels })
        assertEquals(ChannelStart.OpenTv, ChannelAutostart.resumeLast(record) { null })
    }

    @Test
    fun channelTargetPrefersTheDeviceThenTheServerThenTheFirstChannel() = runTest {
        assertEquals(ChannelStart.Play(3), ChannelAutostart.lastChannel(record) { channels to 2L })
        assertEquals(ChannelStart.Play(2), ChannelAutostart.lastChannel(null) { channels to 2L })
        assertEquals(ChannelStart.Play(2), ChannelAutostart.lastChannel(record.copy(channelId = 99)) { channels to 2L })
        assertEquals(ChannelStart.Play(7), ChannelAutostart.lastChannel(null) { channels to null })
        assertEquals(ChannelStart.OpenTv, ChannelAutostart.lastChannel(null) { emptyList<Channel>() to null })
        assertEquals(ChannelStart.OpenTv, ChannelAutostart.lastChannel(record) { null })
    }

    @Test
    fun theServerIsTriedAgainUntilItAnswers() = runTest {
        var calls = 0
        val answer = ChannelAutostart.untilAnswered {
            calls++
            if (calls < 4) throw IOException("network is not up yet")
            channels
        }
        assertEquals(channels, answer)
        assertEquals(4, calls)
        assertEquals(3 * ChannelAutostart.RETRY_STEP_MS, currentTime)
    }

    @Test
    fun theWaitEnds() = runTest {
        assertNull(ChannelAutostart.untilAnswered<List<Channel>> { throw IOException("down") })
        assertEquals(ChannelAutostart.SERVER_WAIT_MS, currentTime)
        // A request that hangs is cut off at the same deadline.
        assertNull(ChannelAutostart.untilAnswered<List<Channel>> { awaitCancellation() })
        assertEquals(2 * ChannelAutostart.SERVER_WAIT_MS, currentTime)
    }
}
