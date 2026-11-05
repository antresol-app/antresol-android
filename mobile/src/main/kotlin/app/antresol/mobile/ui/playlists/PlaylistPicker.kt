package app.antresol.mobile.ui.playlists

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.core.api.AntresolClient
import app.antresol.core.model.Playlist
import app.antresol.core.music.PlaylistEdits
import app.antresol.mobile.ui.LoadState
import app.antresol.mobile.ui.components.GlassTextField
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.mobile.ui.load
import app.antresol.mobile.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** A message for the snackbar: a string resource and its argument (a playlist name). */
data class PlaylistNotice(val res: Int, val arg: String? = null)

/**
 * "Add to Playlist" for a page that lists songs: [open] with the songs, then the sheet adds them to a
 * playlist the user picks or to a new one. Owned by the page's ViewModel ([scope] is its scope).
 */
class PlaylistPicker(private val client: AntresolClient, private val scope: CoroutineScope) {
    /** The songs being added; non-null while the sheet is open. */
    var trackIds: List<Long>? by mutableStateOf(null)
        private set

    var playlists: LoadState<List<Playlist>> by mutableStateOf(LoadState.Loading)
        private set

    /** A write is in flight (the sheet ignores further taps). */
    var busy: Boolean by mutableStateOf(false)
        private set

    var notice: PlaylistNotice? by mutableStateOf(null)
        private set

    fun open(tracks: List<Long>) {
        if (tracks.isEmpty()) return
        trackIds = tracks
        playlists = LoadState.Loading
        scope.launch { playlists = load { client.playlists().items } }
    }

    fun dismiss() {
        trackIds = null
    }

    fun consumeNotice() {
        notice = null
    }

    fun addTo(playlist: Playlist) = write(playlist.name) { tracks ->
        client.updatePlaylist(playlist.id, PlaylistEdits.append(tracks))
    }

    /** Creates a playlist named [name] holding the songs. */
    fun createWith(name: String) = write(name.trim()) { tracks ->
        client.createPlaylist(name.trim(), tracks)
    }

    private fun write(name: String, block: suspend (List<Long>) -> Unit) {
        val tracks = trackIds ?: return
        if (busy) return
        busy = true
        scope.launch {
            notice = try {
                block(tracks)
                trackIds = null
                PlaylistNotice(R.string.playlist_added, name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                PlaylistNotice(R.string.playlist_failed)
            } finally {
                busy = false
            }
        }
    }
}

/** The picker's sheet (while open) and its snackbar messages in [snackbar]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPickerHost(picker: PlaylistPicker, snackbar: SnackbarHostState) {
    PlaylistNoticeSnackbar(picker.notice, snackbar, picker::consumeNotice)
    if (picker.trackIds == null) return
    var naming by rememberSaveable { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = picker::dismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.navigationBars)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 16.dp),
        ) {
            Text(
                stringResource(R.string.playlist_add),
                style = Antresol.typography.sectionTitle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            SheetRow(MobileIcons.Plus, stringResource(R.string.playlist_new), tag = "playlist-new") { naming = true }
            when (val state = picker.playlists) {
                LoadState.Loading -> Text(stringResource(R.string.state_loading), style = Antresol.typography.caption, modifier = Modifier.padding(20.dp))
                is LoadState.Failed -> Text(
                    state.error.userMessage(LocalContext.current.resources),
                    style = Antresol.typography.caption,
                    modifier = Modifier.padding(20.dp),
                )
                is LoadState.Ready -> state.value.forEach { playlist ->
                    SheetRow(
                        icon = MobileIcons.Music,
                        label = playlist.name,
                        detail = pluralStringResource(R.plurals.song_count, playlist.trackCount, playlist.trackCount),
                        tag = "playlist-${playlist.id}",
                    ) { picker.addTo(playlist) }
                }
            }
        }
    }
    if (naming) {
        PlaylistNameSheet(
            title = stringResource(R.string.playlist_new),
            confirm = stringResource(R.string.playlist_create),
            onConfirm = { name ->
                naming = false
                picker.createWith(name)
            },
            onDismiss = { naming = false },
        )
    }
}

@Composable
private fun SheetRow(icon: ImageVector, label: String, detail: String? = null, tag: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, onClick = onClick)
            .testTag(tag)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(icon, contentDescription = null, tint = AntresolColors.Label, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = Antresol.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail != null) Text(detail, style = Antresol.typography.caption)
        }
    }
}

/** Asks for a playlist name ([initial] when renaming) in a sheet; Confirm is enabled once it is not blank. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistNameSheet(title: String, confirm: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit, initial: String = "") {
    var name by rememberSaveable { mutableStateOf(initial) }
    val valid = name.isNotBlank()
    ActionSheet(title = title, onDismiss = onDismiss) {
        GlassTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(R.string.playlist_name),
            modifier = Modifier.padding(horizontal = 20.dp).testTag("playlist-name"),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (valid) onConfirm(name) }),
        )
        SheetButtons(confirm = confirm, confirmEnabled = valid, confirmTag = "playlist-name-confirm", onConfirm = { onConfirm(name) }, onDismiss = onDismiss)
    }
}

/** A destructive confirmation (delete a playlist) as a sheet: [message], Cancel and [confirm] in red. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfirmSheet(title: String, message: String, confirm: String, confirmTag: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    ActionSheet(title = title, onDismiss = onDismiss) {
        Text(message, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), modifier = Modifier.padding(horizontal = 20.dp))
        SheetButtons(confirm = confirm, confirmColor = AntresolColors.Red, confirmTag = confirmTag, onConfirm = onConfirm, onDismiss = onDismiss)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(
            Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).imePadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(title, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(horizontal = 20.dp))
            content()
        }
    }
}

@Composable
private fun SheetButtons(
    confirm: String,
    confirmTag: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmEnabled: Boolean = true,
    confirmColor: androidx.compose.ui.graphics.Color = AntresolColors.Accent,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.End) {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = AntresolColors.Label) }
        TextButton(onClick = onConfirm, enabled = confirmEnabled, modifier = Modifier.testTag(confirmTag)) {
            Text(confirm, color = if (confirmEnabled) confirmColor else AntresolColors.LabelTertiary)
        }
    }
}

/** Shows [notice] once as a snackbar. */
@Composable
fun PlaylistNoticeSnackbar(notice: PlaylistNotice?, host: SnackbarHostState, onShown: () -> Unit) {
    val resources = LocalContext.current.resources
    LaunchedEffect(notice) {
        if (notice != null) {
            onShown()
            host.showSnackbar(if (notice.arg != null) resources.getString(notice.res, notice.arg) else resources.getString(notice.res))
        }
    }
}
