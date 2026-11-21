package app.antresol.mobile.ui.detail

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.antresol.core.model.AddonWay
import app.antresol.core.model.StepRow
import app.antresol.core.model.WayState
import app.antresol.core.ways.WayPicker
import app.antresol.mobile.ui.components.MobileIcons
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.Badge
import app.antresol.ui.components.WayRing
import app.antresol.ui.components.wayIcon
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes
import app.antresol.ui.userMessage

/**
 * The ways to watch a title or an episode, as a bottom sheet: a tap runs a way at once, a long press
 * chooses by hand (the sheet says so). All text is the server's, the ways' own labels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WaysSheet(
    ways: List<AddonWay>,
    busy: String?,
    onRun: (AddonWay) -> Unit,
    onChoose: (AddonWay) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp).testTag("ways-sheet")) {
            Text(
                stringResource(UiR.string.way_watch_title),
                style = Antresol.typography.sectionTitle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            ways.forEach { way -> WayRow(way, working = busy == way.key, onRun = { onRun(way) }, onChoose = { onChoose(way) }) }
            Text(
                stringResource(UiR.string.way_hold_hint),
                style = Antresol.typography.caption,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WayRow(way: AddonWay, working: Boolean, onRun: () -> Unit, onChoose: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, enabled = !working, onLongClick = onChoose, onClick = onRun)
            .testTag("way-${way.id}")
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(wayIcon(way.icon), contentDescription = null, tint = AntresolColors.Label, modifier = Modifier.size(24.dp))
        Text(
            if (working) stringResource(UiR.string.way_busy) else way.label,
            style = Antresol.typography.body.copy(color = AntresolColors.Label),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (working) CircularProgressIndicator(strokeWidth = 2.dp, color = AntresolColors.LabelSecondary, modifier = Modifier.size(20.dp))
    }
}

/**
 * The choice of a way, step by step (the first step, then the next of each row that is not final; a
 * final row runs the way). Back returns to the step before; closing the sheet leaves the choice.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun WayStepSheet(picker: WayPicker, onChoose: (StepRow) -> Unit, onBack: () -> Unit, onDismiss: () -> Unit) {
    val resources = LocalContext.current.resources
    val step = picker.step
    val busy = picker.loading || picker.running
    BackHandler(enabled = picker.canGoBack || busy, onBack = onBack)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 160.dp)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
                .testTag("way-step-sheet"),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (picker.canGoBack) {
                    val back = stringResource(UiR.string.way_step_back)
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .focusClickable(CircleShape, onClick = onBack)
                            .semantics { contentDescription = back }
                            .testTag("way-step-back"),
                        contentAlignment = Alignment.Center,
                    ) { Icon(MobileIcons.ChevronLeft, contentDescription = null, tint = AntresolColors.Label, modifier = Modifier.size(22.dp)) }
                }
                Text(
                    step?.title.orEmpty(),
                    style = Antresol.typography.sectionTitle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp, vertical = 4.dp),
                )
                if (busy) CircularProgressIndicator(strokeWidth = 2.dp, color = AntresolColors.LabelSecondary, modifier = Modifier.size(22.dp).testTag("way-step-busy"))
            }
            step?.note?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            picker.error?.let { error ->
                Text(
                    error.userMessage(resources),
                    style = Antresol.typography.caption.copy(color = AntresolColors.Red),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp).testTag("way-step-error"),
                )
            }
            when {
                step == null -> Box(Modifier.fillMaxWidth().heightIn(min = 120.dp), contentAlignment = Alignment.Center) {}
                step.rows.isEmpty() -> Text(
                    step.empty?.takeIf { it.isNotBlank() } ?: stringResource(UiR.string.way_empty_step),
                    style = Antresol.typography.body.copy(color = AntresolColors.LabelSecondary),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 24.dp).testTag("way-step-empty"),
                )
                else -> Column(Modifier.verticalScroll(rememberScrollState()).heightIn(max = 480.dp)) {
                    step.rows.forEachIndexed { index, row ->
                        StepRowView(row, enabled = !busy, onClick = { onChoose(row) }, modifier = Modifier.testTag("way-step-row-$index"))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StepRowView(row: StepRow, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .focusClickable(AntresolShapes.control, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(row.label, style = Antresol.typography.body.copy(color = AntresolColors.Label), maxLines = 3, overflow = TextOverflow.Ellipsis)
            row.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Antresol.typography.caption, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (row.badges.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.badges.forEach { Badge(it) }
                }
            }
        }
        if (row.current) Icon(MobileIcons.Check, contentDescription = null, tint = AntresolColors.Accent, modifier = Modifier.size(22.dp))
        if (!row.isFinal) Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = AntresolColors.LabelTertiary, modifier = Modifier.size(18.dp))
    }
}

/**
 * The round button of a way that keeps a copy on the server. While it works ([state] active) a ring
 * around it shows how far it is; with «Cancel» on offer a tap opens that menu, else a tap runs it.
 */
@Composable
fun KeepWayButton(
    way: AddonWay,
    state: WayState?,
    working: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state?.active == true
    val description = listOfNotNull(way.label, state?.text?.takeIf { it.isNotBlank() }).joinToString(". ")
    Box(modifier.size(50.dp).testTag("way-keep-${way.id}"), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(AntresolColors.Glass)
                .border(1.dp, AntresolColors.GlassBorder, CircleShape)
                .focusClickable(CircleShape, enabled = !working, onLongClick = onLongClick, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(wayIcon(way.icon), contentDescription = null, tint = AntresolColors.Label, modifier = Modifier.size(22.dp))
        }
        if (active || working) WayRing(fraction = state?.fraction?.toFloat().takeIf { active }, modifier = Modifier.size(50.dp))
    }
}

/** «Cancel» of a keep way that is working, with what it says about itself. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepMenuSheet(way: AddonWay, state: WayState?, onCancel: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(bottom = 16.dp).testTag("way-keep-menu")) {
            Text(way.label, style = Antresol.typography.sectionTitle, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            state?.text?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = Antresol.typography.caption, modifier = Modifier.padding(horizontal = 20.dp, vertical = 2.dp))
            }
            Text(
                stringResource(UiR.string.way_cancel),
                style = Antresol.typography.body.copy(color = AntresolColors.Red),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusClickable(AntresolShapes.control) {
                        onCancel()
                        onDismiss()
                    }
                    .testTag("way-keep-cancel")
                    .padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}
