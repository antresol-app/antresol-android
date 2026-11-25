package app.antresol.mobile.ui.requests

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import app.antresol.core.model.KidRequest
import app.antresol.core.model.RequestStatus
import app.antresol.mobile.ui.LocalBottomBarPadding
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.BackBar
import app.antresol.mobile.ui.components.EmptyView
import app.antresol.mobile.ui.components.ErrorView
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.GlassTextField
import app.antresol.mobile.ui.components.LoadingView
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.PageTitle
import app.antresol.mobile.ui.components.PrimaryPillButton
import app.antresol.mobile.ui.components.SectionTitle
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.LoadState
import app.antresol.ui.R
import app.antresol.ui.components.ArtworkImage
import app.antresol.ui.requests.MyRequestsViewModel
import app.antresol.ui.requests.RequestStatusPill
import app.antresol.ui.requests.RequestsViewModel
import app.antresol.ui.requests.relativeTime
import app.antresol.ui.requests.requestKindLabel
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * «My Requests» of a kid profile: what it asked for and what the parents answered, newest first. A tap on a video
 * opens its title page (the library's page once it has arrived).
 */
@Composable
fun MyRequestsScreen(viewModel: MyRequestsViewModel, onOpen: (KidRequest) -> Unit, onBack: () -> Unit) {
    val model = viewModel.requests
    // Back from a title page: a parent may have answered meanwhile.
    LifecycleResumeEffect(viewModel) {
        model.load(quiet = true)
        onPauseOrDispose { }
    }
    RequestsPage(onBack, stringResource(R.string.requests_mine), lead = null, tag = "my-requests") {
        when (val state = model.state) {
            LoadState.Loading -> item("loading") { LoadingView() }
            is LoadState.Failed -> item("error") { ErrorView(state.error, onRetry = { model.load() }) }
            is LoadState.Ready -> {
                if (state.value.isEmpty()) item("empty") { EmptyView(stringResource(R.string.requests_mine_empty)) }
                items(state.value, key = { it.id }) { request ->
                    RequestItem(request, line = relativeTime(request.createdAt), onOpen = { onOpen(request) }) { RequestStatusPill(request) }
                }
            }
        }
    }
}

/**
 * «Requests» of an administrator: the waiting ones with Approve and Decline, then those answered in the last 30
 * days. A tap on a video opens its page, where the title is brought onto the server.
 */
@Composable
fun RequestsScreen(viewModel: RequestsViewModel, onOpen: (KidRequest) -> Unit, onBack: () -> Unit) {
    val model = viewModel.requests
    // Back from a title page: the title may have been kept on the server, which answers its request.
    LifecycleResumeEffect(viewModel) {
        model.load(quiet = true)
        onPauseOrDispose { }
    }
    var declining by remember { mutableStateOf<KidRequest?>(null) }
    RequestsPage(onBack, stringResource(R.string.requests_title), lead = stringResource(R.string.requests_lead), tag = "requests") {
        when (val state = model.state) {
            LoadState.Loading -> item("loading") { LoadingView() }
            is LoadState.Failed -> item("error") { ErrorView(state.error, onRetry = { model.load() }) }
            is LoadState.Ready -> {
                if (model.answerFailed) {
                    item("answer-failed") {
                        Text(
                            stringResource(R.string.requests_answer_failed),
                            style = Antresol.typography.caption.copy(color = AntresolColors.Red),
                            modifier = Modifier.padding(horizontal = PageGutter, vertical = 4.dp),
                        )
                    }
                }
                item("waiting-title") { SectionTitle(stringResource(R.string.requests_waiting), Modifier.padding(top = 12.dp)) }
                if (model.waiting.isEmpty()) item("empty") { EmptyView(stringResource(R.string.requests_empty)) }
                items(model.waiting, key = { it.id }) { request ->
                    RequestItem(request, line = whoAndWhen(request), onOpen = { onOpen(request) }) {
                        if (declining?.id == request.id) {
                            DeclineForm(
                                request,
                                onCancel = { declining = null },
                                onDecline = { reason ->
                                    declining = null
                                    model.decline(request, reason)
                                },
                            )
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                PrimaryPillButton(
                                    text = stringResource(R.string.requests_approve),
                                    enabled = model.busy == null,
                                    onClick = { model.approve(request) },
                                    modifier = Modifier.testTag("request-approve"),
                                )
                                GlassPillButton(
                                    text = stringResource(R.string.requests_decline),
                                    enabled = model.busy == null,
                                    onClick = { declining = request },
                                    modifier = Modifier.testTag("request-decline"),
                                )
                            }
                        }
                    }
                }
                if (model.answered.isNotEmpty()) {
                    item("answered-title") { SectionTitle(stringResource(R.string.requests_decided), Modifier.padding(top = 20.dp)) }
                    items(model.answered, key = { it.id }) { request ->
                        RequestItem(request, line = whoAndWhen(request), onOpen = { onOpen(request) }) { RequestStatusPill(request, admin = true) }
                    }
                }
            }
        }
    }
}

