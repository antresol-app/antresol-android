package app.antresol.tv.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.core.api.InvalidServerAddressException
import app.antresol.core.session.NotAntresolServerException
import app.antresol.core.session.ServerNeedsSetupException
import app.antresol.core.session.SessionManager
import app.antresol.tv.ui.components.PillButton
import app.antresol.tv.ui.components.PillStyle
import app.antresol.tv.ui.components.TvTextField
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.tv.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.components.LogoMark
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.io.IOException

/**
 * First-run screen: the server address, checked with `/api/health` before moving on to the
 * profile picker. A bare host gets `http://` and port 8420.
 */
@Composable
fun ServerScreen(session: SessionManager) {
    val resources = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    var address by rememberSaveable { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        if (address.isEmpty()) address = session.lastServerUrl().orEmpty()
        fieldFocus.tryRequestFocus()
    }

    fun connect() {
        if (connecting) return
        if (address.isBlank()) {
            error = resources.getString(R.string.server_invalid_address)
            return
        }
        connecting = true
        error = null
        scope.launch {
            try {
                session.connect(address)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = when (e) {
                    is NotAntresolServerException -> resources.getString(R.string.server_not_antresol)
                    is ServerNeedsSetupException, is InvalidServerAddressException -> e.userMessage(resources)
                    is IOException -> resources.getString(R.string.server_unreachable)
                    else -> e.userMessage(resources)
                }
                connecting = false
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    0f to AntresolColors.BrandStart.copy(alpha = 0.55f),
                    1f to AntresolColors.Background,
                    radius = 1400f,
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.width(520.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            LogoMark(size = 72.dp)
            Text(stringResource(R.string.server_title), style = Antresol.typography.pageTitle, textAlign = TextAlign.Center)
            Text(
                stringResource(R.string.server_subtitle),
                style = Antresol.typography.body,
                color = AntresolColors.LabelSecondary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            TvTextField(
                value = address,
                onValueChange = {
                    address = it
                    error = null
                },
                placeholder = stringResource(R.string.server_address_hint),
                onSubmit = ::connect,
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(fieldFocus),
            )
            Text(
                text = error ?: if (connecting) stringResource(R.string.server_connecting) else "",
                style = Antresol.typography.caption,
                color = if (error != null) AntresolColors.Red else AntresolColors.LabelSecondary,
                textAlign = TextAlign.Center,
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            PillButton(
                text = stringResource(R.string.server_connect),
                onClick = ::connect,
                style = PillStyle.Primary,
                enabled = !connecting,
            )
        }
    }
}
