package com.borodutch.spinwell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every language has every string and plural, with the same arguments as English and the plural forms its language needs. */
class LocalizationTest {
    private val folders = listOf(
        "ar", "de", "es", "fr", "hi", "in", "it", "ja", "ko", "nl", "pl", "pt", "pt-rPT", "ru", "sv", "th", "tr", "uk", "vi", "b+zh+Hans", "b+zh+Hant",
    )
    private val forms = mapOf(
        "ar" to setOf("zero", "one", "two", "few", "many", "other"), "es" to setOf("one", "many", "other"), "fr" to setOf("one", "many", "other"),
        "it" to setOf("one", "many", "other"), "pt" to setOf("one", "many", "other"), "pt-rPT" to setOf("one", "many", "other"),
        "pl" to setOf("one", "few", "many", "other"), "ru" to setOf("one", "few", "many", "other"), "uk" to setOf("one", "few", "many", "other"),
        "de" to setOf("one", "other"), "nl" to setOf("one", "other"), "sv" to setOf("one", "other"), "hi" to setOf("one", "other"), "tr" to setOf("one", "other"),
        "in" to setOf("other"), "ja" to setOf("other"), "ko" to setOf("other"), "th" to setOf("other"), "vi" to setOf("other"),
        "b+zh+Hans" to setOf("other"), "b+zh+Hant" to setOf("other"),
    )
    private val arg = Regex("""%(?:(\d+)\$)?[sd]""")

    private class Res(val strings: Map<String, String>, val plurals: Map<String, Map<String, String>>)

    private fun load(folder: String): Res {
        val xml = File("src/main/res/$folder/strings.xml").readText()
        val strings = Regex("""<string name="([^"]+)"([^>]*)>(.*?)</string>""").findAll(xml)
            .filter { "translatable=\"false\"" !in it.groupValues[2] || folder == "values" }.associate { it.groupValues[1] to it.groupValues[3] }
        val plurals = Regex("""<plurals name="([^"]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { p ->
            p.groupValues[1] to Regex("""<item quantity="(\w+)">(.*?)</item>""").findAll(p.groupValues[2]).associate { it.groupValues[1] to it.groupValues[2] }
        }
        return Res(strings, plurals)
    }

    private fun args(text: String) = arg.findAll(text).map { it.value.last() }.sorted().toList()

    @Test fun everyLanguageHasEveryString() {
        val en = load("values")
        assertTrue(en.strings.size > 100)
        val fixed = Regex("""<string name="([^"]+)"[^>]*translatable="false"""").findAll(File("src/main/res/values/strings.xml").readText()).map { it.groupValues[1] }.toSet()
        for (f in folders) {
            val l = load("values-$f")
            assertEquals("$f strings", en.strings.keys - fixed, l.strings.keys)
            assertEquals("$f plurals", en.plurals.keys, l.plurals.keys)
            for ((k, v) in l.strings) {
                assertEquals("$f $k arguments", args(en.strings.getValue(k)), args(v))
                if (args(v).size > 1) assertTrue("$f $k needs positional arguments", arg.findAll(v).all { it.groupValues[1].isNotEmpty() })
            }
            for ((k, v) in l.plurals) {
                assertEquals("$f $k forms", forms.getValue(f), v.keys)
                assertEquals("$f $k arguments", args(en.plurals.getValue(k).getValue("other")), args(v.getValue("other")))
            }
            val same = en.strings.keys.count { it in l.strings && l.strings[it] == en.strings[it] }
            assertTrue("$f: $same of ${en.strings.size} strings are still English", same < en.strings.size / 8)
        }
    }
}
