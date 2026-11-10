package app.antresol.mobile.ui.channels

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import app.antresol.core.model.Channel
import app.antresol.ui.R
import app.antresol.ui.channels.ChannelsViewModel
import app.antresol.ui.channels.rememberNowMs

/** Channels shown on Home, at most. */
private const val ON_AIR_MAX = 20

/**
 * «On Air Now» on Home: the channels and what they show, the phone's way into «TV» (its tab bar
 * has room for five tabs, as on the web). The title opens the TV page. Nothing without channels.
 */
@Composable
fun OnAirShelf(viewModel: ChannelsViewModel, onWatch: (Channel) -> Unit, onSeeAll: () -> Unit) {
    LifecycleStartEffect(viewModel) {
        viewModel.startRefreshing()
        onStopOrDispose { viewModel.stopRefreshing() }
    }
    val channels = viewModel.onAir.take(ON_AIR_MAX)
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
