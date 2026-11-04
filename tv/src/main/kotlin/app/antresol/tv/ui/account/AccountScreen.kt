package app.antresol.tv.ui.account

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.session.AuthState
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** The avatar menu: who is signed in, switch profile, sign out, change server. */
@Composable
fun AccountScreen(
    state: AuthState.SignedIn,
    serverUrl: String,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onChangeServer: () -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        onScrolledToTop(true)
        first.tryRequestFocus()
    }
    val session = state.session
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        ProfileAvatar(name = session.userName, avatar = session.avatar, size = 104.dp)
        Spacer(Modifier.height(4.dp))
        Text(session.userName, style = Antresol.typography.pageTitle)
        val roles = listOfNotNull(
            stringResource(R.string.account_admin).takeIf { state.user?.isAdmin == true },
            stringResource(R.string.profile_kid).takeIf { state.user?.isKid == true },
        )
        Text(
            text = (roles + stringResource(R.string.server_connected_to, serverUrl)).joinToString(" · "),
            style = Antresol.typography.caption,
            color = AntresolColors.LabelSecondary,
        )
        Spacer(Modifier.height(20.dp))
        Column(
            modifier = Modifier.width(320.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PillButton(
                text = stringResource(R.string.profile_switch),
                onClick = onSwitchProfile,
                icon = AntresolIcons.SwitchProfile,
                style = PillStyle.Primary,
                modifier = Modifier.focusRequester(first),
            )
            PillButton(text = stringResource(R.string.profile_sign_out), onClick = onSignOut, icon = AntresolIcons.SignOut)
            PillButton(text = stringResource(R.string.server_change), onClick = onChangeServer, icon = AntresolIcons.Server)
        }
    }
}
