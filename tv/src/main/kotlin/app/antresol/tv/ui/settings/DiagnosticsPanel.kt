package app.antresol.tv.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.antresol.tv.R as TvR
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.components.QrCodeImage
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.launch

/**
 * Settings → Diagnostics (administrators): what the TV kept about its freezes and restarts, as text
 * (Up and Down scroll it) and as a QR code of its shorter form, for a phone to copy. [report] makes
 * the text (at most the given length); nothing leaves the TV. Back closes it.
 */
@Composable
internal fun DiagnosticsPanel(report: suspend (maxChars: Int) -> String, onDismiss: () -> Unit) {
    var full by remember { mutableStateOf<String?>(null) }
    var short by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        full = report(Int.MAX_VALUE)
        short = report(QR_CHARS)
    }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f)),
        contentAlignment = Alignment.CenterEnd,
    ) {
        Column(
            Modifier
                .fillMaxHeight()
                .width(PANEL_WIDTH)
                .background(AntresolColors.BackgroundElevated)
                .padding(horizontal = 32.dp, vertical = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(stringResource(TvR.string.settings_diagnostics), style = Antresol.typography.sectionTitle)
            Text(stringResource(TvR.string.settings_diagnostics_note), style = Antresol.typography.caption)
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.width(QR_SIZE)) {
                    short?.let { QrCodeImage(it, QR_SIZE, stringResource(TvR.string.settings_diagnostics_qr)) }
                }
                Text(
                    text = full.orEmpty(),
                    style = Antresol.typography.caption.copy(fontFamily = FontFamily.Monospace, fontSize = 8.sp, lineHeight = 11.sp),
                    color = AntresolColors.LabelSecondary,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            val step = when (event.key) {
                                Key.DirectionDown -> SCROLL_STEP
                                Key.DirectionUp -> -SCROLL_STEP
                                Key.Back -> {
                                    if (event.type == KeyEventType.KeyUp) onDismiss()
                                    return@onPreviewKeyEvent true
                                }
                                else -> return@onPreviewKeyEvent false
                            }
                            if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(step) }
                            true
                        }
                        .focusable()
                        .verticalScroll(scroll)
                        .testTag("diagnostics-text"),
                )
            }
        }
    }
}

/** The QR code's text: about a version-17 code at level M, modules of 5–6 px on a 1080p screen, which a phone reads from the sofa. */
private const val QR_CHARS = 500
private val QR_SIZE = 280.dp
private val PANEL_WIDTH = 900.dp
private const val SCROLL_STEP = 120f
