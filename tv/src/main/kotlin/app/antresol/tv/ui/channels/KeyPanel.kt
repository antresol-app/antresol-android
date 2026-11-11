package app.antresol.tv.ui.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** One action of a [KeyPanel]. */
data class PanelAction(val label: String, val icon: ImageVector? = null, val onClick: () -> Unit)

/**
 * A panel over the page whose choices are driven by the remote directly (the page's root passes its
 * keys to [handlePanelKey]), so it never depends on moving focus out of the list under it. It looks
 * like the app's OptionsPanel: an opaque column on the right over a dimmed page, the selected choice
 * white with black text. [content] goes above the choices (programme details).
 */
@Composable
fun KeyPanel(
    title: String,
    actions: List<PanelAction>,
    selected: Int,
    width: Dp = 380.dp,
    content: @Composable ColumnScope.() -> Unit = {},
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(width)
                .background(AntresolColors.BackgroundElevated)
                .padding(horizontal = 20.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(title, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
            Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                actions.forEachIndexed { index, action -> PanelRow(action, index == selected) }
            }
        }
    }
}

@Composable
private fun PanelRow(action: PanelAction, selected: Boolean) {
    val content = if (selected) AntresolColors.LabelInverse else AntresolColors.Label
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) AntresolColors.Label else Color.Transparent, AntresolShapes.control)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(20.dp)) {
            action.icon?.let { Icon(it, contentDescription = null, tint = content) }
        }
        Text(action.label, style = Antresol.typography.bodyEmphasis, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/**
 * The remote on an open [KeyPanel]: Up/Down move the selection, OK (on release, so the press that
 * opened the panel does not count) runs it and closes, Back goes to the caller's BackHandler. Every
 * other key is swallowed so nothing under the panel reacts.
 */
fun handlePanelKey(event: KeyEvent, count: Int, selected: Int, onSelect: (Int) -> Unit, onRun: (Int) -> Unit): Boolean {
    if (event.key == Key.Back) return false
    val down = event.type == KeyEventType.KeyDown
    when (event.key) {
        Key.DirectionUp, Key.DirectionLeft -> if (down) onSelect((selected - 1).coerceAtLeast(0))
        Key.DirectionDown, Key.DirectionRight -> if (down) onSelect((selected + 1).coerceAtMost(count - 1))
        in OkKeys -> if (event.type == KeyEventType.KeyUp) onRun(selected)
    }
    return true
}

