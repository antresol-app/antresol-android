package app.antresol.mobile.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.core.api.ApiException
import app.antresol.core.model.Profile
import app.antresol.core.session.AuthNotice
import app.antresol.core.session.AuthState
import app.antresol.core.session.SessionManager
import app.antresol.mobile.R as MobileR
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.GlassTextField
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.load
import app.antresol.mobile.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** "Who's watching?": the server's profiles, then a password step for protected profiles. */
@Composable
fun ProfilesScreen(session: SessionManager, state: AuthState.ChooseProfile) {
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    var profiles by remember { mutableStateOf<LoadState<List<Profile>>>(LoadState.Loading) }
    var passwordFor by rememberSaveable { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.serverUrl, reload) {
        profiles = LoadState.Loading
        profiles = load { session.profiles() }
    }

    fun attempt(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = if (e.isUnauthorized) resources.getString(MobileR.string.password_wrong) else e.userMessage(resources)
            } catch (e: Exception) {
                error = e.userMessage(resources)
            } finally {
                busy = false
            }
        }
    }

    OnboardingScaffold {
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
                        onSubmit = { password -> attempt { session.signIn(selected, password) } },
                        onCancel = {
                            passwordFor = null
                            error = null
                        },
                    )
                } else {
                    ProfilePicker(
                        profiles = loaded.value,
                        notice = state.notice,
                        error = error,
                        onSelect = { profile ->
                            attempt { if (!session.resume(profile)) passwordFor = profile.id }
                        },
                    )
                }
            }
        }
        if (passwordFor == null) {
            Spacer(Modifier.height(12.dp))
            val host = state.serverUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: state.serverUrl
            Text(
                stringResource(R.string.server_connected_to, host),
                style = Antresol.typography.caption,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.server_change),
                style = Antresol.typography.bodyEmphasis.copy(color = AntresolColors.Accent),
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable { scope.launch { session.changeServer() } }
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePicker(
    profiles: List<Profile>,
    notice: AuthNotice?,
    error: String?,
    onSelect: (Profile) -> Unit,
) {
    Text(stringResource(R.string.profiles_title), style = Antresol.typography.pageTitle, textAlign = TextAlign.Center)
    if (notice == AuthNotice.SessionExpired) {
        Text(
            stringResource(R.string.error_session_expired),
            style = Antresol.typography.body.copy(color = AntresolColors.Orange),
            textAlign = TextAlign.Center,
        )
    }
    Spacer(Modifier.height(8.dp))
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        profiles.forEach { profile ->
            Column(
                modifier = Modifier
                    .width(104.dp)
                    .clip(Antresol.shapes.smallCard)
                    .clickable(role = Role.Button) { onSelect(profile) }
                    .testTag("profile-${profile.id}")
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    ProfileAvatar(name = profile.name, avatar = profile.avatar, size = 88.dp)
                    if (profile.hasPassword) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(AntresolColors.BackgroundElevated),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(AntresolIcons.Lock, contentDescription = null, tint = AntresolColors.Label, modifier = Modifier.size(16.dp))
                        }
                    }
                }
                Text(
                    profile.name,
                    style = Antresol.typography.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
    error?.let {
        Text(it, style = Antresol.typography.caption.copy(color = AntresolColors.Red), textAlign = TextAlign.Center)
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
    var visible by rememberSaveable { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(profile.id) { focus.requestFocus() }

    ProfileAvatar(name = profile.name, avatar = profile.avatar, size = 96.dp)
    Text(
        stringResource(R.string.password_title, profile.name),
        style = Antresol.typography.sectionTitle,
        textAlign = TextAlign.Center,
    )
    GlassTextField(
        value = password,
        onValueChange = { password = it },
        placeholder = stringResource(R.string.profile_password),
        leadingIcon = AntresolIcons.Lock,
        trailingIcon = if (visible) MobileIcons.EyeOff else MobileIcons.Eye,
        trailingDescription = stringResource(if (visible) MobileR.string.password_hide else MobileR.string.password_show),
        onTrailingClick = { visible = !visible },
        concealed = !visible,
        enabled = !busy,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { if (password.isNotEmpty()) onSubmit(password) }),
        modifier = Modifier
            .focusRequester(focus)
            .testTag("password"),
    )
    error?.let {
        Text(it, style = Antresol.typography.caption.copy(color = AntresolColors.Red), textAlign = TextAlign.Center)
    }
    PrimaryPillButton(
        text = stringResource(R.string.profile_sign_in),
        onClick = { onSubmit(password) },
        enabled = !busy && password.isNotEmpty(),
        modifier = Modifier.fillMaxWidth(),
    )
    GlassPillButton(
        text = stringResource(R.string.action_cancel),
        onClick = onCancel,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    )
}
