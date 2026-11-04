package app.antresol.tv.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthNotice
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.TvTextField
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.tv.ui.load
import app.antresol.tv.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** "Who's watching?": the profiles of the server, then a password step for protected profiles. */
@Composable
fun ProfilesScreen(session: SessionManager, state: AuthState.ChooseProfile) {
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var profiles by remember { mutableStateOf<LoadState<List<Profile>>>(LoadState.Loading) }
    var passwordFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var lastFocused by rememberSaveable { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.serverUrl, reload) {
        profiles = LoadState.Loading
        profiles = load { session.profiles() }
    }

    fun select(profile: Profile) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                if (!session.resume(profile)) passwordFor = profile.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage(resources)
            } finally {
                busy = false
            }
        }
    }

    fun signIn(profile: Profile, password: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                session.signIn(profile, password)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.userMessage(resources)
            } finally {
                busy = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(0f to AntresolColors.IdentityBackground, 1f to AntresolColors.Background)),
    ) {
        when (val loaded = profiles) {
            LoadState.Loading -> LoadingView()
            is LoadState.Failed -> ErrorView(loaded.error, onRetry = { reload++ })
            is LoadState.Ready -> {
                val selected = loaded.value.firstOrNull { it.id == passwordFor }
                if (selected != null) {
                    BackHandler {
                        passwordFor = null
                        error = null
                    }
                    PasswordStep(
                        profile = selected,
                        busy = busy,
                        error = error,
                        onSubmit = { signIn(selected, it) },
                        onCancel = {
                            passwordFor = null
                            error = null
                        },
                    )
                } else {
                    ProfilePicker(
                        profiles = loaded.value,
                        serverUrl = state.serverUrl,
                        message = error ?: state.notice?.let { stringResource(R.string.error_session_expired) },
                        isError = error != null || state.notice == AuthNotice.SessionExpired,
                        initialFocus = lastFocused,
                        onFocused = { lastFocused = it },
                        onSelect = ::select,
                        onChangeServer = { scope.launch { session.changeServer() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfilePicker(
    profiles: List<Profile>,
    serverUrl: String,
    message: String?,
    isError: Boolean,
    initialFocus: Long?,
    onFocused: (Long) -> Unit,
    onSelect: (Profile) -> Unit,
    onChangeServer: () -> Unit,
) {
    val requesters = remember(profiles) { profiles.associate { it.id to FocusRequester() } }
    LaunchedEffect(profiles) {
        val target = requesters[initialFocus] ?: requesters[profiles.firstOrNull()?.id]
        target?.tryRequestFocus()
    }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(stringResource(R.string.profiles_title), style = Antresol.typography.pageTitle)
        Spacer(Modifier.height(40.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
            items(profiles, key = { it.id }) { profile ->
                ProfileTile(
                    profile = profile,
                    onClick = { onSelect(profile) },
                    modifier = Modifier
                        .focusRequester(requesters.getValue(profile.id))
                        .onFocusChanged { if (it.isFocused) onFocused(profile.id) },
                )
            }
        }
        Spacer(Modifier.height(28.dp))
        Text(
            text = message.orEmpty(),
            style = Antresol.typography.caption,
            color = if (isError) AntresolColors.Orange else AntresolColors.LabelSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(560.dp),
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.server_connected_to, serverUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: serverUrl),
            style = Antresol.typography.caption,
            color = AntresolColors.LabelTertiary,
        )
        Spacer(Modifier.height(12.dp))
        PillButton(text = stringResource(R.string.server_change), onClick = onChangeServer, icon = AntresolIcons.Server)
    }
}

@Composable
private fun ProfileTile(profile: Profile, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.width(132.dp),
    ) {
        Surface(
            onClick = onClick,
            modifier = modifier
                .size(112.dp)
                .onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                pressedContainerColor = Color.Transparent,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.FOCUS_SCALE),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(BorderStroke(3.dp, AntresolColors.Label), inset = 4.dp, shape = CircleShape),
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = Glow(elevationColor = Color.White.copy(alpha = 0.3f), elevation = 18.dp),
            ),
        ) {
            ProfileAvatar(name = profile.name, avatar = profile.avatar, size = 112.dp)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = profile.name,
                style = Antresol.typography.title,
                color = if (focused) AntresolColors.Label else AntresolColors.LabelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (profile.hasPassword) {
                Icon(
                    imageVector = AntresolIcons.Lock,
                    contentDescription = stringResource(R.string.profile_password),
                    tint = AntresolColors.LabelTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }
}

@Composable
private fun PasswordStep(
    profile: Profile,
    busy: Boolean,
    error: String?,
    onSubmit: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var password by rememberSaveable(profile.id) { mutableStateOf("") }
    val fieldFocus = remember { FocusRequester() }
    LaunchedEffect(profile.id) { fieldFocus.tryRequestFocus() }
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        ProfileAvatar(name = profile.name, avatar = profile.avatar, size = 96.dp)
        Text(stringResource(R.string.password_title, profile.name), style = Antresol.typography.sectionTitle)
        TvTextField(
            value = password,
            onValueChange = { password = it },
            placeholder = stringResource(R.string.profile_password),
            onSubmit = { if (password.isNotEmpty()) onSubmit(password) },
            password = true,
            modifier = Modifier
                .width(420.dp)
                .focusRequester(fieldFocus),
        )
        Text(
            text = error ?: stringResource(R.string.password_show_keyboard),
            style = Antresol.typography.caption,
            color = if (error != null) AntresolColors.Red else AntresolColors.LabelTertiary,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(420.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            PillButton(
                text = stringResource(R.string.profile_sign_in),
                onClick = { onSubmit(password) },
                style = PillStyle.Primary,
                enabled = !busy && password.isNotEmpty(),
            )
            PillButton(text = stringResource(R.string.action_cancel), onClick = onCancel)
        }
        Spacer(Modifier.fillMaxWidth())
    }
}
