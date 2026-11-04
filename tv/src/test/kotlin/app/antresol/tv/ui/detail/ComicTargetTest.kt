package app.antresol.tv.ui.detail

import app.antresol.core.model.BookDetail
import app.antresol.core.model.BookProgress
import app.antresol.core.model.Card
import app.antresol.core.model.Kind
import app.antresol.core.model.VolumeCard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComicTargetTest {
    private fun volume(progress: BookProgress?) = BookDetail(
        card = Card(id = 21, kind = Kind.COMIC_VOLUME, title = "Hero Tales"),
        kind = Kind.COMIC_VOLUME,
        readFileId = 13,
        progress = progress,
    )

    @Test
    fun volumeContinuesAtTheSavedPage() {
        assertEquals(ComicTarget(21, 2), volume(BookProgress(fraction = 0.66, locator = "page:2")).comicTarget())
        assertEquals(ComicTarget(21, null), volume(null).comicTarget())
        assertEquals(ComicTarget(21, null), volume(BookProgress(fraction = 1.0, locator = "page:3", finished = true)).comicTarget())
    }

    @Test
    fun seriesOpensTheFirstUnfinishedVolume() {
        val series = BookDetail(
            card = Card(id = 20, kind = Kind.COMIC),
            kind = Kind.COMIC,
            volumes = listOf(VolumeCard(id = 30, progress = 1.0), VolumeCard(id = 31, progress = 0.2), VolumeCard(id = 32)),
        )
        assertEquals(ComicTarget(31, null), series.comicTarget())
        assertEquals(ComicTarget(30, null), series.copy(volumes = series.volumes.take(1)).comicTarget())
        assertNull(series.copy(volumes = emptyList()).comicTarget())
    }
}
