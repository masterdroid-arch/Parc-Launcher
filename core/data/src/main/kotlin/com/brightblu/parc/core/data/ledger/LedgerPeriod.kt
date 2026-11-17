package com.brightblu.parc.core.data.ledger

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The period switch shared by the Network page and the Screen time tile (T185). [id] is the stored
 * value; [label] is the short name shown first on the switch.
 */
enum class LedgerPeriod(val id: String, val label: String) {
    Today("today", "Today"),
    Week("7d", "7 days"),
    Month("30d", "30 days"),
    Year("year", "This year"),
    All("all", "All time");

    /** Tapping the label cycles forward and wraps. */
    fun next(): LedgerPeriod = entries[(ordinal + 1) % entries.size]

    companion object {
        val Default = Today

        /** An unknown, empty or newer id reads as [Default]. */
        fun fromId(id: String?): LedgerPeriod = entries.firstOrNull { it.id == id } ?: Default
    }
}

/**
 * What one period adds up to. [from]..[to] is the window actually counted. For [LedgerPeriod.Year]
 * and [LedgerPeriod.All] the window starts no earlier than the first ledger date, because nothing
 * before Parc started recording is "missing", it is simply not recorded ([sinceFirst] is then true).
 * [days] is the length of the window; [networkDays] and [screenDays] count the days in it that have
 * that measurement, so the label can say "x of y days".
 */
data class PeriodSummary(
    val period: LedgerPeriod,
    val from: LocalDate,
    val to: LocalDate,
    val days: Int,
    val wifiBytes: Long,
    val mobileBytes: Long,
    val screenOnMillis: Long,
    val networkDays: Int,
    val screenDays: Int,
    val firstDate: LocalDate?,
    val sinceFirst: Boolean,
) {
    val networkTotalBytes: Long get() = wifiBytes + mobileBytes

    /** The label for the network figures, for example `7 days · 5 of 7 days`. */
    fun networkLabel(): String = PeriodLabels.of(this, networkDays)

    /** The label for the screen figures. */
    fun screenLabel(): String = PeriodLabels.of(this, screenDays)
}

/** Honest period labels. Pure; dates are formatted in English like the rest of the hub. */
object PeriodLabels {
    private val full = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)
    private val short = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    /**
     * `All time · since 12 Aug 2026`, `This year · since 3 Nov` (only when recording began this
     * year), and ` · x of y days` appended when days inside the counted window have no figure.
     * With no data at all the label is just the period name.
     */
    fun of(summary: PeriodSummary, daysWithData: Int): String {
        val base = StringBuilder(summary.period.label)
        val first = summary.firstDate
        when (summary.period) {
            LedgerPeriod.All -> if (first != null) base.append(" · since ").append(first.format(full))
            LedgerPeriod.Year -> if (summary.sinceFirst && first != null) {
                base.append(" · since ").append(first.format(short))
            }
            else -> Unit
        }
        if (summary.days > 1 && daysWithData < summary.days && first != null) {
            base.append(" · ").append(daysWithData).append(" of ").append(summary.days).append(" days")
        }
        return base.toString()
    }
}
