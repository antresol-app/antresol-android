package app.antresol.tv.ui.settings

import android.app.Activity
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.DeviceSession
import app.antresol.core.model.Health
import app.antresol.core.model.LibraryStatus
import app.antresol.core.model.Quality
import app.antresol.core.session.AuthState
import app.antresol.tv.BuildConfig
import app.antresol.tv.R as TvR
import app.antresol.tv.settings.AppLanguage
import app.antresol.tv.settings.TvSettings
import app.antresol.tv.ui.LoadState
import app.antresol.tv.ui.components.MenuOption
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.listRowColors
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.tv.ui.load
import app.antresol.tv.ui.player.qualityLabel
import app.antresol.tv.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.format.TrackLabels
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Server facts for the Settings page (each null when the server did not answer) and the devices
 * signed in to this profile (`/api/me/devices`), which can be signed out one by one.
 */
class SettingsViewModel(private val client: AntresolClient) : ViewModel() {
    var health: Health? by mutableStateOf(null)
        private set
    var library: LibraryStatus? by mutableStateOf(null)
        private set

    /** This device first. */
    var devices: LoadState<List<DeviceSession>> by mutableStateOf(LoadState.Loading)
        private set

    /** The last sign-out of another device failed. */
    var revokeFailed: Boolean by mutableStateOf(false)
        private set

    val serverUrl: String get() = client.baseUrl

    init {
        viewModelScope.launch { health = runCatching { client.health() }.getOrNull() }
        viewModelScope.launch { library = runCatching { client.libraryStatus() }.getOrNull() }
        loadDevices()
    }

    fun loadDevices() {
        viewModelScope.launch { devices = load { client.devices().items.sortedByDescending { it.current } } }
    }

    /** Signs [device] out of this profile, then reloads the list. */
    fun revoke(device: DeviceSession) {
        if (device.current) return
        viewModelScope.launch {
            revokeFailed = try {
                client.revokeDevice(device.id)
                false
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                true
            }
            loadDevices()
        }
    }
}

private enum class SettingsMenu { Quality, Subtitles, Language, ConfirmSignOut, ConfirmChangeServer, ConfirmRevoke }

/**
 * Settings, opened from the avatar: the profile (switch, sign out), playback defaults (video
 * quality, subtitle language), the app language, the server (address, version, library, change),
 * the profile's signed-in devices and about the app. Choices open a side panel (the shell hides its
 * nav meanwhile, see [onMenuOpenChanged]); a new language restarts the app's screens from Home, so
 * text from the server reloads in that language too. Sign Out, Change Server and signing out
 * another device ask first, with Cancel focused.
 */
