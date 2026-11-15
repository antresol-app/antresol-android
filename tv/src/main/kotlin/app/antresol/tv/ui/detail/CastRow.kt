package app.antresol.tv.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.model.CastMember
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.CardSurface
import app.antresol.tv.ui.components.FocusMemory
import app.antresol.tv.ui.components.HorizontalRowScrolling
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.trapHorizontalFocus
import app.antresol.ui.R
import app.antresol.ui.components.CastPhoto
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** Diameter of a cast photo on the 960 dp TV canvas. */
private val PhotoSize = 84.dp

/**
 * "Cast": a row of round photos (initials while a photo loads or when there is none) with the
 * name and the character under each. The photos take focus, so the row scrolls with the D-pad
 * like the shelves; OK does nothing.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CastRow(cast: List<CastMember>, focus: FocusMemory, modifier: Modifier = Modifier) {
    val urls = LocalMediaUrls.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            stringResource(R.string.detail_cast),
            style = Antresol.typography.sectionTitle,
            modifier = Modifier.padding(horizontal = ScreenPaddingH),
        )
        HorizontalRowScrolling {
            LazyRow(
                state = rememberLazyListState(),
                contentPadding = PaddingValues(horizontal = ScreenPaddingH, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
                modifier = Modifier
                    .trapHorizontalFocus()
                    .focusRestorer(),
            ) {
                // The same person can be listed twice: the position keeps keys unique.
                itemsIndexed(cast, key = { index, member -> "$index-${member.name}" }) { index, member ->
                    var focused by remember { mutableStateOf(false) }
                    Column(
                        Modifier.width(PhotoSize + 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CardSurface(
                            onClick = {},
                            outline = CircleShape,
                            modifier = Modifier
                                .size(PhotoSize)
                                .remembersFocus(focus, "cast/$index")
                                .onFocusChanged { focused = it.hasFocus },
                        ) {
                            CastPhoto(member, urls?.image(member.photo), PhotoSize)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                member.name,
                                style = Antresol.typography.title,
                                color = if (focused) AntresolColors.Label else AntresolColors.Label.copy(alpha = 0.88f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                            member.character?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                            }
                        }
                    }
                }
            }
        }
    }
}
