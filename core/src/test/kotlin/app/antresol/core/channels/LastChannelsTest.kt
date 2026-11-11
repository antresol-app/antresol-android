package app.antresol.core.channels

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LastChannelsTest {
    @Test
    fun remembersTheChannelPerServerAndProfile() {
        val storage = InMemoryLastChannelStorage()
        val channels = LastChannels(storage)
        val dad = channels.of("server-a", 1)
        val kid = channels.of("server-a", 2)
        val otherServer = channels.of("server-b", 1)

        assertNull(dad.id.value)
        dad.remember(10)
        kid.remember(20)
        assertEquals(10L, dad.id.value)
        assertEquals(20L, kid.id.value)
        assertNull(otherServer.id.value)
        assertEquals(10L, storage.read(LastChannels.key("server-a", 1)))
    }

    @Test
    fun readsWhatAnEarlierRunStored() {
        val storage = InMemoryLastChannelStorage()
        LastChannels(storage).of("s", 7).remember(3)
        assertEquals(3L, LastChannels(storage).of("s", 7).id.value)
    }

    @Test
    fun aHandleSeesChangesMadeThroughAnother() {
        val channels = LastChannels(InMemoryLastChannelStorage())
        val page = channels.of("s", 1)
        channels.of("s", 1).remember(5)
        assertEquals(5L, page.id.value)
    }
}