@Composable
private fun whoAndWhen(request: KidRequest): String =
    request.user?.let { stringResource(R.string.requests_from, it.name, relativeTime(request.createdAt)) } ?: relativeTime(request.createdAt)

@Composable
private fun RequestsPage(onBack: () -> Unit, title: String, lead: String?, tag: String, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val bottom = LocalBottomBarPadding.current
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(AntresolColors.Background).testTag(tag),
        contentPadding = PaddingValues(bottom = bottom.calculateBottomPadding() + 16.dp),
    ) {
        item("top") { BackBar(onBack) }
        item("title") { PageTitle(title) }
        if (lead != null) {
            item("lead") {
                Text(lead, style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = PageGutter, vertical = 4.dp))
            }
        }
        content()
    }
}

/** A request: its cover, title, kind and year, who asked and when; [actions] (its state, or the buttons that answer it) under them. */
@Composable
private fun RequestItem(request: KidRequest, line: String, onOpen: () -> Unit, actions: @Composable () -> Unit) {
    val urls = LocalMediaUrls.current
    val title = if (request.hidden) stringResource(R.string.request_hidden) else request.title
    val kicker = listOfNotNull(
        requestKindLabel(request.kind),
        request.year.takeIf { it > 0 }?.toString(),
        request.rating.takeIf { it.isNotBlank() },
    ).joinToString(" · ")
    val openable = request.hasTitlePage || request.status == RequestStatus.IN_LIBRARY && request.libraryItemId != null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = PageGutter - 4.dp, vertical = 6.dp)
            .then(if (openable) Modifier.focusClickable(AntresolShapes.control, onClick = onOpen) else Modifier)
            .padding(4.dp)
            .testTag("request-item"),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        ArtworkImage(
            url = urls?.image(request.poster.takeIf { it.isNotBlank() && !request.hidden }),
            title = title,
            shape = AntresolShapes.badge,
            modifier = Modifier.size(width = 64.dp, height = 96.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (kicker.isNotBlank()) Text(kicker, style = Antresol.typography.caption, maxLines = 1)
            Text(title, style = Antresol.typography.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (request.subtitle.isNotBlank() && !request.hidden) Text(request.subtitle, style = Antresol.typography.caption, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (line.isNotBlank()) Text(line, style = Antresol.typography.caption.copy(color = AntresolColors.LabelSecondary), maxLines = 1)
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) { actions() }
        }
    }
}

/** «Decline» under the request's row: what the kid will see, an optional reason, Cancel and Decline. */
@Composable
private fun DeclineForm(request: KidRequest, onCancel: () -> Unit, onDecline: (reason: String) -> Unit) {
    var reason by remember { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().testTag("decline-form"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.requests_decline_message, request.user?.name.orEmpty()), style = Antresol.typography.caption)
        GlassTextField(
            value = reason,
            onValueChange = { reason = it.take(MAX_REASON) },
            placeholder = stringResource(R.string.requests_reason),
            modifier = Modifier.testTag("decline-reason"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassPillButton(text = stringResource(R.string.action_cancel), onClick = onCancel, modifier = Modifier.testTag("decline-cancel"))
            PrimaryPillButton(text = stringResource(R.string.requests_decline), onClick = { onDecline(reason) }, modifier = Modifier.testTag("decline-confirm"))
        }
    }
}

/** The server keeps a reason of up to this many characters. */
private const val MAX_REASON = 200
