package app.antresol.mobile.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.core.discovery.DiscoveredServer
import app.antresol.core.discovery.DiscoveryState
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * The servers found on the network, above the address field: a grouped block (like Settings) of
 * rows with name, address and version. While nothing is found: "Looking…", then the Docker hint.
 */
@Composable
fun DiscoveredServerList(state: DiscoveryState, enabled: Boolean, onChoose: (DiscoveredServer) -> Unit) {
    if (state.servers.isEmpty()) {
        Text(
            stringResource(if (state.searching) R.string.server_searching else R.string.server_none_found),
            style = Antresol.typography.caption.copy(color = AntresolColors.LabelTertiary),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("discovery-status"),
        )
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.server_found_title).uppercase(),
            style = Antresol.typography.eyebrow,
            modifier = Modifier.padding(start = 4.dp),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .clip(AntresolShapes.card)
                .background(AntresolColors.BackgroundElevated)
                .testTag("discovered-servers"),
        ) {
            state.servers.forEachIndexed { index, server ->
                if (index > 0) HorizontalDivider(color = AntresolColors.Hairline, modifier = Modifier.padding(start = 52.dp))
                ServerRow(server, enabled) { onChoose(server) }
            }
        }
    }
}

@Composable
private fun ServerRow(server: DiscoveredServer, enabled: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, enabled = enabled, onClick = onClick)
            .testTag("server-${server.id}")
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(AntresolIcons.Server, contentDescription = null, tint = AntresolColors.Accent, modifier = Modifier.size(22.dp))
        Column(Modifier.weight(1f)) {
            Text(server.name, style = Antresol.typography.bodyEmphasis, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (server.version.isNotBlank()) stringResource(R.string.server_found_detail, server.address, server.version) else server.address,
                style = Antresol.typography.caption,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = AntresolColors.LabelTertiary, modifier = Modifier.size(18.dp))
    }
}
