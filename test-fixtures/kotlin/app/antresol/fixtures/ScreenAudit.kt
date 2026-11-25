package app.antresol.fixtures

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.text.TextLayoutResult
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * An activity whose text is scaled by [fontScale], as a phone set to that font size does: sheets and dialogs (their
 * own windows) follow it too. The language and the window size are the test's own (`@Config(qualifiers = …)`).
 */
class AuditActivity : ComponentActivity() {
    override fun attachBaseContext(newBase: Context) {
        val base = newBase.resources
        val config = Configuration(base.configuration).apply {
            fontScale = AuditActivity.fontScale
            // The copy of the window's configuration has no density of its own: without it the context drew at another one.
            densityDpi = base.displayMetrics.densityDpi
        }
        val context = newBase.createConfigurationContext(config)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, base.displayMetrics)
        super.attachBaseContext(context)
    }

    companion object {
        @Volatile var fontScale = 1f
    }
}

/** One thing a screen did wrong in one scene: a text cut short, two texts over each other, a missing translation. */
data class Finding(val scene: String, val kind: String, val text: String, val detail: String = "") {
    override fun toString() = "$scene\t$kind\t$text\t$detail"
}

/**
 * Settings (and other) screens drawn in a real activity per language, window size and font scale, with the
 * checks a person makes by eye: text that ends in «…» or is clipped, texts that overlap, texts outside the
 * window, a key shown instead of its text, Cyrillic in the English interface, a Russian line with no
 * Cyrillic at all. [report] gathers the [findings]; the shots go to [dir] as `<scene>.png`.
 *
 * One window per font scale ([launch]); the language and size come from the test's `@Config(qualifiers)`, the font
 * scale from the activity's own context ([AuditActivity]), so dialogs and sheets (separate windows) follow it too.
 */
class ScreenAudit(private val compose: ComposeTestRule, private val dir: File) {
    val findings = ArrayList<Finding>()

    /** What each window was (size, density, font scale), for whoever reads the shots. */
    val notes = ArrayList<String>()

    init {
        dir.mkdirs()
    }

    /** A window in [language] (the test's `@Config`) with text scaled by [fontScale], showing [content]. */
    fun launch(language: String, fontScale: Float, manualClock: Boolean = false, content: @Composable () -> Unit): ActivityController<AuditActivity> {
        AuditActivity.fontScale = fontScale
        // As the apps do for the interface language: names of languages and dates follow it.
        Locale.setDefault(Locale(language))
        val controller = Robolectric.buildActivity(AuditActivity::class.java).setup()
        val resources = controller.get().resources
        val spoken = resources.configuration.locales[0].language
        if (spoken != language) findings += Finding("launch", "locale-not-applied", language, "the window speaks $spoken")
        notes += "$language: ${resources.displayMetrics.widthPixels}x${resources.displayMetrics.heightPixels} px at ${resources.displayMetrics.density}, font ${resources.configuration.fontScale}"
        // [manualClock]: the clock is ours (see [settle]), so an animation that never ends (a focus glow) cannot keep a test
        // waiting for «idle»; a page that scrolls needs the test's own clock instead (a scroll waits for frames).
        compose.mainClock.autoAdvance = !manualClock
        controller.get().setContent(content = content)
        settle(10)
        return controller
    }

    /** Lets [frames] frames pass and the main looper (network answers) run, without ever waiting for the UI to be idle. */
    fun settle(frames: Int = 6) {
        repeat(frames) {
            Thread.sleep(10)
            ShadowLooper.idleMainLooper(50, TimeUnit.MILLISECONDS)
            compose.mainClock.advanceTimeBy(32)
        }
    }

    /** The activity's whole window as a PNG. */
    fun shot(scene: String, activity: Activity) {
        settle(4)
        draw(scene, activity.window.decorView)
    }

    /** The window of the dialog or sheet on top: the last of the windows with compose content (false when there is only the page). */
    fun dialogShot(scene: String): Boolean {
        settle(30)
        val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        if (roots.size < 2) return false
        val view = roots.last().root as? View ?: return false
        draw(scene, view)
        return true
    }

