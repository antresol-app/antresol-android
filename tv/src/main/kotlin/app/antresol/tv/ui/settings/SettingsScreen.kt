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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.model.Quality
import app.antresol.core.session.AuthState
import app.antresol.tv.BuildConfig
import app.antresol.tv.R as TvR
import app.antresol.ui.language.UiLanguageModel
import app.antresol.ui.language.UiLanguages
import app.antresol.tv.settings.TvSettings
import app.antresol.tv.ui.components.MenuOption
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.listRowColors
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.tv.ui.player.qualityLabel
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.format.TrackLabels
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

private enum class SettingsMenu { Quality, Subtitles, Language, ConfirmSignOut }

/**
 * Settings, opened from the avatar: the profile (switch, sign out), playback defaults (video
 * quality, subtitle language), the app language and about the app. Server facts and signed-in
 * devices are managed on the web, not on a TV (as on Apple TV). Choices open a side panel (the
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
) {
    val preferences by settings.preferences.collectAsStateWithLifecycle()
    LaunchedEffect(language) { language.refresh() }
    var menu by remember { mutableStateOf<SettingsMenu?>(null) }
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
            lastMenu?.let { if (!openers.getValue(it).tryRequestFocus()) first.tryRequestFocus() }
        }
    }
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

@Composable
private fun subtitleLabel(code: String): String =
    if (code.isEmpty()) stringResource(TvR.string.settings_off) else TrackLabels.language(code) ?: code

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
