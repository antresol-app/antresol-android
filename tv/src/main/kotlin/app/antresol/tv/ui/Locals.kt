package app.antresol.tv.ui

import androidx.compose.runtime.staticCompositionLocalOf
import app.antresol.core.api.MediaUrls

/** Image and media URL builder of the signed-in server; null outside the signed-in shell. */
val LocalMediaUrls = staticCompositionLocalOf<MediaUrls?> { null }
