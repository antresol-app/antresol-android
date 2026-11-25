package app.antresol.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestStatus
import app.antresol.ui.R
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * A request's state as the web shows it: a pill with an icon («Waiting for an answer», «Approved — coming soon»,
 * «Already in the library», «A parent said no») and, under a refusal, the reason the parent gave. Not focusable:
 * the screens put it where a button would be.
 */
@Composable
fun RequestStatusPill(request: KidRequest, modifier: Modifier = Modifier) {
    val accent = requestStatusAccent(request.status)
    val ink = if (accent) AntresolColors.Accent else AntresolColors.Label
    Column(modifier = modifier.testTag("request-status"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .background(if (accent) AntresolColors.Accent.copy(alpha = 0.16f) else AntresolColors.FillTertiary, AntresolShapes.pill)
                .border(1.dp, if (accent) AntresolColors.Accent.copy(alpha = 0.4f) else AntresolColors.Hairline, AntresolShapes.pill)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Icon(requestStatusIcon(request.status), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            Text(stringResource(requestStatusLabel(request.status)), style = Antresol.typography.bodyEmphasis.copy(color = ink), maxLines = 1)
        }
        if (request.status == RequestStatus.DECLINED && request.reason.isNotBlank()) {
            Text(
                stringResource(R.string.request_reason_line, request.reason),
                style = Antresol.typography.caption.copy(color = AntresolColors.LabelSecondary),
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}
