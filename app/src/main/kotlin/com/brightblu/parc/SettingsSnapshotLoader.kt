package com.brightblu.parc

import android.content.Context
import com.brightblu.parc.model.ArcFeel
import com.brightblu.parc.model.HubAppearance
import com.brightblu.parc.model.LookSettings
import com.brightblu.parc.ring.FeelPrefs
import kotlinx.coroutines.flow.first

/**
 * T180: the stored settings as one [SettingsSnapshot], read straight from the stores (no live Home). Used by the debug
 * audit to change one registry setting through the same [SettingsWriter] the Settings pages use.
 */
internal object SettingsSnapshotLoader {
    suspend fun load(context: Context, services: LauncherServices): Pair<SettingsSnapshot, SettingsWriter> {
        val ui = ParcUiPrefs(context)
        val lookStore = ParcLookStore(context)
        val hubAppearance = HubAppearanceStore(context)
        val hubStore = HubConfigStore(context)
        val search = com.brightblu.parc.core.data.search.SearchPrefs.create(context)
        val feel = FeelStore(context)
        val settings = services.prefs.settings.first()
        val searchSnapshot = search.snapshot.first()
        val commsEnabled = services.comms.enabled.value
        val commsRecordHistory = services.comms.keepHistory.value
        val commsStored = services.comms.storedMessages.value
        val hubConfig = hubStore.config.first()
        val gestures = GestureOptionsStore(context).config.first()
        val skins = services.skins
        val snapshot = SettingsSnapshot(
            skins = skins, skinId = skins.firstOrNull { it.id == settings.skinId }?.id ?: skins.first().id,
            look = runCatching { lookStore.look.first() }.getOrDefault(LookSettings()),
            hub = runCatching { hubAppearance.appearance.first() }.getOrDefault(HubAppearance()),
            counts = ui.layoutCounts(), aligned = ui.alignAcrossArcs(), joystick = ui.joystickAim(), hubDoubleTap = ui.hubDoubleTap(),
            gestures = gestures, haptics = settings.haptics, feel = runCatching { feel.prefs.first() }.getOrDefault(FeelPrefs()),
            arcFeel = runCatching { feel.arcFeel.first() }.getOrDefault(ArcFeel()), clockVisible = settings.clockVisible,
            iconPack = services.appIndex.iconPacks?.selection?.value, statsVisible = settings.statsVisible, hubSize = hubConfig.size,
            searchPhoneSettings = searchSnapshot.phoneSettingsEnabled,
            searchFiles = searchSnapshot.filesEnabled, searchEvents = searchSnapshot.eventsEnabled,
            badges = ui.notificationBadges(), badgesInAllApps = ui.badgesInAllApps(), menuNotifications = ui.menuNotifications(),
            menuNotificationText = ui.menuNotificationText(), markWorkApps = ui.markWorkApps(),
            layoutLocked = ui.layoutLocked(), leftHand = ui.leftHand(),
            wallpaperSkinSupported = services.wallpaperSkin.supported, wallpaperMode = settings.wallpaperMode,
            wallpaperLook = settings.wallpaperLook,
            commsEnabled = commsEnabled, commsRecordHistory = commsRecordHistory,
            commsStoredCount = commsStored.size, commsStorageLine = com.brightblu.parc.CommsExtrasPresentation.storageLine(
                commsStored.size, com.brightblu.parc.CommsExtrasPresentation.storageBytes(commsStored)),
        )
        val writer = SettingsWriter(look = { lookStore.update(it) }, hub = { hubAppearance.update(it) }, prefs = services.prefs,
            ui = ui, hubStore = hubStore, search = search, feel = feel, iconPacks = services.appIndex.iconPacks,
            layout = services.layout, comms = services.comms)
        return snapshot to writer
    }
}