@Composable
fun SettingsScreen(
    state: AuthState.SignedIn,
    viewModel: SettingsViewModel,
    settings: TvSettings,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onChangeServer: () -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    onMenuOpenChanged: (Boolean) -> Unit,
) {
    val preferences by settings.preferences.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf<SettingsMenu?>(null) }
    var revoking by remember { mutableStateOf<DeviceSession?>(null) }
    LaunchedEffect(menu != null) { onMenuOpenChanged(menu != null) }
    // The control that opened each panel gets focus back when the panel closes.
    val openers = remember { SettingsMenu.entries.associateWith { FocusRequester() } }
    var lastMenu by remember { mutableStateOf<SettingsMenu?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(menu) {
        val current = menu
        if (current != null) {
            lastMenu = current
        } else {
            // A signed-out device's row is gone: fall back to the first setting.
            lastMenu?.let { if (!openers.getValue(it).tryRequestFocus()) first.tryRequestFocus() }
        }
    }
    val activity = LocalContext.current as? Activity
    BackHandler(enabled = menu != null) { menu = null }
    LaunchedEffect(Unit) {
        onScrolledToTop(true)
        first.tryRequestFocus()
    }

    Box(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 48.dp, end = 48.dp, top = 84.dp),
            horizontalArrangement = Arrangement.spacedBy(48.dp),
        ) {
            ProfileColumn(
                state = state,
                onSwitchProfile = onSwitchProfile,
                onSignOut = { menu = SettingsMenu.ConfirmSignOut },
                switchModifier = Modifier.focusRequester(first),
                signOutModifier = Modifier.focusRequester(openers.getValue(SettingsMenu.ConfirmSignOut)),
            )
            LazyColumn(
                contentPadding = PaddingValues(bottom = 48.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
            ) {
                item { Section(stringResource(TvR.string.settings_playback)) }
                item {
                    SettingRow(
                        label = stringResource(TvR.string.settings_default_quality),
                        value = qualityLabel(preferences.defaultQuality),
                        onClick = { menu = SettingsMenu.Quality },
                        modifier = Modifier.focusRequester(openers.getValue(SettingsMenu.Quality)),
                    )
                }
                item {
                    SettingRow(
                        label = stringResource(TvR.string.settings_subtitles),
                        value = subtitleLabel(preferences.subtitleLanguage),
                        onClick = { menu = SettingsMenu.Subtitles },
                        modifier = Modifier.focusRequester(openers.getValue(SettingsMenu.Subtitles)),
                    )
                }
                item { Section(stringResource(TvR.string.settings_language)) }
                item {
                    SettingRow(
                        label = stringResource(TvR.string.settings_app_language),
                        value = languageLabel(preferences.language),
                        onClick = { menu = SettingsMenu.Language },
                        modifier = Modifier.focusRequester(openers.getValue(SettingsMenu.Language)),
                    )
                }
                item { Section(stringResource(TvR.string.settings_server)) }
                item { InfoRow(stringResource(TvR.string.settings_server_address), viewModel.serverUrl) }
                item {
                    InfoRow(
                        stringResource(TvR.string.settings_server_version),
                        viewModel.health?.version?.ifBlank { null } ?: stringResource(TvR.string.settings_unknown),
                    )
                }
                viewModel.library?.let { library ->
                    item {
                        InfoRow(
                            stringResource(TvR.string.settings_library),
                            stringResource(TvR.string.settings_library_counts, library.movies, library.episodes, library.albums, library.books + library.audiobooks + library.comics),
                        )
                    }
                }
                item {
                    Box(Modifier.padding(top = 10.dp)) {
                        PillButton(
                            stringResource(R.string.server_change),
                            onClick = { menu = SettingsMenu.ConfirmChangeServer },
                            icon = AntresolIcons.Server,
                            modifier = Modifier.focusRequester(openers.getValue(SettingsMenu.ConfirmChangeServer)),
                        )
                    }
                }
                devicesSection(
                    devices = viewModel.devices,
                    revokeFailed = viewModel.revokeFailed,
                    revoking = revoking,
                    revokeOpener = openers.getValue(SettingsMenu.ConfirmRevoke),
                    onRevoke = { device ->
                        revoking = device
                        menu = SettingsMenu.ConfirmRevoke
                    },
                    onRetry = viewModel::loadDevices,
                )
            }
        }
        when (menu) {
            SettingsMenu.Quality -> OptionsPanel(
                title = stringResource(TvR.string.settings_default_quality),
                note = stringResource(TvR.string.settings_default_quality_hint),
                options = Quality.ALL.map { value ->
                    MenuOption(qualityLabel(value), value == preferences.defaultQuality) { settings.update { it.copy(defaultQuality = value) } }
                },
                onDismiss = { menu = null },
            )
            SettingsMenu.Subtitles -> OptionsPanel(
                title = stringResource(TvR.string.settings_subtitles),
                note = stringResource(TvR.string.settings_subtitles_hint),
                options = (listOf("") + TvSettings.SUBTITLE_LANGUAGES).map { code ->
                    MenuOption(subtitleLabel(code), code == preferences.subtitleLanguage) { settings.update { it.copy(subtitleLanguage = code) } }
                },
                onDismiss = { menu = null },
            )
            SettingsMenu.Language -> OptionsPanel(
                title = stringResource(TvR.string.settings_app_language),
                options = AppLanguage.entries.map { language ->
                    MenuOption(languageLabel(language), language == preferences.language) {
                        if (language != preferences.language) {
                            settings.update { it.copy(language = language) }
                            activity?.let(::restartInNewLanguage)
                        }
                    }
                },
                onDismiss = { menu = null },
            )
            SettingsMenu.ConfirmSignOut -> ConfirmPanel(
                title = stringResource(TvR.string.settings_sign_out_confirm, state.session.userName),
                confirmLabel = stringResource(R.string.profile_sign_out),
                onConfirm = onSignOut,
                onDismiss = { menu = null },
            )
            SettingsMenu.ConfirmChangeServer -> ConfirmPanel(
                title = stringResource(TvR.string.settings_change_server_confirm, viewModel.serverUrl),
                confirmLabel = stringResource(R.string.server_change),
                onConfirm = onChangeServer,
                onDismiss = { menu = null },
            )
            SettingsMenu.ConfirmRevoke -> revoking?.let { device ->
                ConfirmPanel(
                    title = stringResource(R.string.settings_revoke_title, device.label()),
                    note = stringResource(R.string.settings_revoke_body),
                    confirmLabel = stringResource(R.string.settings_revoke),
                    onConfirm = { viewModel.revoke(device) },
                    onDismiss = { menu = null },
                )
            }
            null -> Unit
        }
    }
}

