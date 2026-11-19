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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.api.ApiException
import app.antresol.core.model.Quality
import app.antresol.ui.history.DeviceHistoryModel
import app.antresol.tv.ui.components.TextFieldPanel
import app.antresol.core.session.AuthState
import app.antresol.tv.BuildConfig
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.autostart.AutostartController
import app.antresol.ui.channels.ChannelPrefsModel
import app.antresol.tv.autostart.AutostartPolicy
import app.antresol.tv.autostart.AutostartTarget
import app.antresol.tv.autostart.OverlayPermission
import app.antresol.tv.R as TvR
import app.antresol.ui.language.UiLanguageModel
import app.antresol.ui.language.UiLanguages
import app.antresol.tv.settings.TvSettings
import app.antresol.ui.playback.MenuOption
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.listRowColors
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.playback.qualityLabel
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.format.TrackLabels
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

private enum class SettingsMenu {
    Quality, Subtitles, Language, AutostartTarget, OverlayGuide, OverlayManual, ConfirmSignOut, History, HistoryNew, HistoryOff;

    /** The control that gets focus back when this panel closes: a history panel opened from the picker returns to its row. */
    val opener: SettingsMenu get() = if (this == HistoryNew || this == HistoryOff) History else this
}

/**
 * Settings, opened from the avatar: the profile (switch, sign out), playback defaults (video
 * quality, subtitle language), the autostart («Открывать Antresol при включении»: off by default; it
 * guides the viewer to the «Display over other apps» permission it needs), the app language and
 * about the app, and «Отдельная история на этом устройстве» ([deviceHistory]; null for a kid, whose
 * switch is an administrator's): a shared TV writes its own history, picked from the person's or
 * made by name; switching back asks whether to move what was watched here into the profile, as the
 * web does. A switch starts the screens again on Home (see `MainShell`). Server facts and the other
 * signed-in devices are managed on the web, not on a TV (as on Apple TV). Choices open a side panel (the
 * shell hides its nav meanwhile, see [onMenuOpenChanged]); a new language restarts the app's
 * screens from Home, so text from the server reloads in that language too. Sign Out asks first,
 * with Cancel focused.
 */
