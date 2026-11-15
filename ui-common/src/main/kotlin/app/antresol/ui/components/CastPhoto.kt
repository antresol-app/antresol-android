package app.antresol.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import app.antresol.core.model.CastMember
import app.antresol.ui.theme.AntresolGradients
import coil.compose.AsyncImage

/**
 * A cast member's round photo. The initials avatar is drawn underneath and stays when there is
 * no photo, while it loads and when it fails. Load [photoUrl] (the resolved `cast[].photo`) with
 * the app's image loader, which adds the Bearer header.
 */
@Composable
fun CastPhoto(member: CastMember, photoUrl: String?, size: Dp, modifier: Modifier = Modifier) {
    // Like ArtworkImage: the initials stay until the photo has loaded (and come back if it fails),
    // so a photo with transparent edges never shows letters through it.
    var loaded by remember(photoUrl) { mutableStateOf(false) }
    Box(modifier.size(size).clip(CircleShape)) {
        if (!loaded) ProfileAvatar(name = member.name, avatar = castGradient(member.name), size = size)
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

/** A stable avatar gradient per name, so a person keeps their colour everywhere. */
fun castGradient(name: String): String =
    AntresolGradients.avatarIds[Math.floorMod(name.hashCode(), AntresolGradients.avatarIds.size)]
