package app.antresol.tv.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Text
import app.antresol.tv.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** A centred spinner for screens that are loading. */
@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AntresolColors.LabelSecondary, strokeWidth = 3.dp, modifier = Modifier.size(40.dp))
    }
}

/** An error message with a "Try Again" pill, focused unless [takeFocus] is false. */
@Composable
fun ErrorView(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier, takeFocus: Boolean = true) {
    val focus = remember { FocusRequester() }
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = error.userMessage(LocalContext.current.resources),
            style = Antresol.typography.body,
            color = AntresolColors.LabelSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
        PillButton(
            text = stringResource(R.string.action_retry),
            onClick = onRetry,
            style = PillStyle.Primary,
            modifier = Modifier.focusRequester(focus),
        )
    }
    LaunchedEffect(Unit) { if (takeFocus) focus.tryRequestFocus() }
}

/** A quiet centred message (empty library, no results). */
@Composable
fun MessageView(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = Antresol.typography.body,
            color = AntresolColors.LabelSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 520.dp),
        )
    }
}