@Composable
fun SettingsScreen(
    state: AuthState.SignedIn,
    settings: TvSettings,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    onMenuOpenChanged: (Boolean) -> Unit,
    language: UiLanguageModel,
    deviceHistory: DeviceHistoryModel? = null,
    channelPrefs: ChannelPrefsModel? = null,
) {
    LaunchedEffect(channelPrefs) { channelPrefs?.refresh() }
    val preferences by settings.preferences.collectAsStateWithLifecycle()
    LaunchedEffect(language) { language.refresh() }
    var menu by remember { mutableStateOf<SettingsMenu?>(null) }
    LaunchedEffect(menu != null) { onMenuOpenChanged(menu != null) }
    // «Display over other apps» is granted on a system page: looked at again when it closes.
    val context = LocalContext.current
    var overlayGranted by remember { mutableStateOf(OverlayPermission.granted(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        overlayGranted = OverlayPermission.granted(context)
        AutostartController.sync(context)
    }
    val setAutostart: (Boolean) -> Unit = { on ->
        settings.update { it.copy(autostart = on) }
        AutostartController.sync(context)
        if (on && !overlayGranted) menu = SettingsMenu.OverlayGuide
    }
    // The control that opened each panel gets focus back when the panel closes.
    val openers = remember { SettingsMenu.entries.associateWith { FocusRequester() } }
    var lastMenu by remember { mutableStateOf<SettingsMenu?>(null) }
    val first = remember { FocusRequester() }
    LaunchedEffect(menu) {
        val current = menu
        if (current != null) {
            lastMenu = current
        } else {
            lastMenu?.let { if (!openers.getValue(it.opener).tryRequestFocus()) first.tryRequestFocus() }
        }
    }
    BackHandler(enabled = menu != null) { menu = null }
    LaunchedEffect(Unit) {
        onScrolledToTop(true)
        first.tryRequestFocus()
    }
    LaunchedEffect(deviceHistory) { deviceHistory?.load() }
    val history = state.user?.deviceHistory

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
                item { Section(stringResource(R.string.settings_playback)) }
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
                // The profile's, on the server (absent on an older server that has no such choice).
                channelPrefs?.favoritesFirst?.let { on ->
                    item {
                        SettingRow(
                            label = stringResource(R.string.settings_favorites_first),
                            detail = stringResource(R.string.settings_favorites_first_detail),
                            value = onOffLabel(on),
                            chevron = false,
                            onClick = { channelPrefs.setFavoritesFirst(!on) },
                        )
                    }
                }
                item { Section(stringResource(TvR.string.settings_autostart_section)) }
                item {
                    SettingRow(
                        label = stringResource(TvR.string.settings_autostart),
                        detail = stringResource(TvR.string.settings_autostart_hint),
                        value = onOffLabel(preferences.autostart),
                        chevron = false,
                        onClick = { setAutostart(!preferences.autostart) },
                    )
                }
                if (preferences.autostart) {
                    if (AutostartPolicy.needsOverlayPermission()) {
                        item {
                            SettingRow(
                                label = stringResource(TvR.string.settings_autostart_permission),
                                detail = stringResource(TvR.string.settings_autostart_permission_hint).takeIf { !overlayGranted }.orEmpty(),
                                value = stringResource(if (overlayGranted) TvR.string.settings_permission_allowed else TvR.string.settings_permission_not_allowed),
                                onClick = { if (!overlayGranted) menu = SettingsMenu.OverlayGuide },
                                modifier = Modifier.focusRequester(openers.getValue(SettingsMenu.OverlayGuide)),
                            )
                        }
                    }
                    item {
                        SettingRow(
                            label = stringResource(TvR.string.settings_autostart_wake),
                            detail = stringResource(TvR.string.settings_autostart_wake_hint),
                            value = onOffLabel(preferences.autostartOnWake),
                            chevron = false,
                            onClick = {
                                settings.update { it.copy(autostartOnWake = !it.autostartOnWake) }
                                AutostartController.sync(context)
                            },
                        )
                    }
                    item {
                        SettingRow(
                            label = stringResource(TvR.string.settings_autostart_target),
                            value = autostartTargetLabel(AutostartTarget.fromKey(preferences.autostartTarget)),
                            onClick = { menu = SettingsMenu.AutostartTarget },
                            modifier = Modifier.focusRequester(openers.getValue(SettingsMenu.AutostartTarget)),
                        )
                    }
                }
                if (deviceHistory != null) {
                    item { Section(stringResource(R.string.history_managed_title)) }
                    item {
                        val failed = deviceHistory.error
                        SettingRow(
                            label = stringResource(R.string.history_separate),
                            detail = when {
                                failed != null -> failed.historyMessage()
                                history != null -> stringResource(R.string.history_separate_on, history.name)
                                else -> ""
                            },
                            value = history?.name ?: stringResource(TvR.string.settings_off),
                            onClick = {
                                deviceHistory.clearError()
                                deviceHistory.load()
                                menu = SettingsMenu.History
                            },
                            modifier = Modifier.focusRequester(openers.getValue(SettingsMenu.History)),
                        )
                    }
                }
                item { Section(stringResource(R.string.settings_language)) }
                item {
                    SettingRow(
                        label = stringResource(R.string.ui_language),
                        value = languageLabel(language.choice ?: UiLanguages.PROFILE),
                        onClick = { menu = SettingsMenu.Language },
                        modifier = Modifier.focusRequester(openers.getValue(SettingsMenu.Language)),
                    )
                }
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
                title = stringResource(R.string.ui_language),
                // The profile's interface language, on the server: it follows the profile to every device.
                options = UiLanguages.CHOICES.map { choice ->
                    MenuOption(languageLabel(choice), choice == (language.choice ?: UiLanguages.PROFILE)) { language.choose(choice) }
                },
                onDismiss = { menu = null },
            )
            SettingsMenu.AutostartTarget -> OptionsPanel(
                title = stringResource(TvR.string.settings_autostart_target),
                options = AutostartTarget.entries.map { target ->
                    MenuOption(autostartTargetLabel(target), target.key == preferences.autostartTarget) {
                        settings.update { it.copy(autostartTarget = target.key) }
                    }
                },
                onDismiss = { menu = null },
            )
            SettingsMenu.OverlayGuide -> OptionsPanel(
                title = stringResource(TvR.string.settings_overlay_title),
                note = stringResource(TvR.string.settings_overlay_note),
                noteLines = 8,
                width = 520.dp,
                options = listOf(
                    MenuOption(stringResource(TvR.string.settings_overlay_open), selected = false) {
                        // The page is on top of this screen next; the manual way replaces the panel when the TV has no page.
                        if (!OverlayPermission.openSettings(context)) menu = SettingsMenu.OverlayManual
                    },
                    MenuOption(stringResource(TvR.string.settings_overlay_later), selected = false) {},
                ),
                onDismiss = { if (menu == SettingsMenu.OverlayGuide) menu = null },
                focusIndex = 0,
            )
            SettingsMenu.OverlayManual -> OptionsPanel(
                title = stringResource(TvR.string.settings_overlay_title),
                note = stringResource(TvR.string.settings_overlay_manual, OverlayPermission.adbCommand(context)),
                noteLines = 8,
                width = 520.dp,
                options = listOf(MenuOption(stringResource(TvR.string.settings_overlay_close), selected = false) {}),
                onDismiss = { menu = null },
                focusIndex = 0,
            )
            SettingsMenu.History -> if (deviceHistory != null) {
                val accounts = deviceHistory.accounts.orEmpty()
                val section = stringResource(R.string.history_managed_title)
                OptionsPanel(
                    title = stringResource(R.string.history_separate),
                    note = stringResource(R.string.history_managed_footer),
                    noteLines = 5,
                    width = 460.dp,
                    options = buildList {
                        // Off: back to the profile's own history, after the merge question.
                        add(MenuOption(stringResource(TvR.string.settings_off), selected = history == null) {
                            if (history != null) menu = SettingsMenu.HistoryOff
                        })
                        accounts.forEachIndexed { index, account ->
                            add(
                                MenuOption(account.name, selected = account.id == history?.id, section = section.takeIf { index == 0 }) {
                                    if (account.id != history?.id) deviceHistory.use(account.id)
                                },
                            )
                        }
                        add(
                            MenuOption(
                                stringResource(R.string.history_managed_new),
                                selected = false,
                                section = section.takeIf { accounts.isEmpty() },
                            ) { menu = SettingsMenu.HistoryNew },
                        )
                    },
                    onDismiss = { if (menu == SettingsMenu.History) menu = null },
                )
            }
            SettingsMenu.HistoryNew -> if (deviceHistory != null) {
                TextFieldPanel(
                    title = stringResource(R.string.history_managed_name),
                    placeholder = stringResource(R.string.history_managed_name_placeholder),
                    submitLabel = stringResource(R.string.history_managed_create),
                    onSubmit = { name ->
                        deviceHistory.create(name)
                        menu = null
                    },
                    onDismiss = { menu = null },
                )
            }
            SettingsMenu.HistoryOff -> if (deviceHistory != null && history != null) {
                OptionsPanel(
                    title = stringResource(R.string.history_off_title, history.name),
                    note = stringResource(R.string.history_off_text),
                    noteLines = 5,
                    width = 460.dp,
                    options = listOf(
                        MenuOption(stringResource(R.string.action_cancel), selected = false) {},
                        MenuOption(stringResource(R.string.history_off_keep), selected = false) { deviceHistory.turnOff(merge = false) },
                        MenuOption(stringResource(R.string.history_off_merge), selected = false) { deviceHistory.turnOff(merge = true) },
                    ),
                    onDismiss = { menu = null },
                    focusIndex = 0,
                )
            }
            SettingsMenu.ConfirmSignOut -> ConfirmPanel(
                title = stringResource(TvR.string.settings_sign_out_confirm, state.session.userName),
                confirmLabel = stringResource(R.string.profile_sign_out),
                onConfirm = onSignOut,
                onDismiss = { menu = null },
            )
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
        ProfileAvatar(name = session.userName, avatar = session.avatar, size = 96.dp, photoUrl = LocalMediaUrls.current?.image(session.photo))
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
private fun ConfirmPanel(title: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    OptionsPanel(
        title = title,
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
private fun SettingRow(label: String, value: String, onClick: () -> Unit, modifier: Modifier = Modifier, detail: String = "", chevron: Boolean = true) {
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
            if (chevron) {
                Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.7f), modifier = Modifier.width(16.dp))
            } else {
                // The values of all rows stay in one column.
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

/** The server's sentence for a refused switch (a kid, too many histories), else the web's «Не удалось переключить историю». */
@Composable
private fun Throwable.historyMessage(): String {
    val server = (this as? ApiException)?.message?.takeUnless { it.isBlank() || it.startsWith("HTTP ") }
    return server ?: stringResource(R.string.history_separate_failed)
}

@Composable
private fun subtitleLabel(code: String): String =
    if (code.isEmpty()) stringResource(TvR.string.settings_off) else TrackLabels.language(code) ?: code

@Composable
private fun onOffLabel(on: Boolean): String = stringResource(if (on) TvR.string.settings_on else TvR.string.settings_off)

@Composable
private fun autostartTargetLabel(target: AutostartTarget): String = stringResource(
    when (target) {
        AutostartTarget.Last -> TvR.string.settings_autostart_target_last
        AutostartTarget.Tv -> TvR.string.settings_autostart_target_tv
        AutostartTarget.Channel -> TvR.string.settings_autostart_target_channel
    },
)

@Composable
private fun languageLabel(language: String): String = when (language) {
    UiLanguages.ENGLISH -> stringResource(TvR.string.language_english)
    UiLanguages.RUSSIAN -> stringResource(TvR.string.language_russian)
    else -> stringResource(R.string.ui_language_profile)
}

/** Starts the activity afresh (new ViewModels, so server text is fetched again in the new language). */
fun restartInNewLanguage(activity: Activity) {
    activity.startActivity(
        Intent(activity, activity.javaClass).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
    )
}
