package app.antresol.mobile.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.antresol.mobile.ui.userMessage
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** A centred spinner. */
@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 64.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = AntresolColors.Label, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
    }
}

/** The error sentence with "Try Again". */
@Composable
fun ErrorView(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val message = error.userMessage(LocalContext.current.resources)
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(message, style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary), textAlign = TextAlign.Center)
        GlassPillButton(text = stringResource(R.string.action_retry), onClick = onRetry)
    }
}

/** A quiet sentence for an empty list. */
@Composable
fun EmptyView(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp),
    )
}
