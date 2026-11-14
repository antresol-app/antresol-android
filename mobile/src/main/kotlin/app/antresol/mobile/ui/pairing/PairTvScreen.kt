package app.antresol.mobile.ui.pairing

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import android.content.res.Resources
import app.antresol.core.api.ApiException
import app.antresol.core.model.Profile
import app.antresol.core.tvsetup.TvSetupError
import app.antresol.core.tvsetup.TvSetupFailure
import app.antresol.core.tvsetup.TvSetupOtherServerException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.IOException
import app.antresol.mobile.R
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.components.BackBar
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.GlassTextField
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.mobile.ui.userMessage
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * «Sign in a TV» (Settings): scan the TV's QR code (or type its code), see which device asks,
 * pick the profile (admins), Approve or Decline. [cameraAllowed] overrides the permission check
 * (tests and devices without a camera pass false/true).
 */
@Composable
fun PairTvScreen(
    viewModel: PairTvViewModel,
    ownName: String,
    ownAvatar: String,
    onBack: () -> Unit,
    cameraAllowed: Boolean? = null,
    scanner: @Composable (onText: (String) -> Unit, modifier: Modifier) -> Unit = { onText, modifier -> QrScanner(onText, modifier) },
) {
    val bottom = LocalBottomBarPadding.current
    Box(Modifier.fillMaxSize().background(AntresolColors.Background).testTag("pair-tv")) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(bottom = bottom.calculateBottomPadding() + 24.dp),
        ) {
            BackBar(onBack)
            Text(
                stringResource(R.string.pair_title),
                style = Antresol.typography.pageTitle,
                modifier = Modifier.padding(horizontal = PageGutter, vertical = 8.dp),
            )
            Column(
                Modifier
                    .padding(horizontal = PageGutter)
                    .widthIn(max = 520.dp)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (val step = viewModel.step) {
                    is PairStep.Scan -> ScanStep(step, viewModel, cameraAllowed, scanner)
                    PairStep.Checking -> Text(
                        stringResource(R.string.pair_checking),
                        style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
                        modifier = Modifier.padding(vertical = 24.dp),
                    )
                    is PairStep.Confirm -> ConfirmStep(step, viewModel, ownName, ownAvatar)
                    is PairStep.SetupConfirm -> SetupConfirmStep(step, viewModel, ownName, ownAvatar)
                    is PairStep.Done -> DoneStep(step, onAgain = viewModel::again, onBack = onBack)
                }
            }
        }
    }
}

