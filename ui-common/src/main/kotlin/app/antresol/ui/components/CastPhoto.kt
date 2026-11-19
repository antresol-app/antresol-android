package app.antresol.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import app.antresol.core.model.CastMember
import app.antresol.ui.theme.AntresolGradients

/**
 * A cast member's round photo: [ProfileAvatar] with the initials of a stable gradient underneath.
 * Load [photoUrl] (the resolved `cast[].photo`) with the app's image loader, which adds the Bearer header.
 */
@Composable
fun CastPhoto(member: CastMember, photoUrl: String?, size: Dp, modifier: Modifier = Modifier) =
    ProfileAvatar(name = member.name, avatar = castGradient(member.name), modifier = modifier, size = size, photoUrl = photoUrl)

/** A stable avatar gradient per name, so a person keeps their colour everywhere. */
fun castGradient(name: String): String =
    AntresolGradients.avatarIds[Math.floorMod(name.hashCode(), AntresolGradients.avatarIds.size)]
