package app.antresol.ui.family

import android.content.res.Resources
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.antresol.core.family.Family
import app.antresol.core.family.FamilyKind
import app.antresol.core.model.Chapter
import app.antresol.core.model.FamilyMember
import app.antresol.ui.R
import app.antresol.ui.components.AntresolIcons
import app.antresol.ui.components.ProfileAvatar
import app.antresol.ui.playback.MenuOption
import app.antresol.ui.requests.relativeTime
import app.antresol.ui.theme.Antresol
import app.antresol.ui.theme.AntresolColors

/** How many faces the folded line shows before «+N». */
private const val FACES = 4

/**
 * An avatar inside its progress ring ([Family.ring]): a track and an arc in [ringColor], green with a check badge once
 * everything is done. [ringColor] follows the surface the avatar sits on (a focused TV row is white with black content).
 */
@Composable
fun RingAvatar(
    member: FamilyMember,
    photoUrl: String?,
    size: Dp,
    modifier: Modifier = Modifier,
    ringColor: Color = AntresolColors.Label,
) {
    val stroke = if (size >= 36.dp) 2.5.dp else 2.dp
    val ring = Family.ring(member)
    val arc = if (member.finished) AntresolColors.Green else ringColor
    Box(modifier.size(size + (stroke + 2.dp) * 2), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val width = stroke.toPx()
            val side = this.size.minDimension - width
            val corner = Offset(width / 2, width / 2)
            drawArc(ringColor.copy(alpha = 0.22f), 0f, 360f, false, corner, Size(side, side), style = Stroke(width))
            if (ring > 0f) drawArc(arc, -90f, 360f * ring, false, corner, Size(side, side), style = Stroke(width, cap = StrokeCap.Round))
        }
        ProfileAvatar(name = member.name, avatar = member.avatar, size = size, photoUrl = photoUrl)
        if (member.finished) {
            val badge = size * 0.4f
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(badge)
                    .background(AntresolColors.Green, CircleShape)
                    .border(2.dp, AntresolColors.Background, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(AntresolIcons.Check, contentDescription = null, tint = AntresolColors.LabelInverse, modifier = Modifier.size(badge * 0.62f))
            }
        }
    }
}

/** The faces of the folded line: up to four rings overlapping a little, then «+N». */
@Composable
fun FamilyFaces(
    members: List<FamilyMember>,
    photoUrl: (FamilyMember) -> String?,
    size: Dp,
    ringColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy((-6).dp)) {
        members.take(FACES).forEach { RingAvatar(it, photoUrl(it), size, ringColor = ringColor) }
        if (members.size > FACES) {
            Text(
                "+${members.size - FACES}",
                style = Antresol.typography.caption,
                color = ringColor,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .defaultMinSize(minHeight = size)
                    .background(ringColor.copy(alpha = 0.16f), CircleShape)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * The folded line's content, drawn inside the app's own button: the faces, a small «Семья» above who is where
 * («Alex · С2 Э5 · 34 мин, ещё 2»), and a chevron. [contentColor] and [secondaryColor] follow the button's state.
 */
@Composable
fun FamilyLineContent(
    members: List<FamilyMember>,
    kind: FamilyKind,
    photoUrl: (FamilyMember) -> String?,
    contentColor: Color,
    secondaryColor: Color,
    modifier: Modifier = Modifier,
    chapters: List<Chapter> = emptyList(),
    faceSize: Dp = 28.dp,
) {
    val resources = LocalContext.current.resources
    val more = members.size - 1
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FamilyFaces(members, photoUrl, faceSize, contentColor)
        Column(Modifier.weight(1f, fill = false)) {
            Text(stringResource(R.string.family_title).uppercase(), style = Antresol.typography.eyebrow, color = secondaryColor, maxLines = 1)
            Text(
                FamilyTexts.summary(resources, kind, members, chapters) + if (more > 0) " · " + pluralStringResource(R.plurals.family_and_more, more, more) else "",
                style = Antresol.typography.bodyEmphasis,
                color = contentColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(AntresolIcons.ChevronRight, contentDescription = null, tint = secondaryColor, modifier = Modifier.size(16.dp))
    }
}

/** What a row of the list says about [m]: «С2 Э5 · 34 мин · 2 ч назад». */
fun familyRowDetail(resources: Resources, kind: FamilyKind, m: FamilyMember, chapters: List<Chapter> = emptyList()): String =
    listOf(FamilyTexts.placeOf(resources, kind, m, chapters), relativeTime(m.updatedAt)).filter { it.isNotEmpty() }.joinToString(" · ")

/**
 * The rows of the list, as the menu options of a TV panel and of the player's menu: the ring avatar, the name, where they
 * are and when, and (when there is somewhere to continue) what continuing says. A row without one does nothing.
 */
fun familyMenuOptions(
    resources: Resources,
    kind: FamilyKind,
    members: List<FamilyMember>,
    chapters: List<Chapter>,
    photoUrl: (FamilyMember) -> String?,
    onJump: (FamilyMember) -> Unit,
): List<MenuOption> = members.map { m ->
    MenuOption(
        label = m.displayName,
        selected = false,
        detail = familyRowDetail(resources, kind, m, chapters),
        action = FamilyTexts.jumpLabel(resources, kind, m).ifEmpty { null },
        leading = { tint -> RingAvatar(m, photoUrl(m), 36.dp, ringColor = tint) },
        onSelect = { if (Family.canContinue(kind, m)) onJump(m) },
    )
}
