package com.brightblu.parc.feature.search

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.brightblu.parc.model.Metrics
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.textFamily
import androidx.compose.ui.unit.dp

/** Stable ids of the four modes. Apps is the sheet's own behaviour and is not a [SearchMode]. */
object SearchModeIds {
    const val Apps = "apps"
    const val Translate = "translate"
    const val Note = "note"
    const val Calculate = "calculate"
    /** T209: files on shared storage, only in builds and phones with all-files access. */
    const val Files = "files"

    /** Chip order. Ids not listed here (a future mode) go after these, in registration order. */
    val Order = listOf(Apps, Translate, Note, Calculate, Files)
}

/** What a mode may do to the sheet. The sheet supplies it; tests use a fake. */
interface SearchModeHost {
    val context: Context

    /** Replaces the text in the field (for example to clear it after a note is saved). */
    fun setText(text: String)
}

/**
 * T194: one mode of the search sheet besides Apps. The sheet draws the chips, the field and the
 * cross-settling area; a mode only supplies what is below.
 *
 * - [id]: stable lowercase id (see [SearchModeIds]); registering a mode with the id of a
 *   placeholder replaces the placeholder.
 * - [label]: the chip name.
 * - [hint]: the field's placeholder while this mode is active and the field is empty.
 * - [prefix]: typed at the very start of the field in Apps mode, switches to this mode and is removed
 *   from the text. Lower case, ends in one space ("tr "). Null: no prefix.
 * - [multiline]: the field may grow to several lines (Note mode).
 * - [Body]: the result area, drawn above the field. It receives the live [text]. It is composed only
 *   while the mode is active, so any request it makes is cancelled with it.
 * - [onEnter]: the IME action in the field. Return true when it did something.
 */
interface SearchMode {
    val id: String
    val label: String
    val hint: String
    val prefix: String?
    val multiline: Boolean get() = false

    @Composable
    fun Body(text: String, skin: Skin, host: SearchModeHost)

    fun onEnter(text: String, host: SearchModeHost): Boolean
}

/** The body every mode has until its own card lands: a plain line, and Enter does nothing. */
class PlaceholderSearchMode(
    override val id: String,
    override val label: String,
    override val hint: String,
    override val prefix: String?,
) : SearchMode {
    @Composable
    override fun Body(text: String, skin: Skin, host: SearchModeHost) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = Metrics.searchRowHeightDp.dp)
                .padding(horizontal = Metrics.textGutterDp.dp, vertical = Metrics.rowGapDp.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            BasicText(
                "Coming in this version",
                style = TextStyle(
                    fontFamily = skin.textFamily,
                    color = Color(skin.colors.textSecondary.toInt()),
                    fontSize = skin.typography.caption.sizeSp.sp,
                    fontWeight = FontWeight(skin.typography.caption.weight),
                    letterSpacing = skin.typography.caption.trackingSp.sp,
                ),
            )
        }
    }

    override fun onEnter(text: String, host: SearchModeHost): Boolean = false

    companion object {
        fun translate() = PlaceholderSearchMode(SearchModeIds.Translate, "Translate", "Text to translate", "tr ")
        fun note() = PlaceholderSearchMode(SearchModeIds.Note, "Note", "Jot a note", "n ")
        fun calculate() = PlaceholderSearchMode(SearchModeIds.Calculate, "Calculate", "Type a sum", "= ")
    }
}

/** Where the text goes after a prefix has been recognised. */
data class ModePrefixMatch(val modeId: String, val rest: String)

/** Pure rules of the mode set. No Android, no Compose. */
object SearchModes {
    /**
     * The modes of a build, in chip order. Note and Calculate are always there (T195 replaces their
     * bodies by listing real modes in [replacements]); Translate is there only when the build lists it
     * in [replacements] (the internet build does, T196). A replacement with the id of a placeholder
     * wins over the placeholder.
     */
    fun standard(replacements: List<SearchMode> = emptyList()): List<SearchMode> {
        val byId = LinkedHashMap<String, SearchMode>()
        byId[SearchModeIds.Note] = PlaceholderSearchMode.note()
        byId[SearchModeIds.Calculate] = PlaceholderSearchMode.calculate()
        replacements.forEach { byId[it.id] = it }
        return ordered(byId.values.filter { it.id != SearchModeIds.Apps })
    }

    fun ordered(modes: List<SearchMode>): List<SearchMode> =
        modes.withIndex().sortedBy { (i, mode) ->
            val rank = SearchModeIds.Order.indexOf(mode.id)
            (if (rank >= 0) rank else SearchModeIds.Order.size) * 1000 + i
        }.map { it.value }

    /**
     * The mode whose prefix starts [text], if any: the prefix and its space must be the first
     * characters (no leading space), case does not matter. "transit" and "tr" never match "tr ".
     * [rest] is the text after the prefix, untouched.
     */
    fun detectPrefix(text: String, modes: List<SearchMode>): ModePrefixMatch? {
        for (mode in modes) {
            val prefix = mode.prefix?.takeIf { it.isNotEmpty() } ?: continue
            if (text.length >= prefix.length && text.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) {
                return ModePrefixMatch(mode.id, text.substring(prefix.length))
            }
        }
        return null
    }

    /** The mode after Backspace on an empty field: any other mode goes back to Apps. */
    fun afterBackspace(modeId: String, text: String): String =
        if (modeId != SearchModeIds.Apps && text.isEmpty()) SearchModeIds.Apps else modeId
}
