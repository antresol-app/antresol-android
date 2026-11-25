package app.antresol.mobile.ui.detail

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.unit.dp
import app.antresol.core.family.Family
import app.antresol.core.family.FamilyKind
import app.antresol.core.model.Chapter
import app.antresol.core.model.FamilyMember
import app.antresol.mobile.ui.LocalMediaUrls
import app.antresol.mobile.ui.components.GlassPillButton
import app.antresol.mobile.ui.components.PageGutter
import app.antresol.mobile.ui.components.focusClickable
import app.antresol.ui.R as UiR
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.family.FamilyLineContent
import app.antresol.ui.family.FamilyTexts
import app.antresol.ui.family.RingAvatar
import app.antresol.ui.family.familyRowDetail
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors
import app.antresol.ui.theme.AntresolShapes

/**
 * «Семья» under the action buttons: one quiet line, the family's faces each in a ring of how far they are, and who is
 * where. A tap opens [FamilySheet]. Drawn only when somebody else has progress here.
 */
@Composable
fun FamilyLine(
    members: List<FamilyMember>,
    kind: FamilyKind,
    chapters: List<Chapter>,
    failed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (members.isEmpty()) return
    val urls = LocalMediaUrls.current
    val description = stringResource(UiR.string.family_title) + ": " + FamilyTexts.summary(LocalContext.current.resources, kind, members, chapters)
    Column(modifier.fillMaxWidth().padding(horizontal = PageGutter - 6.dp, vertical = 4.dp)) {
        FamilyLineContent(
            members = members,
            kind = kind,
            photoUrl = { urls?.image(it.photo) },
            contentColor = AntresolColors.Label,
            secondaryColor = AntresolColors.LabelSecondary,
            chapters = chapters,
            modifier = Modifier
                .clip(AntresolShapes.pill)
                .focusClickable(AntresolShapes.pill, onClick = onClick)
                .semantics { contentDescription = description }
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
                .testTag("family-line"),
        )
        if (failed) {
            Text(
                stringResource(UiR.string.family_jump_failed),
                style = Antresol.typography.caption.copy(color = AntresolColors.Red),
                modifier = Modifier.padding(horizontal = 6.dp).testTag("family-failed"),
            )
        }
    }
}

/**
 * The list behind [FamilyLine], as a bottom sheet: everyone with their place and when, and where there is somewhere to
 * continue a button saying what it does. A tap on it takes their position as this profile's own and plays from it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FamilySheet(
    members: List<FamilyMember>,
    kind: FamilyKind,
    chapters: List<Chapter>,
    onJump: (FamilyMember) -> Unit,
    onDismiss: () -> Unit,
) {
    val resources = LocalContext.current.resources
    val urls = LocalMediaUrls.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = AntresolColors.BackgroundElevated,
        shape = AntresolShapes.sheet,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp)
                .testTag("family-sheet"),
        ) {
            Text(
                stringResource(UiR.string.family_title),
                style = Antresol.typography.sectionTitle,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            members.forEach { m ->
                val label = FamilyTexts.jumpLabel(resources, kind, m)
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).testTag("family-row-${m.userId}"),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    RingAvatar(m, urls?.image(m.photo), size = 40.dp)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(m.displayName, style = Antresol.typography.bodyEmphasis, maxLines = 1)
                        Text(familyRowDetail(resources, kind, m, chapters), style = Antresol.typography.caption)
                        if (label.isNotEmpty() && Family.canContinue(kind, m)) {
                            GlassPillButton(
                                text = label,
                                icon = AntresolIcons.Play,
                                onClick = { onJump(m) },
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .semantics { contentDescription = FamilyTexts.jumpDescription(resources, label, m) }
                                    .testTag("family-jump-${m.userId}"),
                            )
                        }
                    }
                }
            }
            Text(
                stringResource(UiR.string.family_note),
                style = Antresol.typography.caption,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }
    }
}