@Composable
private fun ScanStep(
    step: PairStep.Scan,
    viewModel: PairTvViewModel,
    cameraAllowed: Boolean?,
    scanner: @Composable (onText: (String) -> Unit, modifier: Modifier) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(cameraAllowed ?: (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED))
    }
    var asked by rememberSaveable { mutableStateOf(false) }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        granted = ok
        asked = true
        val activity = context.findActivity()
        permanentlyDenied = !ok && activity != null && !activity.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)
    }
    // Back from the system settings with the permission switched on.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (cameraAllowed == null && !granted) {
            granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(AntresolShapes.card)
            .background(AntresolColors.BackgroundElevated)
            .testTag("pair-camera"),
        contentAlignment = Alignment.Center,
    ) {
        if (granted) {
            scanner(viewModel::onScanned, Modifier.fillMaxSize())
        } else {
            Column(
                Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(AntresolIcons.QrCode, contentDescription = null, tint = AntresolColors.LabelSecondary, modifier = Modifier.size(48.dp))
                Text(
                    stringResource(if (permanentlyDenied) R.string.pair_camera_denied else R.string.pair_camera_rationale),
                    style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
                    textAlign = TextAlign.Center,
                )
                if (permanentlyDenied) {
                    GlassPillButton(
                        text = stringResource(R.string.pair_camera_settings),
                        onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        },
                    )
                } else {
                    PrimaryPillButton(
                        text = stringResource(R.string.pair_camera_allow),
                        onClick = { launcher.launch(Manifest.permission.CAMERA) },
                        modifier = Modifier.testTag("pair-allow-camera"),
                    )
                }
            }
        }
    }
    if (granted) {
        Text(
            stringResource(R.string.pair_scan_hint),
            style = Antresol.typography.caption,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    step.notice?.let { notice ->
        val resources = context.resources
        Text(
            when (notice) {
                PairNotice.NotATvCode -> stringResource(R.string.pair_not_a_tv_code)
                PairNotice.InvalidCode -> stringResource(R.string.pair_code_invalid)
                PairNotice.OtherServer -> stringResource(R.string.pair_other_server)
                is PairNotice.Failed -> notice.error.userMessage(resources)
            },
            style = Antresol.typography.body.copy(color = AntresolColors.Orange),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("pair-notice"),
        )
    }

    var code by rememberSaveable { mutableStateOf("") }
    Text(
        stringResource(R.string.pair_code_title).uppercase(),
        style = Antresol.typography.eyebrow,
        modifier = Modifier.padding(start = 4.dp, top = 8.dp),
    )
    GlassTextField(
        value = code,
        onValueChange = { code = it.take(12) },
        placeholder = stringResource(R.string.pair_code_hint),
        leadingIcon = AntresolIcons.Tv,
        keyboardOptions = KeyboardOptions(
            autoCorrectEnabled = false,
            capitalization = KeyboardCapitalization.Characters,
            keyboardType = KeyboardType.Ascii,
            imeAction = ImeAction.Go,
        ),
        keyboardActions = KeyboardActions(onGo = { viewModel.onCodeEntered(code) }),
        modifier = Modifier.testTag("pair-code-field"),
    )
    PrimaryPillButton(
        text = stringResource(R.string.pair_continue),
        onClick = { viewModel.onCodeEntered(code) },
        enabled = code.isNotBlank(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ConfirmStep(step: PairStep.Confirm, viewModel: PairTvViewModel, ownName: String, ownAvatar: String) {
    val device = step.lookup.device
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AntresolShapes.card)
            .background(AntresolColors.BackgroundElevated)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AntresolIcons.Tv, contentDescription = null, tint = AntresolColors.Accent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            device.name.ifBlank { device.model.ifBlank { step.lookup.code } },
            style = Antresol.typography.sectionTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("pair-device-name"),
        )
        val detail = listOfNotNull(
            device.model.takeIf { it.isNotBlank() && it != device.name },
            device.version.takeIf { it.isNotBlank() }?.let { "v$it" },
        ).joinToString(" · ")
        if (detail.isNotBlank()) Text(detail, style = Antresol.typography.caption)
        Text(stringResource(R.string.pair_asks_to_sign_in), style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary))
    }
    if (step.lookup.remote) {
        Text(
            stringResource(R.string.pair_remote_warning),
            style = Antresol.typography.body.copy(color = AntresolColors.Orange),
            modifier = Modifier.testTag("pair-remote-warning"),
        )
    }

    Text(stringResource(R.string.pair_profile).uppercase(), style = Antresol.typography.eyebrow, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AntresolShapes.card)
            .background(AntresolColors.BackgroundElevated),
    ) {
        val choices = step.profiles.ifEmpty { listOf(Profile(step.selectedUserId, ownName, ownAvatar)) }
        choices.forEachIndexed { index, profile ->
            if (index > 0) HorizontalDivider(color = AntresolColors.Hairline, modifier = Modifier.padding(start = 64.dp))
            ProfileChoice(
                profile = profile,
                selected = profile.id == step.selectedUserId,
                selectable = step.profiles.isNotEmpty() && !step.sending,
                onSelect = { viewModel.select(profile.id) },
            )
        }
    }
    Text(stringResource(R.string.pair_profile_hint), style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = 4.dp))

    step.error?.let {
        Text(
            it.userMessage(LocalContext.current.resources),
            style = Antresol.typography.body.copy(color = AntresolColors.Red),
            modifier = Modifier.testTag("pair-error"),
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassPillButton(
            text = stringResource(R.string.pair_deny),
            onClick = viewModel::deny,
            enabled = !step.sending,
            modifier = Modifier.weight(1f).testTag("pair-deny"),
        )
        PrimaryPillButton(
            text = stringResource(R.string.pair_approve),
            onClick = viewModel::approve,
            enabled = !step.sending,
            modifier = Modifier.weight(1f).testTag("pair-approve"),
        )
    }
}

@Composable
private fun ProfileChoice(profile: Profile, selected: Boolean, selectable: Boolean, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selectable) Modifier.focusClickable(AntresolShapes.control, role = Role.RadioButton, onClick = onSelect) else Modifier)
            .testTag("pair-profile-${profile.id}")
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ProfileAvatar(name = profile.name, avatar = profile.avatar, size = 36.dp)
        Text(profile.name, style = Antresol.typography.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (selected) Icon(AntresolIcons.Check, contentDescription = null, tint = AntresolColors.Accent, modifier = Modifier.size(22.dp))
    }
}

/**
 * «Set Up a TV»: the TV that showed its setup QR gets this phone's server and signs in to the
 * chosen profile (admins may pick another one). Cancel goes back to the scanner.
 */
