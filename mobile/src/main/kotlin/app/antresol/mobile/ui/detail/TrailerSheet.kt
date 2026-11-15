package app.antresol.mobile.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.core.model.Trailer
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.Badge
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import app.antresol.ui.trailers.trailerLabel

/** The trailer chooser of a detail page with several trailers: one row per trailer, best first. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrailerSheet(trailers: List<Trailer>, onDismiss: () -> Unit, onOpen: (Trailer) -> Unit) {
    val resources = LocalContext.current.resources
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp)) {
            Text(
                stringResource(UiR.string.trailer_choose),
                style = Antresol.typography.sectionTitle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            trailers.forEachIndexed { index, trailer ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusClickable(AntresolShapes.control) { onOpen(trailer) }
                        .testTag("trailer-$index")
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Icon(AntresolIcons.Trailer, contentDescription = null, tint = AntresolColors.Label, modifier = Modifier.size(24.dp))
                    Text(
                        trailerLabel(resources, trailer),
                        style = Antresol.typography.body.copy(color = AntresolColors.Label),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    trailer.lang.takeIf { it.isNotBlank() }?.let { Badge(it.uppercase()) }
                }
            }
        }
    }
}
