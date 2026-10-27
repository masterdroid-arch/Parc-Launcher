package com.brightblu.parc

import com.brightblu.parc.core.data.focus.FocusDay
import com.brightblu.parc.core.data.focus.FocusIconTreatment
import com.brightblu.parc.core.data.focus.FocusMode
import com.brightblu.parc.core.data.focus.FocusRepository
import com.brightblu.parc.core.data.focus.FocusSchedule

/**
 * T123: the focus-mode sample shown only while demo mode is on.
 *
 * The whole fixture is the one hard-coded list below. No value is read from the device, a contact
 * or the author's real modes. [install] is called from `LauncherServices` only when [DemoMode.enabled]
 * is true; in demo that process runs on T120b's namespaced context, so the writes land in
 * `files/demo/datastore/parc_focus.preferences_pb`, never in Parc's real focus store.
 */
internal object DemoFocusModes {

    /** The sample mode that starts active, so the screen always has one active row. */
    const val ActiveName = "Work"

    /** Names in the order the Focus page shows them; derived so there is only one fixture list. */
    val names: List<String> get() = sample.map { it.name }

    private val weekdays = FocusDay.entries.take(5).toSet()
    private val weekend = setOf(FocusDay.Sat, FocusDay.Sun)

    /**
     * Three modes with generic placeholder apps and a schedule each. Packages use the same
     * `parc.demo.*` placeholder space as T122, so they cannot name a real app. `syncDnd` is false
     * in every sample: demo mode must never create or turn on a real Do Not Disturb rule.
     */
    val sample: List<FocusMode> = listOf(
        FocusMode(
            name = "Work",
            glyph = "▣",
            hiddenPackages = setOf("parc.demo.chat", "parc.demo.feed", "parc.demo.video"),
            silenceBadges = true,
            schedule = FocusSchedule(weekdays, 9 * 60, 17 * 60),
            priority = 3,
        ),
        FocusMode(
            name = "Sleep",
            glyph = "☾",
            hiddenPackages = setOf("parc.demo.chat", "parc.demo.feed"),
            silenceBadges = true,
            iconTreatment = FocusIconTreatment.Dimmed,
            schedule = FocusSchedule(FocusDay.entries.toSet(), 22 * 60, 7 * 60),
            priority = 2,
        ),
        FocusMode(
            name = "Reading",
            glyph = "◇",
            hiddenPackages = setOf("parc.demo.video", "parc.demo.radio", "parc.demo.solitaire"),
            schedule = FocusSchedule(weekend, 10 * 60, 12 * 60),
            priority = 1,
        ),
    )

    /** The sample modes, or an empty list when demo is off. */
    fun modesIfDemo(demo: Boolean): List<FocusMode> = if (demo) sample else emptyList()

    /** The mode the sample starts manually, or null when demo is off. */
    fun activeNameIfDemo(demo: Boolean): String? = if (demo) ActiveName else null

    /**
     * Writes the sample through [sink] only when [demo] is true. Returns false without touching the
     * sink when demo is off, so the real focus path is exactly the one built before this task.
     */
    suspend fun install(demo: Boolean, sink: FocusSampleSink): Boolean {
        if (!demo) return false
        sink.install(sample, ActiveName)
        return true
    }
}

/** The one write boundary the fixture needs; [RepositoryFocusSampleSink] is the production one. */
internal fun interface FocusSampleSink {
    suspend fun install(modes: List<FocusMode>, activeName: String)
}

/**
 * Production sink. In demo mode the repository was created from T120b's wrapped context, so its
 * DataStore file is the demo one. The active sample is started through the existing manual
 * override, not a second focus state.
 */
internal class RepositoryFocusSampleSink(
    private val repository: FocusRepository,
) : FocusSampleSink {

    override suspend fun install(modes: List<FocusMode>, activeName: String) {
        modes.forEach { repository.save(it) }
        repository.quickStart(activeName, minutes = null)
    }
}
