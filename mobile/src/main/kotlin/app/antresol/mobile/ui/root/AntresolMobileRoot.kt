package app.antresol.mobile.ui.root

import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.mobile.ui.onboarding.ProfilesScreen
import app.antresol.mobile.ui.onboarding.ServerScreen
import app.antresol.mobile.ui.shell.MainShell
import app.antresol.ui.components.BrandSplash
import app.antresol.ui.theme.AntresolMotion

/** Top-level switch: splash → server address → profile picker → signed-in shell. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
fun AntresolMobileRoot(session: SessionManager) {
    val state by session.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (session.state.value == AuthState.Starting) session.restore()
    }
    updateTransition(targetState = state, label = "root").Crossfade(
        animationSpec = tween(AntresolMotion.MEDIUM_MS),
        contentKey = { it.screenKey() },
    ) { current ->
        when (current) {
            AuthState.Starting -> BrandSplash()
            AuthState.NoServer -> ServerScreen(session)
            is AuthState.ChooseProfile -> ProfilesScreen(session, current)
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
