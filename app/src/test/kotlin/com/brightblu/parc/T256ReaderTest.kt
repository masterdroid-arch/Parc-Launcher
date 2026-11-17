package com.brightblu.parc

import com.brightblu.parc.reader.DefineHandler
import com.brightblu.parc.reader.ReaderDefineRules
import com.brightblu.parc.reader.ReaderTranslateVariant
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** T256: Define and Translate on a selected word in the reader. */
class T256ReaderTest {

    private fun handler(label: String, packageName: String = "com.example.app"): DefineHandler =
        DefineHandler(packageName, "$packageName.Main", label,
            ReaderDefineRules.looksLikeDictionary(label, packageName))

    @Test
    fun noProcessTextAppMeansNoDictionaryAndAQuietMessage() {
        val plan = ReaderDefineRules.plan(emptyList())
        assertFalse(plan.hasApp)
        assertTrue(plan.chosen.isEmpty())
        assertFalse(plan.wantsChooser)
        assertTrue(ReaderDefineRules.NoAppMessage.startsWith("No dictionary app found"))
    }

    @Test
    fun aDictionaryLookingAppIsChosenBeforeOtherProcessTextApps() {
        val notes = handler("Notes")
        val dictionary = handler("Dictionary", "com.example.dictionary")
        val plan = ReaderDefineRules.plan(listOf(notes, dictionary))
        assertTrue(plan.hasApp)
        assertEquals(listOf(dictionary), plan.chosen)
        assertFalse(plan.wantsChooser)
    }

    @Test
    fun severalCandidatesAskTheSystemChooserAndUnknownLabelsFallBackToAll() {
        val first = handler("Define")
        val second = handler("Dicionário", "com.example.dicionario")
        val dictionaryPlan = ReaderDefineRules.plan(listOf(first, second))
        assertEquals(listOf(first, second), dictionaryPlan.chosen)
        assertTrue(dictionaryPlan.wantsChooser)

        val notes = handler("Notes")
        val translate = handler("Translate")
        val fallback = ReaderDefineRules.plan(listOf(notes, translate))
        assertEquals(listOf(notes, translate), fallback.chosen)
        assertTrue(fallback.wantsChooser)
    }

    @Test
    fun dictionaryLabelsAndPackagesAreRecognisedWithoutOnlineLookup() {
        assertTrue(ReaderDefineRules.looksLikeDictionary("Dictionary", "com.example.notes"))
        assertTrue(ReaderDefineRules.looksLikeDictionary("Define word", "com.example.notes"))
        assertTrue(ReaderDefineRules.looksLikeDictionary("Notes", "com.example.dictionary"))
        assertFalse(ReaderDefineRules.looksLikeDictionary("Notes", "com.example.notes"))
        assertFalse(ReaderDefineRules.looksLikeDictionary("Translate", "com.example.translate"))
    }

    @Test
    fun selectionMenuOffersDefineAndTranslateAndTheManifestCanSeeProcessTextApps() {
        val reader = File("src/main/kotlin/com/brightblu/parc/reader")
        val ui = File(reader, "ReaderMarksUi.kt").readText()
        val screen = File(reader, "ReaderScreen.kt").readText()
        val manifest = File("src/main/AndroidManifest.xml").readText().replace(Regex("\\s+"), " ")

        assertTrue(ui.contains("ReaderBarAction(\"Define\""))
        assertTrue(ui.contains("ReaderBarAction(\"Translate\""))
        assertTrue(ui.contains("translateAvailable"))
        assertTrue(screen.contains("ReaderDefine.open(context, selected.text)"))
        assertTrue(screen.contains("ReaderTranslateVariant.Sheet(text, skin)"))
        assertTrue(manifest.contains("android.intent.action.PROCESS_TEXT"))
        assertTrue(manifest.contains("android:mimeType=\"text/plain\""))
    }

    @Test
    fun translateReusesTheSearchModeAndExistsOnlyInTheInternetBuilds() {
        val internet = BuildConfig.FLAVOR == "personalWeather" || BuildConfig.FLAVOR == "family"
        assertEquals(internet, ReaderTranslateVariant.Available)

        val real = File("src/personalWeather/kotlin/com/brightblu/parc/reader/ReaderTranslateVariant.kt").readText()
        val stub = File("src/noWeather/kotlin/com/brightblu/parc/reader/ReaderTranslateVariant.kt").readText()
        assertTrue(real.contains("TranslateSearchMode(sendAtOnce = true)"))
        assertTrue(stub.contains("const val Available = false"))
    }
}
