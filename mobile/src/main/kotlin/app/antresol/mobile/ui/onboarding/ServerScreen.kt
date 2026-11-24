package app.antresol.mobile.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antresol.core.api.HostKind
import app.antresol.core.discovery.DiscoveredServer
import app.antresol.core.discovery.DiscoveryState
import app.antresol.core.discovery.ServerFinder
import app.antresol.core.session.SessionManager
import app.antresol.mobile.ui.components.FormError
import app.antresol.mobile.ui.components.GlassTextField
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.ui.onboarding.rememberServerConnectState
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.LogoTile
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.launch

/**
 * First launch (or "Change Server"): the servers found on the network ([finder]) above the address
 * field. Choosing one connects exactly like typing its address; the address is validated with
 * `/api/health`.
 */
@Composable
fun ServerScreen(session: SessionManager, finder: ServerFinder) {
    val focus = LocalFocusManager.current
    var address by rememberSaveable { mutableStateOf("") }
    val connection = rememberServerConnectState(session)
    val busy = connection.busy
    val error = connection.error
    val discovery by remember(finder) { finder.states() }.collectAsStateWithLifecycle(DiscoveryState())

    LaunchedEffect(Unit) {
        if (address.isEmpty()) session.lastServerUrl()?.let { address = it }
    }

    fun connect() {
        if (busy || address.isBlank()) return
        focus.clearFocus()
        connection.connect(address)
    }

    fun choose(server: DiscoveredServer) {
        address = server.baseUrl
        connection.error = null
        connect()
    }

    OnboardingScaffold {
        LogoTile(size = 84.dp)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.server_title), style = Antresol.typography.pageTitle, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.server_subtitle_discovery),
            style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        DiscoveredServerList(discovery, enabled = !busy, onChoose = ::choose)
        if (discovery.servers.isNotEmpty()) {
            Text(
                stringResource(R.string.server_manual_title).uppercase(),
                style = Antresol.typography.eyebrow,
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp),
            )
        }
        GlassTextField(
            value = address,
            onValueChange = {
                address = it
                connection.error = null
            },
            placeholder = stringResource(R.string.server_address_hint),
            leadingIcon = AntresolIcons.Server,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect() }),
            modifier = Modifier.testTag("server-address"),
        )
        if (remember(address) { HostKind.isClearOverInternet(address) }) {
            Text(
                stringResource(R.string.server_clear_text_warning),
                style = Antresol.typography.caption.copy(color = AntresolColors.Orange),
                textAlign = TextAlign.Center,
                modifier = Modifier.testTag("server-clear-text-warning"),
            )
        }
        error?.let {
            FormError(it)
        }
        PrimaryPillButton(
            text = stringResource(if (busy) R.string.server_connecting else R.string.server_connect),
            onClick = ::connect,
            enabled = !busy && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