@Composable
private fun SetupConfirmStep(step: PairStep.SetupConfirm, viewModel: PairTvViewModel, ownName: String, ownAvatar: String) {
    val resources = LocalContext.current.resources
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AntresolShapes.card)
            .background(AntresolColors.BackgroundElevated)
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(AntresolIcons.Tv, contentDescription = null, tint = AntresolColors.Accent, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(4.dp))
        Text(
            stringResource(R.string.pair_setup_title),
            style = Antresol.typography.sectionTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("pair-setup-title"),
        )
        Text(
            stringResource(R.string.pair_setup_text),
            style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
            textAlign = TextAlign.Center,
        )
    }

    Text(stringResource(R.string.pair_setup_server).uppercase(), style = Antresol.typography.eyebrow, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AntresolShapes.card)
            .background(AntresolColors.BackgroundElevated)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(AntresolIcons.Server, contentDescription = null, tint = AntresolColors.LabelSecondary, modifier = Modifier.size(24.dp))
        Text(
            serverLabel(step.serverUrl),
            style = Antresol.typography.body,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).testTag("pair-setup-server"),
        )
    }

    Text(stringResource(R.string.pair_profile).uppercase(), style = Antresol.typography.eyebrow, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(AntresolShapes.card)
            .background(AntresolColors.BackgroundElevated),
    ) {
        val choices = step.profiles.ifEmpty { listOf(Profile(step.selectedUserId, ownName, ownAvatar)) }
        choices.forEachIndexed { index, profile ->
            if (index > 0) HorizontalDivider(color = AntresolColors.Hairline, modifier = Modifier.padding(start = 64.dp))
            ProfileChoice(
                profile = profile,
                selected = profile.id == step.selectedUserId,
                selectable = step.profiles.isNotEmpty() && !step.sending,
                onSelect = { viewModel.select(profile.id) },
            )
        }
    }
    Text(stringResource(R.string.pair_profile_hint), style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = 4.dp))

    if (step.sending) {
        Text(
            stringResource(R.string.pair_setup_sending),
            style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
            modifier = Modifier.testTag("pair-setup-sending"),
        )
    }
    step.error?.let { error ->
        Text(
            setupErrorMessage(error, step.code != null, resources),
            style = Antresol.typography.body.copy(color = AntresolColors.Red),
            modifier = Modifier.testTag("pair-error"),
        )
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassPillButton(
            text = stringResource(R.string.pair_setup_cancel),
            onClick = viewModel::again,
            enabled = !step.sending,
            modifier = Modifier.weight(1f).testTag("pair-setup-cancel"),
        )
        PrimaryPillButton(
            text = stringResource(if (step.error != null) R.string.pair_setup_retry else R.string.pair_setup_button),
            onClick = viewModel::setUp,
            enabled = !step.sending,
            modifier = Modifier.weight(1f).testTag("pair-setup"),
        )
    }
}

/** `host:port` (or the host of an https address) of the phone's server, as the card shows it. */
private fun serverLabel(url: String): String {
    val parsed = url.toHttpUrlOrNull() ?: return url
    val default = (parsed.scheme == "http" && parsed.port == 80) || (parsed.scheme == "https" && parsed.port == 443)
    return if (default) parsed.host else "${parsed.host}:${parsed.port}"
}

/** What went wrong setting up the TV. [tvJoined]: the TV already took the server; only the sign-in failed. */
private fun setupErrorMessage(error: Throwable, tvJoined: Boolean, resources: Resources): String {
    val message = when (error) {
        is TvSetupFailure -> when (error.error) {
            TvSetupError.ExpiredNonce -> resources.getString(R.string.pair_setup_expired)
            TvSetupError.Unreachable -> resources.getString(R.string.pair_setup_server_unreachable)
            TvSetupError.NotAntresol -> resources.getString(R.string.pair_setup_not_antresol)
            TvSetupError.ServerMismatch -> resources.getString(R.string.pair_setup_mismatch)
            TvSetupError.RateLimited -> resources.getString(R.string.pair_setup_rate_limited)
            TvSetupError.BadRequest, null -> resources.getString(R.string.pair_setup_failed)
        }
        is TvSetupOtherServerException -> resources.getString(R.string.pair_setup_mismatch)
        // The TV itself did not answer (another network, or it left the setup screen).
        is IOException -> if (error is ApiException) error.userMessage(resources) else resources.getString(R.string.pair_setup_tv_unreachable)
        else -> error.userMessage(resources)
    }
    return if (tvJoined) resources.getString(R.string.pair_setup_sign_in_failed, message) else message
}

@Composable
private fun DoneStep(step: PairStep.Done, onAgain: () -> Unit, onBack: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            if (step.approved) AntresolIcons.CheckCircle else AntresolIcons.Tv,
            contentDescription = null,
            tint = if (step.approved) AntresolColors.Green else AntresolColors.LabelSecondary,
            modifier = Modifier.size(56.dp),
        )
        Text(
            stringResource(
                when {
                    step.setup -> R.string.pair_setup_done
                    step.approved -> R.string.pair_done
                    else -> R.string.pair_declined
                },
            ),
            style = Antresol.typography.sectionTitle,
            textAlign = TextAlign.Center,
            modifier = Modifier.testTag("pair-done"),
        )
        PrimaryPillButton(text = stringResource(R.string.pair_finish), onClick = onBack, modifier = Modifier.fillMaxWidth())
        GlassPillButton(text = stringResource(R.string.pair_scan_again), onClick = onAgain, modifier = Modifier.fillMaxWidth())
    }
}

private fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
