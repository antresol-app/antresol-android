package app.antresol.mobile.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.mobile.R
import app.antresol.mobile.ui.components.BackBar
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.image.rememberArtworkTint
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * The top of a detail page: an artwork-tinted glow fading to black behind [artwork], the back
 * button over the status bar, and the title block at the bottom.
 */
@Composable
fun DetailHeader(
    tintUrl: String?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    artwork: @Composable BoxScope.() -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    val tint by rememberArtworkTint(tintUrl, AntresolColors.BrandStart)
    Box(modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .background(Brush.verticalGradient(0f to tint, 0.7f to tint.copy(alpha = 0.25f), 1f to AntresolColors.Background)),
        )
        Box(Modifier.matchParentSize(), content = artwork)
        Column(Modifier.fillMaxWidth()) {
            BackBar(onBack)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = PageGutter)
                    .padding(bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

/** A long description that expands on tap. */
@Composable
fun ExpandableText(text: String, modifier: Modifier = Modifier, collapsedLines: Int = 4) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    Text(
        text = text,
        style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
        maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, role = null) { expanded = !expanded }
            .padding(horizontal = PageGutter, vertical = 8.dp),
    )
}

/** Label + value lines ("Audio  English · AAC · Stereo"). */
@Composable
fun InfoLine(label: String, value: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = PageGutter, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = Antresol.typography.caption.copy(color = AntresolColors.LabelTertiary))
        Text(value, style = Antresol.typography.body)
    }
}

/** Shows [notice] (a string resource: a failed write, a playback request that failed) once as a snackbar. */
@Composable
fun NoticeSnackbar(notice: Int?, host: SnackbarHostState, onShown: () -> Unit) {
    val resources = LocalContext.current.resources
    LaunchedEffect(notice) {
        if (notice != null) {
            onShown()
            host.showSnackbar(resources.getString(notice))
        }
    }
}

/** A full-size centred box for loading and error states under the back button. */
@Composable
fun DetailStateBox(onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().background(AntresolColors.Background)) {
        BackBar(onBack)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { content() }
    }
}
