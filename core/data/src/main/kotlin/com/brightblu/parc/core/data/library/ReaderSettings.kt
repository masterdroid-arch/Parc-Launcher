package com.brightblu.parc.core.data.library

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.brightblu.parc.model.Metrics
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

@Serializable
enum class ReaderFamily { Serif, Sans, Figtree }

@Serializable
enum class ReaderPageColor { Light, Sepia, Dark, Black }

/** One set of reading choices for every book (T207). All values are clamped on read and write. */
@Serializable
data class ReaderSettings(
    val fontSp: Float = Metrics.readerDefaultFontSp,
    val lineSpacing: Float = Metrics.readerDefaultLineSpacing,
    val marginFraction: Float = Metrics.readerDefaultMarginFraction,
    val family: ReaderFamily = ReaderFamily.Serif,
    val pageColor: ReaderPageColor = ReaderPageColor.Light,
    // T208: PDF-only choices. They stay in the same one-set-for-every-book document, so old
    // settings without these fields read as the calm defaults.
    val pdfLayout: PdfLayout = PdfLayout.Page,
    val pdfInvert: Boolean = false,
    // T255: comfort choices. Still one set for every book. [brightness] 0 means "follow the phone";
    // anything else is the reader's own window brightness, 0.05 to 1.
    val brightness: Float = 0f,
    val night: Boolean = false,
    val volumeKeys: Boolean = false,
    // T257: read-aloud choices. They are part of the same one-set-for-every-book document, so old
    // documents read as the calm defaults and backups keep working without a new key.
    val ttsSpeed: Float = ReaderTtsRules.DefaultSpeed,
    val ttsStopAtChapterEnd: Boolean = false,
) {
    init {
        require(brightness.isFinite() && brightness == 0f || brightness in MinBrightness..1f)
        require(fontSp.isFinite() && fontSp in Metrics.readerMinFontSp..Metrics.readerMaxFontSp)
        require(lineSpacing.isFinite() && lineSpacing in Metrics.readerMinLineSpacing..Metrics.readerMaxLineSpacing)
        require(marginFraction.isFinite() && marginFraction in Metrics.readerMinMarginFraction..Metrics.readerMaxMarginFraction)
        require(ttsSpeed.isFinite() && ttsSpeed in ReaderTtsRules.MinSpeed..ReaderTtsRules.MaxSpeed)
    }

    fun largerText(): ReaderSettings = copy(fontSp = (fontSp + Metrics.readerFontStepSp)
        .quantize(Metrics.readerFontStepSp).coerceIn(Metrics.readerMinFontSp, Metrics.readerMaxFontSp))
    fun smallerText(): ReaderSettings = copy(fontSp = (fontSp - Metrics.readerFontStepSp)
        .quantize(Metrics.readerFontStepSp).coerceIn(Metrics.readerMinFontSp, Metrics.readerMaxFontSp))
    fun moreLineSpacing(): ReaderSettings = copy(lineSpacing = (lineSpacing + Metrics.readerLineStep)
        .quantize(Metrics.readerLineStep).coerceIn(Metrics.readerMinLineSpacing, Metrics.readerMaxLineSpacing))
    fun lessLineSpacing(): ReaderSettings = copy(lineSpacing = (lineSpacing - Metrics.readerLineStep)
        .quantize(Metrics.readerLineStep).coerceIn(Metrics.readerMinLineSpacing, Metrics.readerMaxLineSpacing))
    fun widerMargins(): ReaderSettings = copy(marginFraction = (marginFraction + Metrics.readerMarginStep)
        .quantize(Metrics.readerMarginStep).coerceIn(Metrics.readerMinMarginFraction, Metrics.readerMaxMarginFraction))
    fun narrowerMargins(): ReaderSettings = copy(marginFraction = (marginFraction - Metrics.readerMarginStep)
        .quantize(Metrics.readerMarginStep).coerceIn(Metrics.readerMinMarginFraction, Metrics.readerMaxMarginFraction))

    /** Dragging to the far left hands brightness back to the phone. */
    fun withBrightness(value: Float): ReaderSettings = copy(brightness = when {
        !value.isFinite() || value < MinBrightness -> 0f
        else -> value.coerceAtMost(1f)
    })

    companion object {
        const val MinBrightness = 0.05f
    }

    private fun Float.quantize(step: Float): Float = (this / step).roundToInt() * step
}

