package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolTheme
import app.antresol.ui.theme.Antresol
import coil.compose.AsyncImage

/**
 * A profile avatar: the profile's photo when it has one ([photoUrl], the resolved `photo` of the
 * profile, loaded with the app's image loader and its disk cache; the server's address carries the
 * upload's version, so a new photo is a new address and an old one is never shown again). The
 * first letter of [name] on the `grad-N` gradient named by [avatar] is drawn underneath and stays
 * while the photo loads, when it fails and when there is none.
 */
@Composable
fun ProfileAvatar(
    name: String,
    avatar: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    photoUrl: String? = null,
) {
    // Like ArtworkImage: the letter stays until the photo has loaded (and comes back if it fails),
    // so a photo with transparent edges never shows the letter through it.
    var loaded by remember(photoUrl) { mutableStateOf(false) }
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .semantics { contentDescription = name },
    ) {
        if (!loaded) LetterAvatar(name, avatar, size)
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                onSuccess = { loaded = true },
                onError = { loaded = false },
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

@Composable
private fun LetterAvatar(name: String, avatar: String?, size: Dp) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val base = Antresol.typography.avatarInitial
    Box(
        modifier = Modifier
            .size(size)
            .background(AntresolGradients.avatar(avatar)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = base.copy(fontSize = base.fontSize * (size.value / 64f)),
        )
    }
}

@Preview
@Composable
private fun ProfileAvatarPreview() = AntresolTheme {
    ProfileAvatar(name = "Admin", avatar = "grad-1", size = 64.dp)
}
