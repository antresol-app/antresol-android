package app.antresol.mobile.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.DeviceSession
import app.antresol.core.model.Health
import app.antresol.core.model.Me
import app.antresol.core.model.Quality
import app.antresol.core.session.StoredSession
import app.antresol.mobile.BuildConfig
import app.antresol.mobile.R
import app.antresol.mobile.player.qualityLabel
import app.antresol.mobile.settings.AppLanguage
import app.antresol.mobile.settings.AppSettings
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.LoadViewModel
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.BackBar
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.SegmentedPills
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import app.antresol.ui.R as UiR

/** What the settings page loads from the server: its version and this profile's signed-in devices. */
data class ServerInfo(val health: Health?, val devices: List<DeviceSession>)

class SettingsViewModel(private val client: AntresolClient) : LoadViewModel<ServerInfo>(fetch = { fetchInfo(client) }) {
    /** Signs another device out of this profile, then refreshes the list. */
    fun revoke(device: DeviceSession) {
        viewModelScope.launch {
            try {
                client.revokeDevice(device.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The refreshed list shows whether it worked.
            }
            refresh(force = true)
        }
    }

    private companion object {
        suspend fun fetchInfo(client: AntresolClient): ServerInfo {
            val health = runCatching { client.health() }.getOrNull()
            val devices = client.devices().items.sortedByDescending { it.current }
            return ServerInfo(health, devices)
        }
    }
}

/**
 * Settings: the profile (switch, sign out), the server (address, version, change), playback
 * preferences (video quality, autoplay), the app language, this profile's devices and the version.
 */
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    settings: AppSettings,
    session: StoredSession,
    user: Me?,
    onBack: () -> Unit,
    onSwitchProfile: () -> Unit,
    onSignOut: () -> Unit,
    onChangeServer: () -> Unit,
    onLanguageChanged: () -> Unit,
) {
    var qualitySheet by remember { mutableStateOf(false) }
    var revoking by remember { mutableStateOf<DeviceSession?>(null) }
    val bottom = LocalBottomBarPadding.current
    Box(Modifier.fillMaxSize().background(AntresolColors.Background)) {
        LazyColumn(
            modifier = Modifier.fillMaxSize().testTag("settings"),
            contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 24.dp),
        ) {
            item("top") {
                BackBar(onBack)
                Text(
                    stringResource(UiR.string.nav_settings),
                    style = Antresol.typography.pageTitle,
                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 8.dp),
                )
            }
            item("profile") {
                Row(
                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    val name = user?.name ?: session.userName
                    ProfileAvatar(name = name, avatar = user?.avatar ?: session.avatar, size = 56.dp)
                    Column {
                        Text(name, style = Antresol.typography.sectionTitle)
                        val role = when {
                            user?.isAdmin == true -> stringResource(UiR.string.account_admin)
                            user?.isKid == true -> stringResource(R.string.account_kid)
                            else -> stringResource(R.string.account_profile)
                        }
                        Text(role, style = Antresol.typography.caption)
                    }
                }
            }
            group {
                ActionRow(AntresolIcons.SwitchProfile, stringResource(UiR.string.profile_switch), onSwitchProfile, "settings-switch")
                Divider()
                ActionRow(AntresolIcons.SignOut, stringResource(UiR.string.profile_sign_out), onSignOut, "settings-sign-out", AntresolColors.Red)
            }

            header(R.string.settings_server)
            group {
                val info = (viewModel.state as? LoadState.Ready)?.value
                val host = session.serverUrl.toHttpUrlOrNull()?.let { "${it.host}:${it.port}" } ?: session.serverUrl
                ValueRow(stringResource(R.string.settings_server_address), host)
                Divider()
                ValueRow(stringResource(R.string.settings_server_version), info?.health?.version?.ifBlank { null } ?: "—", tag = "settings-server-version")
                Divider()
                ActionRow(AntresolIcons.Server, stringResource(UiR.string.server_change), onChangeServer, "settings-change-server")
            }

            header(R.string.settings_playback)
            group {
                ValueRow(stringResource(R.string.settings_video_quality), qualityLabel(settings.videoQuality), onClick = { qualitySheet = true }, tag = "settings-quality")
                Divider()
                SwitchRow(stringResource(R.string.settings_autoplay), stringResource(R.string.settings_autoplay_detail), settings.autoplayNext, settings::updateAutoplayNext, "settings-autoplay")
            }

            header(R.string.settings_language)
            item("language") {
                SegmentedPills(
                    options = AppLanguage.entries,
                    selected = settings.language,
                    label = { languageLabel(it) },
                    onSelect = { language ->
                        if (language != settings.language) {
                            settings.updateLanguage(language)
                            onLanguageChanged()
                        }
                    },
                    modifier = Modifier.padding(horizontal = PageGutter, vertical = 4.dp).testTag("settings-language"),
                )
            }

            header(R.string.settings_devices)
            when (val state = viewModel.state) {
                LoadState.Loading -> item("devices-loading") { Text(stringResource(UiR.string.state_loading), style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = PageGutter)) }
                is LoadState.Failed -> item("devices-failed") { Text(stringResource(UiR.string.error_generic), style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = PageGutter)) }
                is LoadState.Ready -> group {
                    state.value.devices.forEachIndexed { index, device ->
                        if (index > 0) Divider()
                        DeviceRow(device, onRevoke = { revoking = device })
                    }
                }
            }

            header(R.string.settings_about)
            group {
                ValueRow(stringResource(R.string.settings_app_version), BuildConfig.VERSION_NAME)
            }
        }
    }
    if (qualitySheet) {
        QualityDialog(settings.videoQuality, onSelect = { settings.updateVideoQuality(it); qualitySheet = false }, onDismiss = { qualitySheet = false })
    }
    revoking?.let { device ->
        AlertDialog(
            onDismissRequest = { revoking = null },
            containerColor = AntresolColors.BackgroundElevated,
            title = { Text(stringResource(R.string.settings_revoke_title, device.name.ifBlank { device.model })) },
            text = { Text(stringResource(R.string.settings_revoke_body)) },
            confirmButton = {
                TextButton(onClick = { viewModel.revoke(device); revoking = null }) {
                    Text(stringResource(R.string.settings_revoke), color = AntresolColors.Red)
                }
            },
            dismissButton = { TextButton(onClick = { revoking = null }) { Text(stringResource(UiR.string.action_cancel)) } },
        )
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String = when (language) {
    AppLanguage.System -> stringResource(R.string.settings_language_system)
    // Each language is named in itself so it can be found whatever the current one is.
    AppLanguage.English -> "English"
    AppLanguage.Russian -> "Русский"
}

private fun LazyListScope.header(title: Int) {
    item("header-$title") {
        Text(
            stringResource(title).uppercase(),
            style = Antresol.typography.eyebrow,
            modifier = Modifier.padding(start = PageGutter, end = PageGutter, top = 24.dp, bottom = 8.dp),
        )
    }
}

/** A rounded elevated block of rows, like iOS grouped settings. */
private fun LazyListScope.group(content: @Composable () -> Unit) {
    item {
        Column(
            Modifier
                .padding(horizontal = PageGutter)
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .background(AntresolColors.BackgroundElevated, AntresolShapes.card),
        ) { content() }
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = AntresolColors.Hairline, modifier = Modifier.padding(start = 16.dp))

@Composable
private fun ActionRow(icon: ImageVector, label: String, onClick: () -> Unit, tag: String, color: Color = AntresolColors.Label) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Text(label, style = Antresol.typography.body.copy(color = color))
    }
}

