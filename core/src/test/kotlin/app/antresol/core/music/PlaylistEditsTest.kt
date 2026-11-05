package app.antresol.core.music

import app.antresol.core.api.AntresolClient
import app.antresol.core.model.PlaylistPatch
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistEditsTest {
    @Test
    fun moveOrderListsOldPositionsInTheirNewPlaces() {
        // a b c d: move c (2) to the front → c a b d.
        assertEquals(listOf(2, 0, 1, 3), PlaylistEdits.moveOrder(4, from = 2, to = 0))
        // Move a (0) to the end → b c d a.
        assertEquals(listOf(1, 2, 3, 0), PlaylistEdits.moveOrder(4, from = 0, to = 3))
        assertEquals(listOf(0, 1, 2), PlaylistEdits.moveOrder(3, from = 1, to = 1))
        assertEquals(listOf("c", "a", "b", "d"), PlaylistEdits.moved(listOf("a", "b", "c", "d"), 2, 0))
    }

    @Test
    fun removeAndRenameBodies() {
        assertEquals(listOf("a", "c"), PlaylistEdits.removed(listOf("a", "b", "c"), 1))
        assertEquals(PlaylistPatch(remove = listOf(1)), PlaylistEdits.remove(1))
        assertEquals("Road Trip", PlaylistEdits.rename("  Road Trip ").name)
    }

    @Test
    fun patchBodiesOmitUnusedFields() {
        val json = AntresolClient.json.encodeToString(PlaylistPatch.serializer(), PlaylistEdits.move(3, 0, 2))
        assertEquals("""{"order":[1,2,0]}""", json)
        assertEquals("""{"append":[5,6]}""", AntresolClient.json.encodeToString(PlaylistPatch.serializer(), PlaylistEdits.append(listOf(5, 6))))
    }
}