    private fun draw(scene: String, view: View) {
        val bitmap = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(dir, "$scene.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** [cutByWindow]: a list has scrolled part of it away, so its bounds are not where its text is. */
    private class TextBox(val text: String, val bounds: Rect, val ellipsized: Boolean, val clipped: Boolean, val lines: Int, val cutByWindow: Boolean)

    /**
     * Checks every window on screen (the page and a dialog over it). [only]: the test tag of one subtree to look
     * at (a side panel over a dimmed page: what is behind it is not looked at). [language]: "en" or "ru", the
     * interface the scene runs in. [allowed]: texts that are data and may be anything (names, versions, brands). [mayCut]: data
     * (a very long name) that may end in «…». [lastWindowOnly]: only the dialog or sheet on top.
     */
    fun report(scene: String, language: String, only: String? = null, allowed: (String) -> Boolean = { false }, mayCut: (String) -> Boolean = { false }, focusExpected: Boolean = false, lastWindowOnly: Boolean = false) {
        settle(4)
        val all = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes()
        val roots = if (lastWindowOnly && all.size > 1) listOf(all.last()) else all
        for (root in roots) {
            val boxes = ArrayList<TextBox>()
            collect(root, boxes, only, inside = only == null)
            checkBoxes(scene, language, root.boundsInRoot, boxes, allowed, mayCut)
        }
        if (focusExpected && compose.onAllNodes(androidx.compose.ui.test.isFocused(), useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) {
            findings += Finding(scene, "no-focus", "", "nothing is focused: the D-pad would have nothing to move from")
        }
    }

    private fun collect(node: SemanticsNode, out: MutableList<TextBox>, only: String?, inside: Boolean) {
        val tag = node.config.getOrElseNullable(SemanticsProperties.TestTag) { null }
        val here = inside || (only != null && tag == only)
        val texts = node.config.getOrElseNullable(SemanticsProperties.Text) { null }
        if (here && texts != null) {
            val results = ArrayList<TextLayoutResult>()
            node.config.getOrElseNullable(SemanticsActions.GetTextLayoutResult) { null }?.action?.invoke(results)
            val layout = results.firstOrNull()
            val ellipsized = layout != null && (0 until layout.lineCount).any { layout.isLineEllipsized(it) }
            // Cut by the line limit with no «…», or longer than its box: what a person sees as a sentence that stops.
            val clipped = layout != null && layout.multiParagraph.didExceedMaxLines && !ellipsized
            val text = texts.joinToString(" ") { it.text }.trim()
            if (text.isNotEmpty() && node.boundsInRoot.width > 0f) {
                val cut = node.boundsInRoot.height < node.size.height - 1 || node.boundsInRoot.width < node.size.width - 1
                out += TextBox(text, node.boundsInRoot, ellipsized, clipped, layout?.lineCount ?: 1, cut)
            }
        }
        node.children.forEach { collect(it, out, only, here) }
    }

    private fun checkBoxes(scene: String, language: String, window: Rect, all: List<TextBox>, allowed: (String) -> Boolean, mayCut: (String) -> Boolean) {
        // A list keeps rows composed a little beyond its edge: those are not on screen.
        val boxes = all.filter { it.bounds.bottom > window.top && it.bounds.top < window.bottom }
        for (box in boxes) {
            if (!mayCut(box.text)) {
                if (box.ellipsized) findings += Finding(scene, "ellipsis", box.text, "${box.lines} line(s)")
                if (box.clipped) findings += Finding(scene, "clipped", box.text)
            }
            if (allowed(box.text)) continue
            if (box.bounds.left < window.left - 1f || box.bounds.right > window.right + 1f) {
                findings += Finding(scene, "outside", box.text, "x ${box.bounds.left.toInt()}..${box.bounds.right.toInt()} of ${window.width.toInt()}")
            }
            if (RAW_KEY.matches(box.text)) findings += Finding(scene, "raw-key", box.text)
            val cyrillic = CYRILLIC.containsMatchIn(box.text)
            if (language == "en" && cyrillic) findings += Finding(scene, "cyrillic-in-en", box.text)
            if (language == "ru" && !cyrillic && box.text.count { it == ' ' } >= 1 && box.text.any { it.isLetter() }) {
                findings += Finding(scene, "latin-in-ru", box.text)
            }
        }
        // Only rows wholly on screen: the bounds of one the list has scrolled half away are the clipped part.
        val whole = boxes.filter { !it.cutByWindow && it.bounds.top >= window.top && it.bounds.bottom <= window.bottom }
        for (i in whole.indices) for (j in i + 1 until whole.size) {
            val a = whole[i].bounds
            val b = whole[j].bounds
            val overlap = a.intersect(b)
            if (overlap.width > 3f && overlap.height > 3f) {
                findings += Finding(scene, "overlap", "${whole[i].text} | ${whole[j].text}", "${overlap.width.toInt()}x${overlap.height.toInt()} px")
            }
        }
    }

    /** Writes [findings] as `findings.tsv` beside the shots; returns them as one line each. */
    fun write(name: String = "findings"): String {
        val text = findings.joinToString("\n") + if (findings.isEmpty()) "" else "\n"
        File(dir, "$name.tsv").writeText(text)
        File(dir, "windows.txt").writeText(notes.joinToString("\n") + "\n")
        return text
    }

    private companion object {
        val RAW_KEY = Regex("^[a-z][a-z0-9]*(_[a-z0-9]+)+$")
        val CYRILLIC = Regex("[\\u0400-\\u04FF]")
    }
}

/** True when the audit runs only to report (the findings are written, the test does not fail on them). */
fun auditReportOnly(): Boolean = !System.getenv("SETTINGS_AUDIT_REPORT").isNullOrEmpty()
