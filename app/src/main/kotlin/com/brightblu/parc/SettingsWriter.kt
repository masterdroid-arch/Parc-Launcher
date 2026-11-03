package com.brightblu.parc

import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.icons.IconPackRepository
import com.brightblu.parc.core.data.search.SearchPrefs
import com.brightblu.parc.model.HubAppearance
import com.brightblu.parc.model.LayoutCounts
import com.brightblu.parc.model.LookSettings

/**
 * Values that Home also keeps in its own live state; Settings calls these so Home updates at once. The defaults write the
 * stores directly (the debug audit host has no live Home).
 */
internal class LiveSinks(
    val aligned: (Boolean) -> Unit,
    val joystick: (Boolean) -> Unit,
    val hubDoubleTap: (Boolean) -> Unit,
    val badges: (Boolean) -> Unit,
    val badgesInAllApps: (Boolean) -> Unit,
    val markWorkApps: (Boolean) -> Unit,
) {
    companion object {
        fun direct(ui: ParcUiPrefs) = LiveSinks(ui::setAlignAcrossArcs, ui::setJoystickAim, ui::setHubDoubleTap,
            ui::setNotificationBadges, ui::setBadgesInAllApps, ui::setMarkWorkApps)
    }
}

/**
 * T175: persists a changed [SettingsSnapshot] to the stores that own each value. The Settings pages, the debug audit and
 * every registry row write through here, so "the value is saved" is one code path. Only the parts that changed are written,
 * and the look and hub documents are merged field by field so a concurrent change (a new picture) is never overwritten.
 * Store keys are unchanged by this rework, so backups made before it still restore.
 */
internal class SettingsWriter(
    private val look: suspend ((LookSettings) -> LookSettings) -> Unit,
    private val hub: suspend ((HubAppearance) -> HubAppearance) -> Unit,
    private val prefs: ParcPrefs,
    private val ui: ParcUiPrefs,
    private val hubStore: HubConfigStore,
    private val search: SearchPrefs,
    private val feel: FeelStore,
    private val iconPacks: IconPackRepository?,
    private val layout: LayoutStore,
    private val live: LiveSinks = LiveSinks.direct(ui),
) {
    suspend fun persist(old: SettingsSnapshot, new: SettingsSnapshot) {
        if (old.look != new.look) look { mergeLook(it, old.look, new.look) }
        if (old.hub != new.hub) hub { mergeHub(it, old.hub, new.hub) }
        if (old.skinId != new.skinId) prefs.setSkinId(new.skinId)
        if (old.haptics != new.haptics) prefs.setHaptics(new.haptics)
        if (old.clockVisible != new.clockVisible) prefs.setClockVisible(new.clockVisible)
        if (old.statsVisible != new.statsVisible) prefs.setStatsVisible(new.statsVisible)
        if (old.wallpaperMode != new.wallpaperMode) prefs.setWallpaperMode(new.wallpaperMode)
        if (old.wallpaperLook != new.wallpaperLook) prefs.setWallpaperLook(new.wallpaperLook)
        if (old.counts != new.counts) {
            ui.setLayoutCounts(new.counts)
            layout.updateLayout { LayoutCounts.migrate(it, new.counts.arcs) }
        }
        if (old.aligned != new.aligned) live.aligned(new.aligned)
        if (old.joystick != new.joystick) live.joystick(new.joystick)
        if (old.hubDoubleTap != new.hubDoubleTap) live.hubDoubleTap(new.hubDoubleTap)
        if (old.badges != new.badges) live.badges(new.badges)
        if (old.badgesInAllApps != new.badgesInAllApps) live.badgesInAllApps(new.badgesInAllApps)
        if (old.markWorkApps != new.markWorkApps) live.markWorkApps(new.markWorkApps)
        if (old.menuNotifications != new.menuNotifications) {
            ui.setMenuNotifications(new.menuNotifications)
            MenuNotifications.applyEnabled(new.menuNotifications)
        }
        if (old.menuNotificationText != new.menuNotificationText) ui.setMenuNotificationText(new.menuNotificationText)
        if (old.hubSize != new.hubSize) hubStore.update { it.copy(size = new.hubSize) }
        if (old.searchPhoneSettings != new.searchPhoneSettings) search.setPhoneSettingsEnabled(new.searchPhoneSettings)
        if (old.searchFiles != new.searchFiles) search.setFilesEnabled(new.searchFiles)
        if (old.searchEvents != new.searchEvents) search.setEventsEnabled(new.searchEvents)
        if (old.feel != new.feel) feel.update { new.feel }
        if (old.arcFeel != new.arcFeel) feel.updateArcFeel { new.arcFeel }
        if (old.iconPack != new.iconPack) iconPacks?.select(new.iconPack)
    }

    companion object {
        /** Applies to [current] only the look fields that changed from [old] to [new]. Pure. */
        fun mergeLook(current: LookSettings, old: LookSettings, new: LookSettings): LookSettings {
            fun <T> pick(get: (LookSettings) -> T): T = if (get(old) != get(new)) get(new) else get(current)
            return current.copy(
                iconShape = pick { it.iconShape }, iconSizeDp = pick { it.iconSizeDp }, clockStyle = pick { it.clockStyle },
                showDate = pick { it.showDate }, showAlarm = pick { it.showAlarm }, wallpaper = pick { it.wallpaper },
                wallpaperDimPercent = pick { it.wallpaperDimPercent }, wallpaperReadability = pick { it.wallpaperReadability },
                imageReadabilityScrim = pick { it.imageReadabilityScrim }, keepTextReadable = pick { it.keepTextReadable },
                includeWallpaperInBackups = pick { it.includeWallpaperInBackups },
            )
        }

        /** Applies to [current] only the hub fields that changed from [old] to [new]. Pure. */
        fun mergeHub(current: HubAppearance, old: HubAppearance, new: HubAppearance): HubAppearance {
            fun <T> pick(get: (HubAppearance) -> T): T = if (get(old) != get(new)) get(new) else get(current)
            return current.copy(arc = pick { it.arc }, stats = pick { it.stats }, glass = pick { it.glass },
                arcOpacity = pick { it.arcOpacity }, statsOpacity = pick { it.statsOpacity }, refraction = pick { it.refraction })
        }
    }
}
