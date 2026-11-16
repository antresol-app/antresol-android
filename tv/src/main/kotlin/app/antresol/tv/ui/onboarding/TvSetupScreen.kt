package app.antresol.tv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.Text
import app.antresol.core.api.InvalidServerAddressException
import app.antresol.core.discovery.DiscoveredServer
import app.antresol.core.discovery.DiscoveryState
import app.antresol.core.discovery.ServerFinder
import app.antresol.core.session.NotAntresolServerException
import app.antresol.core.session.ServerNeedsSetupException
import app.antresol.core.session.SessionManager
import app.antresol.core.tvsetup.LocalAddresses
import app.antresol.core.tvsetup.TvSetupHandler
import app.antresol.core.tvsetup.TvSetupLink
import app.antresol.core.tvsetup.TvSetupListener
import app.antresol.tv.R
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.userMessage
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.QrCodeImage
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetAddress

/** The setup QR while the screen is visible. */
private sealed interface SetupQr {
    data object Opening : SetupQr

    data class Ready(val link: TvSetupLink) : SetupQr

    /** The TV has no LAN IPv4 (or the port could not be opened): only the remote fallbacks. */
    data object NoNetwork : SetupQr
}

/**
 * First-run «Set Up with Your Phone» («TV setup by phone»): shown while no server is configured
 * (and after «Change Server»). The QR on the right is the TV's setup URL; a phone sends the
 * server there ([handler] checks it and starts pairing, then the pairing QR follows). On the
 * left, the remote fallbacks: the servers found on this network and «Enter the Address with the
 * Remote» ([onEnterAddress], the typed-address screen).
 *
 * The listener runs only while the screen is visible (started): a new nonce each time, renewed
 * when it expires, closed when the screen goes away or setup ends. [lanAddress] is the TV's LAN
 * IPv4 (tests pass loopback).
 */
@Composable
fun TvSetupScreen(
    session: SessionManager,
    finder: ServerFinder,
    handler: TvSetupHandler,
    onEnterAddress: () -> Unit,
    lanAddress: () -> InetAddress? = LocalAddresses::lanIpv4,
) {
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var qr by remember { mutableStateOf<SetupQr>(SetupQr.Opening) }
    var connecting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val discovery by remember(finder) { finder.states() }.collectAsStateWithLifecycle(DiscoveryState())
    val manualFocus = remember { FocusRequester() }
    val firstServerFocus = remember { FocusRequester() }
    // Until the user presses a key the screen may move focus to the first server found.
    var untouched by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(handler) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            val address = withContext(Dispatchers.IO) { runCatching { lanAddress() }.getOrNull() }
            if (address == null) {
                qr = SetupQr.NoNetwork
                awaitCancellation()
            }
            val listener = TvSetupListener(handler)
            try {
                var link = withContext(Dispatchers.IO) { listener.start(address) }
                while (true) {
                    qr = SetupQr.Ready(link)
                    val expiresAt = listener.nonceExpiresAt() ?: break
                    delay((expiresAt - System.currentTimeMillis()).coerceAtLeast(1_000))
                    if (listener.stopped) break
                    link = listener.renew()
                }
                awaitCancellation()
            } catch (e: IOException) {
                qr = SetupQr.NoNetwork
                awaitCancellation()
            } finally {
                listener.stop()
            }
        }
    }

    LaunchedEffect(Unit) { manualFocus.tryRequestFocus() }
    val found = discovery.servers.isNotEmpty()
    LaunchedEffect(found) {
        if (found && untouched) firstServerFocus.tryRequestFocus()
    }

    fun choose(server: DiscoveredServer) {
        if (connecting) return
        connecting = true
        error = null
        scope.launch {
            try {
                session.connect(server.baseUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = when (e) {
                    is NotAntresolServerException -> resources.getString(UiR.string.server_not_antresol)
                    is ServerNeedsSetupException, is InvalidServerAddressException -> e.userMessage(resources)
                    is IOException -> resources.getString(UiR.string.server_unreachable)
                    else -> e.userMessage(resources)
                }
                connecting = false
            }
        }
    }

    val ready = qr as? SetupQr.Ready
    QrScreenLayout(
        modifier = Modifier
            .testTag("tv-setup")
            .onPreviewKeyEvent {
                untouched = false
                false
            },
        qr = if (qr == SetupQr.NoNetwork) null else {
            {
                if (ready != null) {
                    QrCodeImage(
                        text = ready.link.toString(),
                        size = TvQrSize,
                        contentDescription = stringResource(R.string.setup_qr_description),
                        modifier = Modifier.testTag("setup-qr"),
                    )
                } else {
                    Box(Modifier.size(TvQrSize).background(AntresolColors.FillQuaternary, AntresolShapes.card))
                }
            }
        },
    ) {
        Text(stringResource(R.string.setup_title), style = Antresol.typography.pageTitle)
        Text(
            stringResource(if (qr == SetupQr.NoNetwork) R.string.setup_no_network else R.string.setup_hint),
            style = Antresol.typography.body,
            color = if (qr == SetupQr.NoNetwork) AntresolColors.Orange else AntresolColors.LabelSecondary,
            modifier = Modifier.testTag("setup-hint"),
        )
        Spacer(Modifier.height(6.dp))
        DiscoveredServerList(
            state = discovery,
            enabled = !connecting,
            onChoose = ::choose,
            firstFocus = firstServerFocus,
            title = stringResource(R.string.setup_servers_title),
            centered = false,
        )
        if (error != null || connecting) {
            Text(
                text = error ?: stringResource(UiR.string.server_connecting),
                style = Antresol.typography.caption,
                color = if (error != null) AntresolColors.Red else AntresolColors.LabelSecondary,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        PillButton(
            text = stringResource(R.string.setup_enter_address),
            onClick = onEnterAddress,
            icon = AntresolIcons.Server,
            modifier = Modifier.focusRequester(manualFocus).testTag("setup-enter-address"),
        )
    }
}
