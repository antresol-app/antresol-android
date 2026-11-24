package app.antresol.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.ParentDataModifier
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** What a child of [ActionFlow] can ask for. */
@Immutable
interface ActionFlowScope {
    /** The child takes the room that is left on its line, together with the other children that grow. */
    fun Modifier.grow(): Modifier
}

private object GrowFlag

private object ActionFlowScopeImpl : ActionFlowScope {
    override fun Modifier.grow(): Modifier = this.then(
        object : ParentDataModifier {
            override fun Density.modifyParentData(parentData: Any?): Any = GrowFlag
        },
    )
}

/**
 * A row of buttons that never squeezes one of them: every child gets at least its own width (a
 * label is never cut, wrapped or hyphenated to make room), and a child that does not fit moves to the
 * next line. On each line the children marked [ActionFlowScope.grow] share what is left, so a lone
 * pill fills the width and two pills split it. With [firstAlone] the first child (the main button) takes
 * a line of its own whenever the whole row does not fit on one, and the rest follow below it.
 */
@Composable
fun ActionFlow(
    modifier: Modifier = Modifier,
    spacing: Dp = 12.dp,
    firstAlone: Boolean = false,
    content: @Composable ActionFlowScope.() -> Unit,
) {
    Layout(content = { ActionFlowScopeImpl.content() }, modifier = modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val maxWidth = constraints.maxWidth
        val natural = measurables.map { it.maxIntrinsicWidth(Constraints.Infinity).coerceAtMost(maxWidth) }
        val grows = measurables.map { it.parentData === GrowFlag }
        val fitsOnOneLine = natural.sum() + gap * (natural.size - 1).coerceAtLeast(0) <= maxWidth

        // Greedy lines of child indices.
        val lines = mutableListOf<MutableList<Int>>()
        var lineWidth = 0
        measurables.indices.forEach { index ->
            val current = lines.lastOrNull()
            val breakHere = current == null ||
                lineWidth + gap + natural[index] > maxWidth ||
                (firstAlone && !fitsOnOneLine && lines.size == 1 && current.size == 1)
            if (breakHere) {
                lines += mutableListOf(index)
                lineWidth = natural[index]
            } else {
                current!! += index
                lineWidth += gap + natural[index]
            }
        }

        val placed = lines.map { line ->
            val free = (maxWidth - line.sumOf { natural[it] } - gap * (line.size - 1)).coerceAtLeast(0)
            val growers = line.count { grows[it] }
            var rest = free
            var seen = 0
            line.map { index ->
                val extra = if (grows[index] && growers > 0) {
                    seen++
                    if (seen == growers) rest else (free / growers).also { rest -= it }
                } else {
                    0
                }
                val width = natural[index] + extra
                measurables[index].measure(Constraints(minWidth = width, maxWidth = width))
            }
        }
        val lineHeights = placed.map { line -> line.maxOfOrNull { it.height } ?: 0 }
        val height = lineHeights.sum() + gap * (lines.size - 1).coerceAtLeast(0)
        val width = if (constraints.hasBoundedWidth) maxWidth else placed.maxOfOrNull { line -> line.sumOf { it.width } + gap * (line.size - 1) } ?: 0
        layout(width, height.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            var y = 0
            placed.forEachIndexed { row, line ->
                var x = 0
                line.forEach { placeable ->
                    placeable.placeRelative(x, y + (lineHeights[row] - placeable.height) / 2)
                    x += placeable.width + gap
                }
                y += lineHeights[row] + gap
            }
        }
    }
}
