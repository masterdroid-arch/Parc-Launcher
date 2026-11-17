package com.brightblu.parc

import com.brightblu.parc.core.data.rhythm.RhythmOptions
import com.brightblu.parc.core.data.steps.StepMath
import com.brightblu.parc.core.data.steps.StepOptions
import java.util.Locale

/**
 * T229: the shared page-options layout. Every hub page gets the same quiet order:
 * a header, "This page", its own settings (when it has any), "Tiles" (when it has tiles),
 * and a "More" section. This file is framework-free so the layout can be unit tested.
 */
internal enum class HubOptionSection(val title: String) {
    ThisPage("This page"),
    Clock("Clock"),
    Weather("Weather"),
    Device("Device"),
    Controls("Controls"),
    Commonplace("Commonplace"),
    Today("Today"),
    Steps("Steps"),
    Rhythm("Rhythm"),
    Sky("Place"),
    Year("Countdowns"),
    Quotes("Quotes"),
    Library("Books"),
    Extra("Page settings"),
    Tiles("Tiles"),
    More("More"),
}

/** The quiet one-line destinations at the bottom of every page's options. */
internal enum class HubOptionMoreRow { WhatATapDoes, EditPages, FullSettings }

internal data class HubPageOptionLayout(
    val name: String,
    val summary: String,
    val ownSection: HubOptionSection?,
    val tileCount: Int,
    val showEditPages: Boolean,
) {
    /** Header is not a labelled section; these are the small labels under it, in reading order. */
    val sections: List<HubOptionSection> = buildList {
        add(HubOptionSection.ThisPage)
        ownSection?.let(::add)
        if (tileCount > 0) add(HubOptionSection.Tiles)
        add(HubOptionSection.More)
    }

    val moreRows: List<HubOptionMoreRow> = buildList {
        add(HubOptionMoreRow.WhatATapDoes)
        if (showEditPages) add(HubOptionMoreRow.EditPages)
        add(HubOptionMoreRow.FullSettings)
    }
}

/** Which page-specific section a page gets; [extraPage] is true for the personal build's own pages. */
internal fun hubPageOptionLayout(
    page: String,
    tileCount: Int,
    showEditPages: Boolean,
    extraPage: Boolean = false,
): HubPageOptionLayout {
    val page = HubCatalog.canonicalPage(page)
    val spec = hubPageOptions[page]
    val own = when (page) {
        WeatherVariant.PageId -> HubOptionSection.Weather
        TodayPage.Id -> HubOptionSection.Today
        DevicePage.Id -> HubOptionSection.Device
        ControlsPage.Id -> HubOptionSection.Controls
        CommonplacePage.Id -> HubOptionSection.Commonplace
        StepsPage.Id -> HubOptionSection.Steps
        RhythmPage.Id -> HubOptionSection.Rhythm
        SkyPage.Id -> HubOptionSection.Sky
        YearPage.Id -> HubOptionSection.Year
        LibraryPage.Id -> HubOptionSection.Library
        else -> if (extraPage) HubOptionSection.Extra else null
    }
    return HubPageOptionLayout(
        name = hubPageName(page),
        summary = spec?.shows.orEmpty(),
        ownSection = own,
        tileCount = tileCount,
        showEditPages = showEditPages,
    )
}

/**
 * One number's legal range and step. The UI only asks "can this go further"; the bounds live here so
 * stride, goal and the Rhythm window cannot drift apart from what they save.
 */
internal data class OptionStep(val min: Int, val max: Int, val step: Int) {
    init {
        require(min <= max) { "min must not exceed max" }
        require(step > 0) { "step must be positive" }
    }

    fun clamp(value: Int): Int = value.coerceIn(min, max)
    fun up(value: Int): Int = clamp(value + step)
    fun down(value: Int): Int = clamp(value - step)
    fun canGoDown(value: Int): Boolean = value > min
    fun canGoUp(value: Int): Boolean = value < max
}

/** Numeric ranges and the words they show on the row. */
internal object HubOptionNumbers {
    val Stride = OptionStep(StepOptions.MinStrideCm, StepOptions.MaxStrideCm, StepOptions.StrideStepCm)
    val Goal = OptionStep(StepOptions.MinGoal, StepOptions.MaxGoal, StepOptions.GoalStep)

    fun strideText(strideCm: Int): String = String.format(Locale.ROOT, "%.2f m", strideCm / 100.0)
    fun goalText(steps: Int): String = StepMath.count(steps.toLong()) + " steps"
    fun hourText(hour: Int): String = String.format(Locale.ROOT, "%02d:00", hour)

    /** The stretch window never becomes shorter than [RhythmOptions.MinSpan] hours. */
    fun rhythmStart(endHour: Int): OptionStep =
        OptionStep(0, (endHour - RhythmOptions.MinSpan).coerceAtLeast(0), 1)

    fun rhythmEnd(startHour: Int): OptionStep =
        OptionStep((startHour + RhythmOptions.MinSpan).coerceAtMost(24), 24, 1)
}

/** Stable index/id mapping for a segmented exclusive choice (the hub size today). */
internal object HubOptionChoice {
    fun sizeIndex(size: HubSize): Int = size.ordinal
    fun sizeAt(index: Int): HubSize = HubSize.entries[index.coerceIn(HubSize.entries.indices)]
    fun sizeAfterReload(savedId: String?): HubSize = HubSize.decode(savedId)
}
