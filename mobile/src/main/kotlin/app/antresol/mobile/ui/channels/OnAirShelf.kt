package app.antresol.mobile.ui.channels

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import app.antresol.mobile.ui.components.CardSizes
import app.antresol.mobile.ui.components.CardStyle
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.SectionTitle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.antresol.core.model.Channel
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.rememberNowMs

/** Home's turn at the shared channel refresh (the «ТВ» tab has its own). */
private const val ON_AIR_WATCHER = "on-air"

/** Channels shown on Home, at most. */
private const val ON_AIR_MAX = 20

/**
 * «Сейчас в эфире» on Home: the channels and what they show. The title opens the «ТВ» tab.
 * Nothing without channels.
 */
@Composable
fun OnAirShelf(viewModel: ChannelsViewModel, onWatch: (Channel) -> Unit, onSeeAll: () -> Unit) {
    LifecycleStartEffect(viewModel) {
        viewModel.startRefreshing(ON_AIR_WATCHER)
        onStopOrDispose { viewModel.stopRefreshing(ON_AIR_WATCHER) }
    }
    val onAir = viewModel.onAir
    val channels = remember(onAir) { onAir.take(ON_AIR_MAX) }
    if (channels.isEmpty()) return
    ChannelShelf(
        title = stringResource(R.string.tv_on_air),
        channels = channels,
        nowMs = rememberNowMs(),
        onWatch = onWatch,
        onSeeAll = onSeeAll,
        modifier = Modifier.padding(top = 20.dp),
    )
}

/** A titled row of channel cards («Сейчас в эфире» on Home). */
@Composable
fun ChannelShelf(
    title: String,
    channels: List<Channel>,
    nowMs: Long,
    onWatch: (Channel) -> Unit,
    modifier: Modifier = Modifier,
    onSeeAll: (() -> Unit)? = null,
) {
    Column(modifier = modifier) {
        SectionTitle(title, onSeeAll = onSeeAll)
        LazyRow(
            contentPadding = PaddingValues(horizontal = PageGutter),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(top = 4.dp),
        ) {
            items(channels, key = { it.id }) { channel ->
                ChannelCard(channel, nowMs, onClick = { onWatch(channel) }, width = CardSizes.width(CardStyle.Wide))
            }
        }
    }
}
