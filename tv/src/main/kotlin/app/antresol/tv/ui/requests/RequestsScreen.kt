package app.antresol.tv.ui.requests

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestStatus
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.ErrorView
import app.antresol.tv.ui.components.LoadingView
import app.antresol.tv.ui.components.MessageView
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.ScreenPaddingH
import app.antresol.tv.ui.components.VerticalShelfScrolling
import app.antresol.tv.ui.components.listRowColors
import app.antresol.tv.ui.components.rememberFocusMemory
import app.antresol.tv.ui.components.remembersFocus
import app.antresol.tv.ui.components.tryRequestFocus
import app.antresol.ui.LoadState
import app.antresol.ui.R
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.playback.MenuOption
import app.antresol.ui.requests.MyRequestsViewModel
import app.antresol.ui.requests.RequestsViewModel
import app.antresol.ui.requests.relativeTime
import app.antresol.ui.requests.requestKindLabel
import app.antresol.ui.requests.requestStatusIcon
import app.antresol.ui.requests.requestStatusLabel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/** A titled group of requests on the page. */
private data class RequestGroup(val id: String, val title: String?, val rows: List<KidRequest>, val emptyHint: String? = null)

/**
 * «My Requests» of a kid profile: what it asked for and what the parents answered, newest first. OK on a video opens
 * its title page (the library's page once it has arrived).
 */
@Composable
fun MyRequestsScreen(
    viewModel: MyRequestsViewModel,
    onOpen: (KidRequest) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val model = viewModel.requests
    // Back from a title page: a parent may have answered meanwhile.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.load(quiet = true) }
    val state = model.state
    RequestsPage(
        title = stringResource(R.string.requests_mine),
        lead = null,
        state = state,
        groups = listOf(RequestGroup("mine", null, (state as? LoadState.Ready)?.value.orEmpty())),
        emptyText = stringResource(R.string.requests_mine_empty),
        errorNote = null,
        admin = false,
        lineOf = { relativeTime(it.createdAt) },
        onRetry = { model.load() },
        onClick = onOpen,
        onScrolledToTop = onScrolledToTop,
        canTakeFocus = canTakeFocus,
    )
}

/**
 * «Requests» of an administrator: the waiting ones first, then those answered in the last 30 days. OK on a waiting one
 * offers Approve, Decline and the title's page (where the title is brought onto the server); on an answered one it
 * opens the page.
 */
@Composable
fun RequestsScreen(
    viewModel: RequestsViewModel,
    onOpen: (KidRequest) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    onPanelOpenChanged: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val model = viewModel.requests
    // Back from a title page: the title may have been kept on the server, which answers its request.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.load(quiet = true) }
    var answering by remember { mutableStateOf<KidRequest?>(null) }
    LaunchedEffect(answering != null) { onPanelOpenChanged(answering != null) }
    // After an answer the request leaves the waiting ones: focus goes to the next waiting request, not after the one answered.
    var answeredFrom by remember { mutableStateOf<Int?>(null) }
    val waiting = model.waiting
    RequestsPage(
        title = stringResource(R.string.requests_title),
        lead = stringResource(R.string.requests_lead),
        state = model.state,
        groups = listOf(
            RequestGroup("waiting", stringResource(R.string.requests_waiting), waiting, emptyHint = stringResource(R.string.requests_empty)),
            RequestGroup("answered", stringResource(R.string.requests_decided).takeIf { model.answered.isNotEmpty() }, model.answered),
        ),
        emptyText = stringResource(R.string.requests_empty),
        errorNote = stringResource(R.string.requests_answer_failed).takeIf { model.answerFailed },
        refocus = answeredFrom?.takeIf { it != waiting.size },
        onRefocused = { answeredFrom = null },
        admin = true,
        lineOf = { request ->
            stringResource(R.string.requests_from, request.user?.name.orEmpty(), relativeTime(request.createdAt)).takeIf { request.user != null }
                ?: relativeTime(request.createdAt)
        },
        onRetry = { model.load() },
        onClick = { request -> if (request.status == RequestStatus.PENDING) answering = request else onOpen(request) },
        onScrolledToTop = onScrolledToTop,
        canTakeFocus = canTakeFocus,
    )
    answering?.let { request ->
        OptionsPanel(
            title = request.title,
            note = listOf(request.user?.name.orEmpty(), relativeTime(request.createdAt)).filter { it.isNotBlank() }.joinToString(" · "),
            options = buildList {
                add(MenuOption(stringResource(R.string.requests_approve), selected = false) { answeredFrom = waiting.size; model.approve(request) })
                add(MenuOption(stringResource(R.string.requests_decline), selected = false) { answeredFrom = waiting.size; model.decline(request, "") })
                if (request.hasTitlePage) add(MenuOption(stringResource(R.string.requests_open_title), selected = false) { onOpen(request) })
            },
            onDismiss = { answering = null },
            focusIndex = 0,
        )
    }
}

