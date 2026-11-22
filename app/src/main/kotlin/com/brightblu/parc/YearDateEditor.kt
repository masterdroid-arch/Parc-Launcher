package com.brightblu.parc

import java.time.LocalDate

/**
 * T280: the add and change flow for a countdown, kept free of Compose so the behaviour can be tested.
 *
 * A draft is immutable; every change returns a new draft. [index] is the position in the saved list when
 * changing an existing date, or null when adding a new one. [triedSave] is set when a save was refused, so
 * the dialog can say why instead of doing nothing.
 */
internal data class YearDateDraft(
    val index: Int?,
    val name: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val yearly: Boolean,
    val triedSave: Boolean = false,
) {
    /** The name as it is stored: one line, trimmed, at most [Countdowns.MaxNameLength] characters. */
    val cleanName: String get() = Countdowns.cleanName(name)

    /** The date the three steppers stand for; a day past the month's end is the month's last day. */
    val date: LocalDate get() = Countdowns.pickedDate(year, month, day)

    val canSave: Boolean get() = cleanName.isNotEmpty()
    val isEditing: Boolean get() = index != null
    val title: String get() = if (isEditing) "Change this date" else "Add a date"
    val saveLabel: String get() = if (isEditing) "Save changes" else "Add date"

    fun named(value: String) = copy(name = value.take(Countdowns.MaxNameLength), triedSave = false)
    fun onDay(value: Int) = copy(day = value.coerceIn(1, 31), triedSave = false)
    fun onMonth(value: Int) = copy(month = value.coerceIn(1, 12), triedSave = false)
    fun onYear(value: Int) = copy(year = value, triedSave = false)
    fun withYearly(value: Boolean) = copy(yearly = value, triedSave = false)
    fun refused() = copy(triedSave = true)
}

/** The list a successful save writes, and the position of the date in it. */
internal data class YearDateSaved(val list: List<Countdown>, val index: Int)

internal object YearDateEditor {
    const val NoName = "Give this date a name first."
    const val NoRoom = "That is the most dates you can keep."

    /** A new draft for today; the caller opens only when [canAdd] is true. */
    fun add(today: LocalDate): YearDateDraft =
        YearDateDraft(index = null, name = "", year = today.year, month = today.monthValue, day = today.dayOfMonth, yearly = false)

    /** A draft prefilled from the saved date at [index]. */
    fun edit(index: Int, countdown: Countdown): YearDateDraft =
        YearDateDraft(index = index, name = countdown.name, year = countdown.date.year, month = countdown.date.monthValue,
            day = countdown.date.dayOfMonth, yearly = countdown.yearly)

    fun canAdd(existing: List<Countdown>): Boolean = existing.size < Countdowns.MaxCount

    /** Why Add a date cannot open, or null when it can. */
    fun addBlocked(existing: List<Countdown>): String? = if (canAdd(existing)) null else NoRoom

    /**
     * Applies [draft] to [existing]; null when the name is empty or a new date would pass the limit.
     * A changed date whose position is no longer in the list is added as new, never dropped.
     */
    fun save(draft: YearDateDraft, existing: List<Countdown>): YearDateSaved? {
        if (!draft.canSave) return null
        val made = Countdown(draft.cleanName, draft.date, draft.yearly)
        val index = draft.index
        if (index != null && index in existing.indices) {
            return YearDateSaved(existing.toMutableList().also { it[index] = made }, index)
        }
        if (!canAdd(existing)) return null
        return YearDateSaved(existing + made, existing.size)
    }

    /** The list without the date at [index]. An index that is no longer in the list leaves it unchanged. */
    fun delete(index: Int, existing: List<Countdown>): List<Countdown> =
        if (index !in existing.indices) existing else existing.filterIndexed { i, _ -> i != index }

    /** Why a refused save did nothing; null when [draft] can be saved into [existing] now. */
    fun problem(draft: YearDateDraft, existing: List<Countdown>): String? {
        val index = draft.index
        return when {
            !draft.canSave -> NoName
            index != null && index in existing.indices -> null
            !canAdd(existing) -> NoRoom
            else -> null
        }
    }
}
