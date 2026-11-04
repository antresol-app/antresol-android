package app.antresol.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.ui.theme.AntresolGradients
import app.antresol.ui.theme.AntresolTheme
import app.antresol.ui.theme.Antresol

/** A profile avatar: the first letter of [name] on the `grad-N` gradient named by [avatar]. */
@Composable
fun ProfileAvatar(
    name: String,
    avatar: String?,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
) {
    val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "?"
    val base = Antresol.typography.avatarInitial
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(AntresolGradients.avatar(avatar))
            .semantics { contentDescription = name },
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
