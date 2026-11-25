package app.antresol.fixtures

import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The interface text of the apps lives in the string catalogs, in English (`values/`) and Russian
 * (`values-ru/`), and nowhere else. Runs for the module under test and `:ui-common` (compiled into both
 * apps' unit tests; the working directory is the module's):
 * - every translatable string, plural and array of the English catalog is in the Russian one, and the Russian
 *   catalog has nothing of its own;
 * - nothing is empty, and both languages take the same placeholders (`%1$s`, `%d`);
 * - the English catalog has no Cyrillic and the Russian one no copy of an English sentence;
 * - no screen puts a literal on screen (`Text("…")`, `contentDescription = "…"`): it would be one language
 *   for everybody and nobody would translate it.
 */
class StringCatalogTest {
    private val modules = listOf(File("."), File("../ui-common"))

    private class Entry(val kind: String, val values: List<String>, val translatable: Boolean)

    private fun catalog(module: File, folder: String): Map<String, Entry> {
        val file = File(module, "src/main/res/$folder/strings.xml")
        if (!file.exists()) return emptyMap()
        val root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).documentElement
        val out = LinkedHashMap<String, Entry>()
        val nodes = root.childNodes
        for (i in 0 until nodes.length) {
            val node = nodes.item(i) as? Element ?: continue
            val name = node.getAttribute("name")
            val translatable = node.getAttribute("translatable") != "false"
            val values = when (node.tagName) {
                "string" -> listOf(node.textContent)
                "plurals", "string-array" -> {
                    val items = node.getElementsByTagName("item")
                    (0 until items.length).map { (items.item(it) as Element).let { item -> item.getAttribute("quantity") + "|" + item.textContent } }
                }
                else -> continue
            }
            out[name] = Entry(node.tagName, values, translatable)
        }
        return out
    }

    private fun placeholders(text: String): List<String> =
        PLACEHOLDER.findAll(text.replace("%%", "")).map { it.value.replace(Regex("^%\\d+\\$"), "%") }.sorted().toList()

    @Test
    fun everyStringIsInBothLanguagesAndSaysTheSameThing() {
        val problems = ArrayList<String>()
        for (module in modules) {
            val tag = module.canonicalFile.name
            val en = catalog(module, "values")
            val ru = catalog(module, "values-ru")
            for ((name, entry) in en) {
                if (!entry.translatable) continue
                val other = ru[name]
                if (other == null) {
                    problems += "[$tag] «$name» has no Russian text"
                    continue
                }
                if (other.kind != entry.kind) problems += "[$tag] «$name» is a ${entry.kind} in English and a ${other.kind} in Russian"
                if (other.values.size != entry.values.size && entry.kind == "string-array") problems += "[$tag] «$name» has ${entry.values.size} items in English, ${other.values.size} in Russian"
                for (text in entry.values) if (text.substringAfter('|').isBlank()) problems += "[$tag] «$name» is empty in English"
                for (text in other.values) if (text.substringAfter('|').isBlank()) problems += "[$tag] «$name» is empty in Russian"
                if (entry.kind == "string") {
                    val a = placeholders(entry.values.single())
                    val b = placeholders(other.values.single())
                    if (a != b) problems += "[$tag] «$name» takes $a in English and $b in Russian"
                    val english = entry.values.single()
                    val russian = other.values.single()
                    if (CYRILLIC.containsMatchIn(english)) problems += "[$tag] «$name» has Cyrillic in English: $english"
                    // A sentence with no Cyrillic is an English copy (a format such as «S%1$d · E%2$d» has no word).
                    if (!CYRILLIC.containsMatchIn(russian) && russian.trim().contains(' ') && WORD.containsMatchIn(russian.replace(PLACEHOLDER, ""))) {
                        problems += "[$tag] «$name» looks untranslated in Russian: $russian"
                    }
                }
            }
            for ((name, entry) in ru) {
                if (name !in en) problems += "[$tag] «$name» is only in the Russian catalog"
                else if (!en.getValue(name).translatable && entry.translatable) problems += "[$tag] «$name» is not translatable in English but has a Russian text"
            }
        }
        assertTrue("String catalogs:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    @Test
    fun noScreenPutsALiteralOnScreen() {
        val problems = ArrayList<String>()
        for (module in modules) {
            File(module, "src/main/kotlin").walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
                file.readLines().forEachIndexed { index, line ->
                    if (line.trimStart().startsWith("//") || line.trimStart().startsWith("*")) return@forEachIndexed
                    for (match in LITERAL.findAll(line)) {
                        val text = match.groupValues[2]
                        // A name, a tag or a symbol is not a sentence; a literal with a letter is.
                        if (text.any { it.isLetter() } && text.replace(Regex("\\$\\{[^}]*}|\\$\\w+"), "").any { it.isLetter() }) {
                            problems += "${file.path}:${index + 1}: ${match.value.trim()}"
                        }
                    }
                }
            }
        }
        assertTrue("Literal text on screen (use a string resource in both languages):\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private companion object {
        val PLACEHOLDER = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[sdfxXeEgGcb]")
        val CYRILLIC = Regex("[\\u0400-\\u04FF]")
        val WORD = Regex("\\p{L}{3,}")

        /** `Text("…")`, `text = "…"`, `contentDescription = "…"`, `placeholder = "…"`, `title = "…"` (animation labels and tags are not these). */
        val LITERAL = Regex("""(\bText\(\s*(?:text\s*=\s*)?|\bcontentDescription\s*=\s*|\bplaceholder\s*=\s*|\bsubmitLabel\s*=\s*|\bnote\s*=\s*|\bdetail\s*=\s*)"((?:[^"\\]|\\.)*)"""")
    }
}
