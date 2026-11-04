package app.antresol.mobile.ui.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.antresol.core.model.Me
import app.antresol.core.session.StoredSession
import app.antresol.mobile.R
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.antresol.ui.R as UiR

/** The avatar's sheet: who is signed in where, Settings, Switch Profile, Change Server and Sign Out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSheet(
    session: StoredSession,
    user: Me?,
    onDismiss: () -> Unit,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onChangeServer: () -> Unit,
    onSettings: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp)) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                val name = user?.name ?: session.userName
                ProfileAvatar(name = name, avatar = user?.avatar ?: session.avatar, size = 56.dp)
                Column {
                    Text(name, style = Antresol.typography.sectionTitle)
                    val role = when {
                        user?.isAdmin == true -> stringResource(UiR.string.account_admin)
                        user?.isKid == true -> stringResource(R.string.account_kid)
                        else -> stringResource(R.string.account_profile)
                    }
                    Text(role, style = Antresol.typography.caption)
                    val host = session.serverUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: session.serverUrl
                    Text(stringResource(UiR.string.server_connected_to, host), style = Antresol.typography.caption)
                }
            }
            HorizontalDivider(color = AntresolColors.Hairline, modifier = Modifier.padding(vertical = 8.dp))
            SheetAction(MobileIcons.Gear, stringResource(UiR.string.nav_settings), AntresolColors.Label, onSettings, "account-settings")
            SheetAction(AntresolIcons.SwitchProfile, stringResource(UiR.string.profile_switch), AntresolColors.Label, onSwitchProfile, "account-switch")
            SheetAction(AntresolIcons.Server, stringResource(UiR.string.server_change), AntresolColors.Label, onChangeServer, "account-server")
            SheetAction(AntresolIcons.SignOut, stringResource(UiR.string.profile_sign_out), AntresolColors.Red, onSignOut, "account-sign-out")
        }
    }
}

@Composable
private fun SheetAction(icon: ImageVector, label: String, color: Color, onClick: () -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
        Text(label, style = Antresol.typography.body.copy(color = color))
    }
}
