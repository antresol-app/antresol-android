package app.antresol.tv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import app.antresol.core.pairing.PairCode
import app.antresol.core.pairing.PairingApi
import app.antresol.core.pairing.PairingFlow
import app.antresol.core.pairing.PairingState
import app.antresol.core.pairing.StartedPairing
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.R
import app.antresol.ui.R as UiR
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.tv.ui.userMessage
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.QrCodeImage
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * «Sign in with your phone» (server contract §2), the TV's default sign-in once a server is known:
 * the QR of the pairing URL on the right with the code in large text and a countdown under it;
 * title, text and the remote fallbacks («Sign In with the Remote», «Change Server») on the left,
 * like the Apple TV app. The [PairingFlow] polls while the screen is visible (it stops in the
 * background and starts over with a new code on return); an expired code is replaced on its own.
 * [initial] is a code the TV already started (set up by phone): it is shown and polled first.
 * On approval the token is stored through [SessionManager] like a password sign-in, which moves
 * the app to the signed-in shell. A declined request offers a new code.
 */
@Composable
fun PhoneSignInScreen(
    session: SessionManager,
    chooser: AuthState.ChooseProfile,
    pairing: PairingApi,
    onUseRemote: () -> Unit,
    onChangeServer: () -> Unit,
    initial: StartedPairing? = null,
    notice: String? = null,
    now: () -> Long = System::currentTimeMillis,
) {
    val resources = LocalContext.current.resources
    var attempt by remember { mutableIntStateOf(0) }
    val states = remember(pairing, attempt) {
        PairingFlow(pairing, session::deviceInfo, now, initial = initial.takeIf { attempt == 0 }).states()
    }
    val state by states.collectAsStateWithLifecycle(PairingState.Starting)
    var signInError by remember { mutableStateOf<String?>(null) }
    var serverId by remember { mutableStateOf<String?>(null) }
    // The page to type the code on stays the same between codes: keep the last one while there is none.
    var pairUrl by remember { mutableStateOf(chooser.serverUrl) }

    LaunchedEffect(state) {
        when (val current = state) {
            is PairingState.Pending -> {
                serverId = current.serverId
                pairUrl = current.url
            }
            is PairingState.Approved -> try {
                session.signInWithPairing(current.token, current.user, serverId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                signInError = e.userMessage(resources)
            }
            else -> Unit
        }
    }

    val offerNewCode = state is PairingState.Denied || (state is PairingState.Approved && signInError != null)
    val remoteFocus = remember { FocusRequester() }
    val newCodeFocus = remember { FocusRequester() }
    LaunchedEffect(offerNewCode) { (if (offerNewCode) newCodeFocus else remoteFocus).tryRequestFocus() }

    QrScreenLayout(
        modifier = Modifier.testTag("phone-sign-in"),
        qr = {
            QrPanel(state)
            Spacer(Modifier.height(18.dp))
            CodeAndStatus(
                state = state,
                signInError = signInError,
                now = now,
                offerNewCode = offerNewCode,
                newCodeFocus = newCodeFocus,
                onNewCode = {
                    signInError = null
                    attempt++
                },
            )
        },
    ) {
        Text(stringResource(R.string.pair_sign_in_with_phone), style = Antresol.typography.pageTitle)
        Text(
            stringResource(R.string.pair_instructions, pairPage(pairUrl)),
            style = Antresol.typography.body,
            color = AntresolColors.LabelSecondary,
        )
        if (notice != null) {
            Text(notice, style = Antresol.typography.caption, color = AntresolColors.Orange, modifier = Modifier.testTag("pair-notice"))
        }
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton(
                text = stringResource(R.string.pair_use_remote),
                onClick = onUseRemote,
                icon = AntresolIcons.SwitchProfile,
                modifier = Modifier.focusRequester(remoteFocus),
            )
            PillButton(text = stringResource(UiR.string.server_change), onClick = onChangeServer, icon = AntresolIcons.Server)
        }
        Text(
            text = stringResource(UiR.string.server_connected_to, hostPort(chooser.serverUrl)),
            style = Antresol.typography.caption,
            color = AntresolColors.LabelTertiary,
        )
    }
}

@Composable
private fun QrPanel(state: PairingState) {
    when (state) {
        is PairingState.Pending -> QrCodeImage(
            text = state.url,
            size = TvQrSize,
            contentDescription = stringResource(R.string.pair_qr_description),
            modifier = Modifier.testTag("pair-qr"),
        )
        // The tile keeps its place while there is no code, so nothing jumps.
        else -> Box(
            Modifier
                .size(TvQrSize)
                .background(AntresolColors.FillQuaternary, AntresolShapes.card),
        )
    }
}

@Composable
private fun CodeAndStatus(
    state: PairingState,
    signInError: String?,
    now: () -> Long,
    offerNewCode: Boolean,
    newCodeFocus: FocusRequester,
    onNewCode: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val pending = state as? PairingState.Pending
    Text(
        text = pending?.let { PairCode.display(it.code) } ?: "——— ———",
        style = Antresol.typography.heroTitle.copy(letterSpacing = 6.sp),
        color = if (pending != null) AntresolColors.Label else AntresolColors.LabelTertiary,
        maxLines = 1,
        modifier = Modifier.testTag("pair-code"),
    )
    Spacer(Modifier.height(6.dp))
    val status: String
    var statusColor = AntresolColors.LabelSecondary
    when (state) {
        is PairingState.Pending -> {
            var nowMs by remember(state) { mutableLongStateOf(now()) }
            LaunchedEffect(state) {
                while (true) {
                    nowMs = now()
                    delay(1_000 - nowMs % 1_000)
                }
            }
            val left = state.secondsLeft(nowMs)
            status = stringResource(R.string.pair_expires_in, "%d:%02d".format(left / 60, left % 60))
        }
        PairingState.Starting -> status = stringResource(R.string.pair_getting_code)
        is PairingState.Retrying -> {
            status = stringResource(R.string.pair_retrying, state.error.userMessage(resources))
            statusColor = AntresolColors.Orange
        }
        is PairingState.Approved -> {
            status = signInError ?: stringResource(R.string.pair_signing_in)
            if (signInError != null) statusColor = AntresolColors.Red
        }
        is PairingState.Denied -> {
            // The server's own sentence says the same ("Вход отклонён"): one line is enough.
            status = stringResource(R.string.pair_denied)
            statusColor = AntresolColors.Orange
        }
    }
    Text(
        status,
        style = Antresol.typography.caption,
        color = statusColor,
        textAlign = TextAlign.Center,
        minLines = 2,
        modifier = Modifier.testTag("pair-status"),
    )
    if (offerNewCode) {
        Spacer(Modifier.height(10.dp))
        PillButton(
            text = stringResource(R.string.pair_new_code),
            onClick = onNewCode,
            style = PillStyle.Primary,
            modifier = Modifier.focusRequester(newCodeFocus),
        )
    }
}

/** `host:port` of a server URL, as the TV shows it. */
internal fun hostPort(url: String): String = url.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: url

/** `host:port/pair` of the pairing URL's origin, as the TV tells people to open it. */
private fun pairPage(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "/pair"
    val default = (parsed.scheme == "http" && parsed.port == 80) || (parsed.scheme == "https" && parsed.port == 443)
    return (if (default) parsed.host else "${parsed.host}:${parsed.port}") + "/pair"
}
