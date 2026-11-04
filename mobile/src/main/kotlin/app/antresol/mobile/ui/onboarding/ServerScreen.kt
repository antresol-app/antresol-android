package app.antresol.mobile.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.antresol.core.api.ApiException
import app.antresol.core.session.NotAntresolServerException
import app.antresol.core.session.ServerNeedsSetupException
import app.antresol.core.session.SessionManager
import app.antresol.mobile.ui.components.GlassTextField
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.LogoMark
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/** First launch (or "Change Server"): the address of the Antresol server, validated with `/api/health`. */
@Composable
fun ServerScreen(session: SessionManager) {
    val resources = LocalContext.current.resources
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        if (address.isEmpty()) session.lastServerUrl()?.let { address = it }
    }

    fun connect() {
        if (busy || address.isBlank()) return
        focus.clearFocus()
        busy = true
        error = null
        scope.launch {
            try {
                session.connect(address.trim())
            } catch (e: CancellationException) {
                throw e
            } catch (e: NotAntresolServerException) {
                error = resources.getString(R.string.server_not_antresol)
            } catch (e: ServerNeedsSetupException) {
                error = resources.getString(R.string.server_needs_setup)
            } catch (e: ApiException) {
                error = e.userMessage(resources)
            } catch (e: IOException) {
                error = resources.getString(R.string.server_unreachable)
            } catch (e: Exception) {
                error = e.userMessage(resources)
            } finally {
                busy = false
            }
        }
    }

    OnboardingScaffold {
        LogoMark(size = 84.dp)
        Spacer(Modifier.height(4.dp))
        Text(stringResource(R.string.server_title), style = Antresol.typography.pageTitle, textAlign = TextAlign.Center)
        Text(
            stringResource(R.string.server_subtitle),
            style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(8.dp))
        GlassTextField(
            value = address,
            onValueChange = {
                address = it
                error = null
            },
            placeholder = stringResource(R.string.server_address_hint),
            leadingIcon = AntresolIcons.Server,
            enabled = !busy,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { connect() }),
            modifier = Modifier.testTag("server-address"),
        )
        error?.let {
            Text(it, style = Antresol.typography.caption.copy(color = AntresolColors.Red), textAlign = TextAlign.Center)
        }
        PrimaryPillButton(
            text = stringResource(if (busy) R.string.server_connecting else R.string.server_connect),
            onClick = ::connect,
            enabled = !busy && address.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
