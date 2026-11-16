package app.antresol.ui.playback

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * The stream was refused with a 401: the device session behind its Bearer header or media key was
 * revoked. Streams are fetched outside `AntresolClient`, so the player asks an authenticated call
 * (`me()`) to report it; the session then returns the app to the profile picker.
 */
val PlaybackException.isUnauthorized: Boolean
    get() = (cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode == 401