object ReaderSettingsJson {
    const val MaxChars = 512
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A lenient shape: values are only validated after clamping, so an old or odd document still reads. */
    @Serializable
    private data class Dto(
        val fontSp: Float = Metrics.readerDefaultFontSp,
        val lineSpacing: Float = Metrics.readerDefaultLineSpacing,
        val marginFraction: Float = Metrics.readerDefaultMarginFraction,
        val family: ReaderFamily = ReaderFamily.Serif,
        val pageColor: ReaderPageColor = ReaderPageColor.Light,
        val pdfLayout: PdfLayout = PdfLayout.Page,
        val pdfInvert: Boolean = false,
        val brightness: Float = 0f,
        val night: Boolean = false,
        val volumeKeys: Boolean = false,
        val ttsSpeed: Float = ReaderTtsRules.DefaultSpeed,
        val ttsStopAtChapterEnd: Boolean = false,
    )

    fun encode(settings: ReaderSettings): String = json.encodeToString(Dto.serializer(), settings.let {
        Dto(it.fontSp, it.lineSpacing, it.marginFraction, it.family, it.pageColor, it.pdfLayout, it.pdfInvert, it.brightness, it.night, it.volumeKeys, it.ttsSpeed, it.ttsStopAtChapterEnd)
    })

    /** Empty, old or garbled documents read as the calm defaults. Values outside the range are clamped. */
    fun decode(text: String?): ReaderSettings {
        if (text.isNullOrBlank() || text.length > MaxChars) return ReaderSettings()
        val raw = runCatching { json.decodeFromString(Dto.serializer(), text) }.getOrNull()
            ?: return ReaderSettings()
        fun finite(value: Float, fallback: Float, min: Float, max: Float): Float =
            if (value.isFinite()) value.coerceIn(min, max) else fallback
        return ReaderSettings(
            fontSp = finite(raw.fontSp, Metrics.readerDefaultFontSp, Metrics.readerMinFontSp, Metrics.readerMaxFontSp),
            lineSpacing = finite(raw.lineSpacing, Metrics.readerDefaultLineSpacing, Metrics.readerMinLineSpacing, Metrics.readerMaxLineSpacing),
            marginFraction = finite(raw.marginFraction, Metrics.readerDefaultMarginFraction,
                Metrics.readerMinMarginFraction, Metrics.readerMaxMarginFraction),
            family = raw.family,
            pageColor = raw.pageColor,
            pdfLayout = raw.pdfLayout,
            pdfInvert = raw.pdfInvert,
            brightness = ReaderSettings().withBrightness(raw.brightness).brightness,
            night = raw.night,
            volumeKeys = raw.volumeKeys,
            ttsSpeed = ReaderTtsRules.nearestSpeed(raw.ttsSpeed),
            ttsStopAtChapterEnd = raw.ttsStopAtChapterEnd,
        )
    }
}

internal val Context.parcReaderStore by preferencesDataStore(
    name = "parc_reader",
    corruptionHandler = com.brightblu.parc.core.data.ParcStoreRescue.handler("parc_reader"),
)

/** The reader's Aa choices live on the phone and travel in the backup under their own small section. */
class ReaderSettingsStore(
    private val store: DataStore<Preferences>,
) {
    constructor(context: Context) : this(context.applicationContext.parcReaderStore)

    val settings: Flow<ReaderSettings> = store.data
        .catch { error -> if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error }
        .map { ReaderSettingsJson.decode(it[Key]) }

    suspend fun update(change: (ReaderSettings) -> ReaderSettings): ReaderSettings {
        var result = ReaderSettings()
        store.edit { prefs ->
            result = change(ReaderSettingsJson.decode(prefs[Key]))
            prefs[Key] = ReaderSettingsJson.encode(result)
        }
        return result
    }

    companion object {
        const val KeyName = "settings_v1"
        private val Key = stringPreferencesKey(KeyName)
    }
}