@Composable
private fun ProfileColumn(
    state: AuthState.SignedIn,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    @Suppress("ModifierParameter") switchModifier: Modifier,
    signOutModifier: Modifier,
) {
    val session = state.session
    Column(
        modifier = Modifier
            .width(250.dp)
            .fillMaxHeight()
            .padding(bottom = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(stringResource(R.string.nav_settings), style = Antresol.typography.pageTitle, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        ProfileAvatar(name = session.userName, avatar = session.avatar, size = 96.dp)
        Text(session.userName, style = Antresol.typography.sectionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        val roles = listOfNotNull(
            stringResource(R.string.account_admin).takeIf { state.user?.isAdmin == true },
            stringResource(R.string.profile_kid).takeIf { state.user?.isKid == true },
        )
        if (roles.isNotEmpty()) Text(roles.joinToString(" · "), style = Antresol.typography.caption)
        Spacer(Modifier.height(8.dp))
        PillButton(
            text = stringResource(R.string.profile_switch),
            onClick = onSwitchProfile,
            icon = AntresolIcons.SwitchProfile,
            style = PillStyle.Primary,
            modifier = switchModifier,
        )
        PillButton(text = stringResource(R.string.profile_sign_out), onClick = onSignOut, icon = AntresolIcons.SignOut, modifier = signOutModifier)
        Spacer(Modifier.weight(1f))
        // About
        Text(
            stringResource(TvR.string.settings_app_version, BuildConfig.VERSION_NAME),
            style = Antresol.typography.caption.copy(color = AntresolColors.Label),
            textAlign = TextAlign.Center,
        )
        Text(stringResource(TvR.string.settings_about_text), style = Antresol.typography.caption, textAlign = TextAlign.Center)
    }
}

/** Cancel (focused) or the destructive action. */
@Composable
private fun ConfirmPanel(title: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit, note: String? = null) {
    OptionsPanel(
        title = title,
        note = note,
        options = listOf(
            MenuOption(stringResource(R.string.action_cancel), selected = false) {},
            MenuOption(confirmLabel, selected = false, onSelect = onConfirm),
        ),
        onDismiss = onDismiss,
        focusIndex = 0,
    )
}

@Composable
private fun Section(title: String) {
    Text(
        title.uppercase(),
        style = Antresol.typography.eyebrow,
        modifier = Modifier.padding(start = 14.dp, top = 18.dp, bottom = 6.dp),
    )
}

/** A focusable setting with its current value; OK opens its choices. */
@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String = "") {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.control),
        colors = listRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(label, style = Antresol.typography.bodyEmphasis, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (detail.isNotBlank()) {
                    Text(detail, style = Antresol.typography.caption, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1)
                }
            }
            Text(value, style = Antresol.typography.body, color = LocalContentColor.current.copy(alpha = 0.7f), maxLines = 1)
            Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.7f), modifier = Modifier.width(16.dp))
        }
    }
}

/**
 * "Signed-in Devices": this device (not focusable), then every other device of this profile; OK on
 * one asks to sign it out. Failures show a line with Try Again.
 */
private fun LazyListScope.devicesSection(
    devices: LoadState<List<DeviceSession>>,
    revokeFailed: Boolean,
    revoking: DeviceSession?,
    revokeOpener: FocusRequester,
    onRevoke: (DeviceSession) -> Unit,
    onRetry: () -> Unit,
) {
    item { Section(stringResource(R.string.settings_devices)) }
    when (devices) {
        LoadState.Loading -> item { InfoRow(stringResource(R.string.state_loading), "") }
        is LoadState.Failed -> item {
            val message = devices.error.userMessage(LocalContext.current.resources)
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(message, style = Antresol.typography.body, color = AntresolColors.LabelSecondary, modifier = Modifier.weight(1f))
                PillButton(stringResource(R.string.action_retry), onClick = onRetry)
            }
        }
        is LoadState.Ready -> {
            if (revokeFailed) {
                item {
                    Text(
                        stringResource(R.string.settings_revoke_failed),
                        style = Antresol.typography.caption,
                        color = AntresolColors.Red,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
                    )
                }
            }
            items(devices.value, key = { "device-${it.id}" }) { device ->
                if (device.current) {
                    InfoRow(device.label(), stringResource(R.string.settings_this_device), detail = device.details())
                } else {
                    SettingRow(
                        label = device.label(),
                        value = stringResource(R.string.settings_revoke),
                        detail = device.details(),
                        onClick = { onRevoke(device) },
                        modifier = if (device.id == revoking?.id) Modifier.focusRequester(revokeOpener) else Modifier,
                    )
                }
            }
        }
    }
}

private fun DeviceSession.label(): String = name.ifBlank { model }

/** Model (when it differs from the name), app version and the day it was last seen. */
private fun DeviceSession.details(): String = listOfNotNull(
    model.takeIf { it.isNotBlank() && it != name },
    version.takeIf { it.isNotBlank() }?.let { "v$it" },
    lastSeen.takeIf { it.length >= 10 }?.take(10),
).joinToString(" · ")

/** A read-only fact (server address, version, this device). */
@Composable
private fun InfoRow(label: String, value: String, detail: String = "") {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Antresol.typography.bodyEmphasis, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, style = Antresol.typography.caption, maxLines = 1)
        }
        Text(
            value,
            style = Antresol.typography.body,
            color = AntresolColors.LabelSecondary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.4f),
        )
    }
}

@Composable
private fun subtitleLabel(code: String): String =
    if (code.isEmpty()) stringResource(TvR.string.settings_off) else TrackLabels.language(code) ?: code

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(TvR.string.language_system)
    AppLanguage.English -> stringResource(TvR.string.language_english)
    AppLanguage.Russian -> stringResource(TvR.string.language_russian)
}

/** Starts the activity afresh (new ViewModels, so server text is fetched again in the new language). */
private fun restartInNewLanguage(activity: Activity) {
    activity.startActivity(
        Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
}