@Composable
private fun RequestsPage(
    title: String,
    lead: String?,
    state: LoadState<List<KidRequest>>,
    groups: List<RequestGroup>,
    emptyText: String,
    errorNote: String?,
    admin: Boolean,
    /** Set once an answer has taken a request off the waiting ones (the number then waiting): focus goes to the first row. */
    refocus: Int? = null,
    onRefocused: () -> Unit = {},
    lineOf: @Composable (KidRequest) -> String,
    onRetry: () -> Unit,
    onClick: (KidRequest) -> Unit,
    onScrolledToTop: (Boolean) -> Unit,
    canTakeFocus: () -> Boolean,
) {
    val focus = rememberFocusMemory()
    val listState = rememberLazyListState()
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 40 } }
    LaunchedEffect(atTop) { onScrolledToTop(atTop) }
    val first = groups.firstNotNullOfOrNull { it.rows.firstOrNull() }
    LaunchedEffect(first?.id, refocus) {
        val row = first ?: return@LaunchedEffect
        if (refocus != null) {
            // The remembered row followed its request into «answered»: the first one is where the parent goes on.
            focus.requester("request/${row.id}").tryRequestFocus()
            onRefocused()
        } else {
            focus.restore("request/${row.id}", canTakeFocus)
        }
    }
    VerticalShelfScrolling {
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(top = 84.dp, bottom = 56.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize().testTag("page/requests"),
        ) {
            item(key = "title") {
                Column(Modifier.padding(horizontal = ScreenPaddingH).padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(title, style = Antresol.typography.pageTitle)
                    if (lead != null) Text(lead, style = Antresol.typography.caption, color = AntresolColors.LabelSecondary, modifier = Modifier.width(LEAD_WIDTH))
                    if (errorNote != null) Text(errorNote, style = Antresol.typography.caption, color = AntresolColors.Red)
                }
            }
            when (state) {
                LoadState.Loading -> item(key = "loading") { Box(Modifier.fillMaxWidth().height(260.dp)) { LoadingView() } }
                is LoadState.Failed -> item(key = "error") { Box(Modifier.fillMaxWidth().height(260.dp)) { ErrorView(state.error, onRetry = onRetry, takeFocus = false) } }
                is LoadState.Ready -> {
                    if (groups.all { it.rows.isEmpty() }) {
                        item(key = "empty") { Box(Modifier.fillMaxWidth().height(260.dp)) { MessageView(emptyText) } }
                    }
                    groups.forEach { group ->
                        // A group with no rows says nothing, unless it has a hint and another group has rows (nothing waits).
                        if (group.rows.isEmpty() && (group.emptyHint == null || groups.all { it.rows.isEmpty() })) return@forEach
                        if (group.title != null) {
                            item(key = "group/${group.id}") {
                                Text(group.title, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(start = ScreenPaddingH, end = ScreenPaddingH, top = 14.dp, bottom = 4.dp))
                            }
                        }
                        if (group.rows.isEmpty()) {
                            item(key = "hint/${group.id}") {
                                Text(group.emptyHint.orEmpty(), style = Antresol.typography.body, color = AntresolColors.LabelSecondary, modifier = Modifier.padding(horizontal = ScreenPaddingH))
                            }
                        }
                        items(group.rows, key = { "request/${it.id}" }) { request ->
                            RequestRow(
                                request = request,
                                line = lineOf(request),
                                admin = admin,
                                onClick = { onClick(request) },
                                modifier = Modifier.padding(horizontal = ScreenPaddingH).remembersFocus(focus, "request/${request.id}"),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A request: its cover, title, kind and year, who asked and when, and its state on the right. */
@Composable
private fun RequestRow(request: KidRequest, line: String, admin: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val urls = LocalMediaUrls.current
    val title = if (request.hidden) stringResource(R.string.request_hidden) else request.title
    val kicker = listOfNotNull(
        requestKindLabel(request.kind),
        request.year.takeIf { it > 0 }?.toString(),
        request.rating.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("request-row"),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.control),
        colors = listRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ArtworkImage(
                url = urls?.image(request.poster.takeIf { it.isNotBlank() && !request.hidden }),
                title = title,
                shape = AntresolShapes.badge,
                modifier = Modifier.size(width = 40.dp, height = 60.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(title, style = Antresol.typography.bodyEmphasis, color = LocalContentColor.current, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (kicker.isNotBlank()) {
                    Text(kicker, style = Antresol.typography.caption, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1)
                }
                if (request.subtitle.isNotBlank() && !request.hidden) {
                    Text(request.subtitle, style = Antresol.typography.caption, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (line.isNotBlank()) {
                    Text(line, style = Antresol.typography.caption, color = LocalContentColor.current.copy(alpha = 0.65f), maxLines = 1)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(requestStatusIcon(request.status), contentDescription = null, tint = LocalContentColor.current, modifier = Modifier.size(18.dp))
                    Text(stringResource(requestStatusLabel(request.status, admin)), style = Antresol.typography.body, color = LocalContentColor.current, maxLines = 1)
                }
                if (request.status == RequestStatus.DECLINED && request.reason.isNotBlank()) {
                    Text(
                        stringResource(R.string.request_reason_line, request.reason),
                        style = Antresol.typography.caption,
                        color = LocalContentColor.current.copy(alpha = 0.65f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.End,
                        modifier = Modifier.width(260.dp),
                    )
                }
            }
        }
    }
}

private val LEAD_WIDTH = 720.dp

/** «3 requests waiting» for the Settings entry of an administrator (nothing when none wait). */
@Composable
fun waitingRequestsText(count: Int): String? =
    if (count > 0) pluralStringResource(R.plurals.requests_waiting_count, count, count) else null
