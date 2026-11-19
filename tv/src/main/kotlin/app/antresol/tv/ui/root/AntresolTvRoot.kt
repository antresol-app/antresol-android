package app.antresol.tv.ui.root

import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.core.api.MediaUrls
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antresol.core.discovery.ServerFinder
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.ui.onboarding.ProfilesScreen
import app.antresol.tv.ui.onboarding.ServerScreen
import app.antresol.tv.ui.onboarding.TvSetupScreen
import app.antresol.core.pairing.PairingTicket
import app.antresol.core.tvsetup.LocalAddresses
import app.antresol.core.tvsetup.TvSetupCoordinator
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.net.InetAddress
import app.antresol.tv.ui.shell.MainShell
import app.antresol.ui.components.BrandSplash
import app.antresol.ui.theme.AntresolMotion

/**
 * Top-level switch: splash → set up with the phone (or the typed address) → sign in with the
 * phone (or the profile picker) → signed-in shell. [ticket] carries the code the TV started when
 * a phone set it up to the sign-in QR; [lanAddress] is where the setup listener listens.
 */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AntresolTvRoot(
    session: SessionManager,
    finder: ServerFinder = ServerFinder.None,
    ticket: PairingTicket = remember { PairingTicket() },
    lanAddress: () -> InetAddress? = LocalAddresses::lanIpv4,
) {
    val state by session.state.collectAsStateWithLifecycle()
    val setupHandler = remember(session, ticket) { TvSetupCoordinator(session, ticket) }
    // «Enter the Address with the Remote» on the setup screen.
    var typing by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (session.state.value == AuthState.Starting) session.restore()
    }
    updateTransition(targetState = state, label = "root").Crossfade(
        animationSpec = tween(AntresolMotion.MEDIUM_MS),
        contentKey = { it.screenKey() },
    ) { current ->
        when (current) {
            AuthState.Starting -> BrandSplash()
            AuthState.NoServer -> if (typing) {
                ServerScreen(session, finder, onBack = { typing = false })
            } else {
                TvSetupScreen(session, finder, setupHandler, onEnterAddress = { typing = true }, lanAddress = lanAddress)
            }
            is AuthState.ChooseProfile -> {
                // The next «Change Server» starts on the phone screen again.
                LaunchedEffect(Unit) { typing = false }
                // Before sign-in too: the profiles' photos are on this server (they need no token).
                CompositionLocalProvider(LocalMediaUrls provides remember(current.serverUrl) { MediaUrls(current.serverUrl) }) {
                    ProfilesScreen(session, current, ticket = ticket)
                }
            }
            // A new profile gets a fresh navigation stack.
            is AuthState.SignedIn -> key(current.session.serverId, current.session.userId) { MainShell(session, current) }
        }
    }
}

/** Screens only change when the kind of state (or the signed-in profile) changes, not on user refreshes. */
private fun AuthState.screenKey(): Any = when (this) {
    AuthState.Starting -> "starting"
    AuthState.NoServer -> "server"
    is AuthState.ChooseProfile -> "profiles:$serverUrl"
    is AuthState.SignedIn -> "shell:${session.serverId}:${session.userId}"
}
