package app.antresol.tv.ui.onboarding

import androidx.activity.compose.BackHandler
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
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.R
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
 * «Sign in with your phone» (server contract §2): a big QR of the pairing URL, the code in large
 * text and a countdown. The [PairingFlow] polls while the screen is visible (it stops in the
 * background and starts over with a new code on return); an expired code is replaced on its own.
 * On approval the token is stored through [SessionManager] like a password sign-in, which moves
 * the app to the signed-in shell. A declined request offers a new code. Back returns to the
 * profile picker ([onBack]).
 */
@Composable
fun PhoneSignInScreen(
    session: SessionManager,
    chooser: AuthState.ChooseProfile,
    pairing: PairingApi,
    onBack: () -> Unit,
    now: () -> Long = System::currentTimeMillis,
) {
    val resources = LocalContext.current.resources
    var attempt by remember { mutableIntStateOf(0) }
    val states = remember(pairing, attempt) { PairingFlow(pairing, session::deviceInfo, now).states() }
    val state by states.collectAsStateWithLifecycle(PairingState.Starting)
    var signInError by remember { mutableStateOf<String?>(null) }
    var serverId by remember { mutableStateOf<String?>(null) }
    // The page to type the code on stays the same between codes: keep the last one while there is none.
    var pairUrl by remember { mutableStateOf(chooser.serverUrl) }

    BackHandler(onBack = onBack)

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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to AntresolColors.IdentityBackground, 1f to AntresolColors.Background))
            .testTag("phone-sign-in"),
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
            QrPanel(state, size = QrSize)
            Details(
                state = state,
                pairUrl = pairUrl,
                signInError = signInError,
                now = now,
                onNewCode = {
                    signInError = null
                    attempt++
                },
                onBack = onBack,
            )
        }
    }
}

private val QrSize = 300.dp

@Composable
private fun QrPanel(state: PairingState, size: Dp) {
    when (state) {
        is PairingState.Pending -> QrCodeImage(
            text = state.url,
            size = size,
            contentDescription = stringResource(R.string.pair_qr_description),
            modifier = Modifier.testTag("pair-qr"),
        )
        // The tile keeps its place while there is no code, so the text does not jump.
        else -> Box(
            Modifier
                .size(size)
                .background(AntresolColors.FillQuaternary, AntresolShapes.card),
        )
    }
}

@Composable
private fun Details(
    state: PairingState,
    pairUrl: String,
    signInError: String?,
    now: () -> Long,
    onNewCode: () -> Unit,
    onBack: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val primaryFocus = remember { FocusRequester() }
    Column(Modifier.width(440.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.pair_sign_in_with_phone), style = Antresol.typography.pageTitle)
        val pending = state as? PairingState.Pending
        val pairPage = pairPage(pairUrl)
        Text(
            stringResource(R.string.pair_instructions, pairPage),
            style = Antresol.typography.body,
            color = AntresolColors.LabelSecondary,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = pending?.let { PairCode.display(it.code) } ?: "——— ———",
            style = Antresol.typography.heroTitle.copy(letterSpacing = 6.sp),
            color = if (pending != null) AntresolColors.Label else AntresolColors.LabelTertiary,
            modifier = Modifier.testTag("pair-code"),
        )
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
        Text(status, style = Antresol.typography.caption, color = statusColor, minLines = 2, modifier = Modifier.testTag("pair-status"))
        val offerNewCode = state is PairingState.Denied || (state is PairingState.Approved && signInError != null)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            if (offerNewCode) {
                PillButton(
                    text = stringResource(R.string.pair_new_code),
                    onClick = onNewCode,
                    style = PillStyle.Primary,
                    modifier = Modifier.focusRequester(primaryFocus),
                )
            }
            PillButton(
                text = stringResource(R.string.pair_use_remote),
                onClick = onBack,
                icon = AntresolIcons.SwitchProfile,
                modifier = if (offerNewCode) Modifier else Modifier.focusRequester(primaryFocus),
            )
        }
        LaunchedEffect(offerNewCode) { primaryFocus.tryRequestFocus() }
    }
}

/** `host:port/pair` of the pairing URL's origin, as the TV tells people to open it. */
private fun pairPage(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return "/pair"
    val default = (parsed.scheme == "http" && parsed.port == 80) || (parsed.scheme == "https" && parsed.port == 443)
    return (if (default) parsed.host else "${parsed.host}:${parsed.port}") + "/pair"
}
