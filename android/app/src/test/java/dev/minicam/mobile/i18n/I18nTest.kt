package dev.minicam.mobile.i18n

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** docs/specs/020 AC1/AC2: the app language and the Spanish dictionary. */
class I18nTest {
    @After
    fun tearDown() = I18n.apply(AppLanguage.ENGLISH)

    @Test
    fun tr_englishIsTheTextItself() {
        I18n.apply(AppLanguage.ENGLISH)

        assertEquals("Recordings", tr("Recordings"))
        assertEquals("Closes in 5 s", tr("Closes in %d s", 5))
    }

    @Test
    fun tr_spanishTranslatesAndFillsArguments() {
        I18n.apply(AppLanguage.SPANISH)

        assertEquals("Grabaciones", tr("Recordings"))
        assertEquals("Se cierra en 5 s", tr("Closes in %d s", 5))
        assertEquals("person detectado en door", tr("%1\$s detected on %2\$s", "person", "door"))
    }

    @Test
    fun tr_aTextWithoutTranslationStaysInEnglish() {
        I18n.apply(AppLanguage.SPANISH)

        assertEquals("Not in the dictionary", tr("Not in the dictionary"))
    }

    @Test
    fun fromCode_unknownMeansSystem() {
        assertEquals(AppLanguage.SPANISH, AppLanguage.fromCode("es"))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode(null))
        assertEquals(AppLanguage.SYSTEM, AppLanguage.fromCode("fr"))
    }

    /** Every text the app passes to tr() has a Spanish translation with the same placeholders. */
    @Test
    fun everyTextHasASpanishTranslation() {
        val literal = "\"((?:[^\"\\\\]|\\\\.)*)\""
        val call = Regex("(?<![\\w.])tr\\(\\s*((?:$literal\\s*\\+\\s*)*$literal)")
        val keys = File("src/main/java").walk().filter { it.extension == "kt" }.flatMap { file ->
            call.findAll(file.readText()).map { match ->
                Regex(literal).findAll(match.groupValues[1]).joinToString("") { part ->
                    part.groupValues[1].replace("\\$", "$").replace("\\\"", "\"").replace("\\\\", "\\")
                }
            }
        }.toSet()
        assertTrue("no tr() calls found", keys.size > 200)
        val missing = keys.filter { it !in Spanish.texts }
        assertEquals("texts without Spanish", emptyList<String>(), missing)
        val placeholder = Regex("%(?:\\d\\$)?[sd]")
        keys.forEach { key ->
            assertEquals(
                "placeholders of \"$key\"",
                placeholder.findAll(key).map { it.value }.sorted().toList(),
                placeholder.findAll(Spanish.texts.getValue(key)).map { it.value }.sorted().toList(),
            )
        }
    }
}
