package app.antresol.mobile.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import app.antresol.core.api.MediaUrls

/** Image and media URL builder of the signed-in server; null outside the signed-in shell. */
val LocalMediaUrls = staticCompositionLocalOf<MediaUrls?> { null }

/** Space that scrolling content leaves at the bottom for the floating tab bar (and the system bar). */
val LocalBottomBarPadding = staticCompositionLocalOf { PaddingValues(0.dp) }
