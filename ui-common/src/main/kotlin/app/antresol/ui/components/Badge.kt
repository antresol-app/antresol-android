package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import app.antresol.ui.format.Badges
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * A small dark outlined chip with grey text, such as "1080p" or "Audio EN RU" (server-provided card
 * badges). Every kind looks the same, as in the design: nothing under a card competes with the focus ring.
 * Audio and subtitle languages show an icon in place of the word ("Audio RU EN" → 🔈 RU EN), as on the
 * web and iPhone; TalkBack still reads the word. With no languages after the word ("Subtitles") the
 * icon keeps the word beside it, so the badge is not a bare glyph. Every badge has the same height,
 * with or without an icon. [accent] is the web's accent badge: "New" on an episode of the guide.
 */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val ink = if (accent) AntresolColors.Accent else AntresolColors.LabelSecondary
    val style = Antresol.typography.badge.copy(color = ink)
    val label = BadgeLabel.of(text)
    // the icon at the text's cap height: the font size in dp, a little smaller
    val iconSize = with(LocalDensity.current) { (style.fontSize.toDp() * 1.1f) }
    // One line of the badge's text plus the padding: an icon-only content is not shorter than a text one.
    val height = with(LocalDensity.current) { style.lineHeight.toDp() } + BADGE_PADDING * 2
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier
            .background(if (accent) AntresolColors.Accent.copy(alpha = 0.16f) else AntresolColors.FillTertiary, AntresolShapes.badge)
            .border(1.dp, if (accent) AntresolColors.Accent.copy(alpha = 0.4f) else AntresolColors.Hairline, AntresolShapes.badge)
            .heightIn(min = height)
            .padding(horizontal = 6.dp, vertical = BADGE_PADDING)
            .clearAndSetSemantics { contentDescription = text },
    ) {
        label.icon?.let { Icon(it, contentDescription = null, tint = ink, modifier = Modifier.size(iconSize)) }
        val shown = label.shown
        if (shown.isNotEmpty()) Text(text = shown, style = style, maxLines = 1)
    }
}

private val BADGE_PADDING = 2.dp

/**
 * A badge's text split into an icon for the audio or subtitles word (the server's, in either language) and the rest.
 * [word] is the word that was taken off the front, kept for a badge with nothing after it.
 */
data class BadgeLabel(val kind: Kind?, val rest: String, val word: String = "") {
    enum class Kind { Audio, Subtitles }

    /** The text drawn beside the icon: the languages, or the word itself when there are none (an icon alone says little). */
    val shown: String
        get() = rest.ifEmpty { word }

    val icon: ImageVector?
        get() = when (kind) {
            Kind.Audio -> AntresolIcons.Speaker
            Kind.Subtitles -> AntresolIcons.Subtitles
            null -> null
        }

    companion object {
        private val words = listOf(
            Kind.Audio to listOf("Звук", "Аудио", "Audio"),
            Kind.Subtitles to listOf("Субтитры", "Subtitles"),
        )

        fun of(text: String): BadgeLabel {
            for ((kind, list) in words) for (word in list) {
                if (text == word || text.startsWith("$word ") || text.startsWith("$word:")) {
                    return BadgeLabel(kind, text.removePrefix(word).removePrefix(":").trim(), word)
                }
            }
            return BadgeLabel(null, text)
        }
    }
}

/** [Badge]s, quality badges first; they wrap onto a second line in narrow cards (as in the web app) instead of being cut. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BadgeRow(badges: List<String>, modifier: Modifier = Modifier, max: Int = 3) {
    if (badges.isEmpty()) return
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Badges.ordered(badges).take(max).forEach { Badge(it) }
    }
}
