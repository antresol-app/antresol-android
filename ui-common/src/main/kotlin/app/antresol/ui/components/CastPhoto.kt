package app.antresol.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
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
    Box(modifier.size(size).clip(CircleShape)) {
        ProfileAvatar(name = member.name, avatar = castGradient(member.name), size = size)
        if (photoUrl != null) {
            AsyncImage(
                model = photoUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.matchParentSize(),
            )
        }
    }
}

/** A stable avatar gradient per name, so a person keeps their colour everywhere. */
fun castGradient(name: String): String =
    AntresolGradients.avatarIds[Math.floorMod(name.hashCode(), AntresolGradients.avatarIds.size)]
