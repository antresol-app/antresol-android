package app.antresol.tv.ui.detail

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import app.antresol.core.model.AddonWay
import app.antresol.core.model.WayState
import app.antresol.core.ways.WayPicker
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.PillButton
import app.antresol.ui.R
import app.antresol.ui.components.WayRing
import app.antresol.ui.components.wayIcon
import app.antresol.ui.playback.MenuOption
import app.antresol.ui.userMessage

/**
 * The ways to watch a title or an episode, as a panel on the right: OK runs a way at once, hold OK
 * chooses by hand (the panel says so). All text is the server's, the ways' own labels.
 */
@Composable
fun WaysListPanel(
    ways: List<AddonWay>,
    onRun: (AddonWay) -> Unit,
    onChoose: (AddonWay) -> Unit,
    onDismiss: () -> Unit,
) {
    OptionsPanel(
        title = stringResource(R.string.way_watch_title),
        note = stringResource(R.string.way_hold_hint),
        options = ways.map { way ->
            MenuOption(way.label, selected = false, onLongSelect = { onChoose(way) }) { onRun(way) }
        },
        onDismiss = onDismiss,
        focusIndex = 0,
    )
}

/**
 * The choice of a way, step by step: the rows of the step (the one picked last is checked, a row
 * that leads on has an arrow), the server's note and «nothing found», what is going on, and what
 * failed. Back returns to the step before, and from the first one closes the panel.
 */
@Composable
fun WayStepPanel(picker: WayPicker, onChoose: (app.antresol.core.model.StepRow) -> Unit, onBack: () -> Unit) {
    val resources = LocalContext.current.resources
    val step = picker.step
    val rows = step?.rows.orEmpty()
    val working = if (picker.loading || picker.running) stringResource(R.string.way_busy) else null
    val note = listOfNotNull(
        picker.error?.userMessage(resources),
        working,
        step?.note?.takeIf { it.isNotBlank() },
        step?.empty?.takeIf { rows.isEmpty() && !picker.loading && it.isNotBlank() } ?: stringResource(R.string.way_empty_step).takeIf { step != null && rows.isEmpty() && step.empty.isNullOrBlank() },
    ).joinToString("\n").ifBlank { null }
    val options = if (rows.isEmpty()) {
        // Nothing to pick: one row to leave by, so focus has somewhere to be.
        listOf(MenuOption(stringResource(R.string.way_cancel), selected = false) { onBack() })
    } else {
        rows.map { row ->
            MenuOption(
                label = row.label,
                selected = row.current,
                badge = row.badges.joinToString(" · ").ifBlank { null },
                detail = row.detail,
                more = !row.isFinal,
            ) { if (!picker.loading && !picker.running) onChoose(row) }
        }
    }
    OptionsPanel(
        title = step?.title.orEmpty(),
        note = note,
        noteLines = 5,
        width = 520.dp,
        options = options,
        onDismiss = onBack,
        closeOnSelect = false,
        focusKey = picker.steps.size,
        focusIndex = rows.indexOfFirst { it.current }.coerceAtLeast(0),
    )
}

/** «Cancel» of a keep way that is working, with what it says about itself. */
@Composable
fun KeepMenuPanel(way: AddonWay, state: WayState?, onCancel: () -> Unit, onDismiss: () -> Unit) {
    OptionsPanel(
        title = way.label,
        note = state?.text?.takeIf { it.isNotBlank() },
        options = listOf(MenuOption(stringResource(R.string.way_cancel), selected = false) { onCancel() }),
        onDismiss = onDismiss,
        focusIndex = 0,
    )
}

/**
 * The button of a way that keeps a copy on the server: the way's icon, with a ring around it while
 * it works ([state] active) that shows how far it is.
 */
@Composable
fun KeepWayPill(
    way: AddonWay,
    state: WayState?,
    working: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val active = state?.active == true
    PillButton(
        text = if (working) stringResource(R.string.way_busy) else way.label,
        onClick = onClick,
        onLongClick = onLongClick,
        enabled = !working,
        leading = {
            Icon(wayIcon(way.icon), contentDescription = null, modifier = Modifier.size(16.dp))
            if (active || working) WayRing(fraction = state?.fraction?.toFloat().takeIf { active }, modifier = Modifier.size(22.dp))
        },
        modifier = modifier,
    )
}