@Composable
private fun ValueRow(label: String, value: String, onClick: (() -> Unit)? = null, tag: String? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = Antresol.typography.body, modifier = Modifier.weight(1f))
        Text(value, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (onClick != null) Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = AntresolColors.LabelTertiary, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SwitchRow(label: String, detail: String, checked: Boolean, onChange: (Boolean) -> Unit, tag: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Switch) { onChange(!checked) }
            .testTag(tag)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = Antresol.typography.body)
            Text(detail, style = Antresol.typography.caption)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = AntresolColors.Green, checkedThumbColor = Color.White),
        )
    }
}

@Composable
private fun DeviceRow(device: DeviceSession, onRevoke: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(device.name.ifBlank { device.model }, style = Antresol.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val detail = listOfNotNull(
                device.model.takeIf { it.isNotBlank() && it != device.name },
                device.version.takeIf { it.isNotBlank() }?.let { "v$it" },
                device.lastSeen.takeIf { it.length >= 10 }?.take(10),
            ).joinToString(" · ")
            if (detail.isNotBlank()) Text(detail, style = Antresol.typography.caption, maxLines = 1)
        }
        if (device.current) {
            Text(stringResource(R.string.settings_this_device), style = Antresol.typography.caption.copy(color = AntresolColors.Green))
        } else {
            Box(
                Modifier
                    .size(36.dp)
                    .clickable(role = Role.Button, onClick = onRevoke)
                    .testTag("revoke-${device.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(MobileIcons.Close, contentDescription = stringResource(R.string.settings_revoke), tint = AntresolColors.LabelTertiary, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun QualityDialog(selected: String, onSelect: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AntresolColors.BackgroundElevated,
        title = { Text(stringResource(R.string.settings_video_quality)) },
        text = {
            Column {
                Quality.ALL.forEach { value ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(value) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(22.dp)) {
                            if (value == selected) Icon(AntresolIcons.Check, contentDescription = null, tint = AntresolColors.Accent)
                        }
                        Text(qualityLabel(value), style = Antresol.typography.body)
                    }
                }
                Text(
                    stringResource(R.string.settings_video_quality_detail),
                    style = Antresol.typography.caption,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(UiR.string.action_cancel)) } },
    )
}
