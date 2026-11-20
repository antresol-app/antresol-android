package app.antresol.ui.playback

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.antresol.core.channels.LivePlaybackPath
import app.antresol.ui.R

/** The live banner's quiet line: «Напрямую · host» / «Через Antresol» · format · codecs · «Источник n из m». */
@Composable
fun playbackPathText(path: LivePlaybackPath): String {
    val route = when {
        !path.direct -> stringResource(R.string.tv_path_antresol)
        path.directHost.isNullOrBlank() -> stringResource(R.string.tv_path_direct_plain)
        else -> stringResource(R.string.tv_path_direct, path.directHost!!)
    }
    val source = if (path.showsAttempt) stringResource(R.string.tv_path_source, path.attempt!!, path.count!!) else null
    return (listOf(route) + path.technical + listOfNotNull(source)).joinToString(" · ")
}
