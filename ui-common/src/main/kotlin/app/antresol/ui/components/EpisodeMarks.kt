package app.antresol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.antresol.ui.R

/**
 * The marks under an episode of the guide: the accent "New" badge (an aired episode the profile has
 * not watched, as on the web) and the library [state] ("Downloaded", "Airs 21 Nov"…). Nothing when
 * there is neither.
 */
@Composable
fun EpisodeMarks(isNew: Boolean, state: String?, modifier: Modifier = Modifier) {
    if (!isNew && state == null) return
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isNew) Badge(stringResource(R.string.episode_new), accent = true)
        state?.let { Badge(it) }
    }
}
