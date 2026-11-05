package app.antresol.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.antresol.mobile.R as MobileR
import app.antresol.ui.R
import app.antresol.ui.components.LogoTile
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol

/** Who is signed in, for the avatar in the tab header, and what tapping it does. */
data class HeaderProfile(val name: String, val avatar: String?, val onClick: () -> Unit)

val LocalHeaderProfile = staticCompositionLocalOf<HeaderProfile?> { null }

/** The top of every tab: logo mark + "Antresol" on the left, the profile avatar (opens the account sheet) on the right. */
@Composable
fun TabHeader(modifier: Modifier = Modifier) {
    val profile = LocalHeaderProfile.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = PageGutter, end = PageGutter - 2.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        LogoTile(size = 30.dp)
        Text(
            text = stringResource(R.string.brand_name),
            style = Antresol.typography.sectionTitle.copy(fontSize = 21.sp),
        )
        Spacer(Modifier.weight(1f))
        if (profile != null) {
            val description = stringResource(MobileR.string.account_open, profile.name)
            ProfileAvatar(
                name = profile.name,
                avatar = profile.avatar,
                size = 38.dp,
                modifier = Modifier
                    .clip(CircleShape)
                    .focusClickable(CircleShape, onClick = profile.onClick)
                    .semantics { contentDescription = description },
            )
        }
    }
}

/** The large bold page title under the header ("Movies"). */
@Composable
fun PageTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Antresol.typography.pageTitle,
        modifier = modifier.padding(horizontal = PageGutter, vertical = 8.dp),
    )
}
