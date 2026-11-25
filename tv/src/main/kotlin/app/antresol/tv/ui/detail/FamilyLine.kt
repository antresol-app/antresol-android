package app.antresol.tv.ui.detail

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import app.antresol.core.family.Family
import app.antresol.core.family.FamilyKind
import app.antresol.core.model.Chapter
import app.antresol.core.model.FamilyMember
import app.antresol.tv.ui.LocalMediaUrls
import app.antresol.tv.ui.components.OptionsPanel
import app.antresol.tv.ui.components.listRowColors
import app.antresol.ui.R
import app.antresol.ui.family.FamilyLineContent
import app.antresol.ui.family.FamilyTexts
import app.antresol.ui.family.familyMenuOptions
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * «Семья» under the action buttons: one quiet line, the family's faces each in a ring of how far they are, and who is
 * where. OK opens [FamilyPanel]. Drawn only when somebody else has progress here; white (like every focus) when focused.
 */
@Composable
fun FamilyLine(
    members: List<FamilyMember>,
    kind: FamilyKind,
    chapters: List<Chapter>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (members.isEmpty()) return
    val urls = LocalMediaUrls.current
    val description = stringResource(R.string.family_title) + ": " + FamilyTexts.summary(LocalContext.current.resources, kind, members, chapters)
    Surface(
        onClick = onClick,
        modifier = modifier
            .padding(top = 4.dp)
            .semantics { contentDescription = description }
            .testTag("family-line"),
        shape = ClickableSurfaceDefaults.shape(AntresolShapes.pill),
        colors = listRowColors(),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.04f),
    ) {
        FamilyLineContent(
            members = members,
            kind = kind,
            photoUrl = { urls?.image(it.photo) },
            contentColor = LocalContentColor.current,
            secondaryColor = LocalContentColor.current.copy(alpha = 0.6f),
            chapters = chapters,
            modifier = Modifier.padding(start = 12.dp, end = 18.dp, top = 8.dp, bottom = 8.dp),
        )
    }
}

/**
 * The list behind [FamilyLine]: everyone with their place and when, and (where there is somewhere to continue) what
 * continuing says. OK on a row continues from their place: [onJump] takes it as this profile's own and plays from it.
 */
@Composable
fun FamilyPanel(
    members: List<FamilyMember>,
    kind: FamilyKind,
    chapters: List<Chapter>,
    onJump: (FamilyMember) -> Unit,
    onDismiss: () -> Unit,
) {
    val urls = LocalMediaUrls.current
    val resources = LocalContext.current.resources
    OptionsPanel(
        title = stringResource(R.string.family_title),
        note = stringResource(R.string.family_note),
        options = familyMenuOptions(resources, kind, members, chapters, photoUrl = { urls?.image(it.photo) }, onJump = onJump),
        onDismiss = onDismiss,
        width = FAMILY_PANEL_WIDTH,
        // The first row there is something to continue from, not the first row.
        focusIndex = members.indexOfFirst { Family.canContinue(kind, it) }.coerceAtLeast(0),
    )
}

/** Wide enough for a face, a name and the line of what continuing says. */
val FAMILY_PANEL_WIDTH = 440.dp

/** Under the line: the last continue that did not work. */
@Composable
fun FamilyFailed() {
    Text(
        stringResource(R.string.family_jump_failed),
        style = Antresol.typography.caption,
        color = AntresolColors.Red,
        modifier = Modifier.testTag("family-failed"),
    )
}
