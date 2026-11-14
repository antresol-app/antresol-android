package app.antresol.tv.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Glow
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.discovery.DiscoveredServer
import app.antresol.core.discovery.DiscoveryState
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolMotion
import app.antresol.ui.theme.AntresolShapes

/**
 * The servers found on the network, above the address field: glass rows (white when focused) with
 * the server's name, address and version. While nothing is found: "Looking…", then the hint that a
 * server in Docker needs its address typed. [firstFocus] goes on the first row. [title] replaces
 * the list's eyebrow; [centered] = false aligns the status text to the start (a left column).
 */
@Composable
fun DiscoveredServerList(
    state: DiscoveryState,
    enabled: Boolean,
    onChoose: (DiscoveredServer) -> Unit,
    firstFocus: FocusRequester,
    modifier: Modifier = Modifier,
    title: String? = null,
    centered: Boolean = true,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state.servers.isEmpty()) {
            Text(
                text = stringResource(if (state.searching) R.string.server_searching else R.string.server_none_found),
                style = Antresol.typography.caption,
                color = AntresolColors.LabelTertiary,
                textAlign = if (centered) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth().testTag("discovery-status"),
            )
            return@Column
        }
        Text(
            (title ?: stringResource(R.string.server_found_title)).uppercase(),
            style = Antresol.typography.eyebrow,
            modifier = Modifier.padding(start = 4.dp),
        )
        // Two rows and a peek fit above the field; more scroll with the D-pad.
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 140.dp).testTag("discovered-servers"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 4.dp, horizontal = 6.dp),
        ) {
            items(state.servers, key = { it.id }) { server ->
                ServerRow(
                    server = server,
                    enabled = enabled,
                    onClick = { onChoose(server) },
                    modifier = if (server == state.servers.first()) Modifier.focusRequester(firstFocus) else Modifier,
                )
            }
        }
    }
}

@Composable
private fun ServerRow(server: DiscoveredServer, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().testTag("server-${server.id}"),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.control),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = AntresolColors.Glass,
            contentColor = AntresolColors.Label,
            focusedContainerColor = AntresolColors.Label,
            focusedContentColor = AntresolColors.LabelInverse,
            pressedContainerColor = AntresolColors.LabelSecondary,
            pressedContentColor = AntresolColors.LabelInverse,
            disabledContainerColor = AntresolColors.FillTertiary,
            disabledContentColor = AntresolColors.LabelTertiary,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = AntresolMotion.CARD_FOCUS_SCALE),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = Glow(Color.White.copy(alpha = 0.3f), 10.dp)),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Icon(AntresolIcons.Server, contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(22.dp))
            Column(Modifier.weight(1f)) {
                Text(server.name, style = Antresol.typography.bodyEmphasis, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (server.version.isNotBlank()) stringResource(R.string.server_found_detail, server.address, server.version) else server.address,
                    style = Antresol.typography.caption,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 1,
                )
            }
            Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = LocalContentColor.current.copy(alpha = 0.7f), modifier = Modifier.size(16.dp))
        }
    }
}
