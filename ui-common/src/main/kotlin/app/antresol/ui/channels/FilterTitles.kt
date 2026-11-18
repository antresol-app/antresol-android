package app.antresol.ui.channels

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.antresol.core.channels.ChannelFilter
import app.antresol.core.channels.Channels
import app.antresol.core.model.ChannelCategory
import app.antresol.ui.R

/**
 * What a channel filter is called: «Все каналы», «Избранное», a category by its name (the server's
 * [categories], else a group's own name) or «Без категории».
 */
@Composable
fun channelFilterTitle(filter: ChannelFilter, categories: List<ChannelCategory> = emptyList()): String = when (filter) {
    ChannelFilter.All -> stringResource(R.string.tv_all_channels)
    ChannelFilter.Favorites -> stringResource(R.string.tv_favorites)
    is ChannelFilter.Category -> Channels.categoryName(filter, categories) ?: stringResource(R.string.tv_category_none)
}

/** The heading of a kind of category in a list of them: «Темы», «Источники», «Группы» (null: no heading for it). */
@Composable
fun categoryKindTitle(kind: String?): String? = when (kind) {
    ChannelCategory.KIND_AI -> stringResource(R.string.tv_category_kind_ai)
    ChannelCategory.KIND_SOURCE -> stringResource(R.string.tv_category_kind_source)
    ChannelCategory.KIND_GROUP -> stringResource(R.string.tv_category_kind_group)
    else -> null
}
