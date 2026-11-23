package com.brightblu.parc

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.spring
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.skins.SettingsUiState
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.rasterHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * T374 (mockup 11): the skin page. One live Home sample shows the skin you have picked but not applied; the tiles pick,
 * the button names what it applies, and Back or Cancel leaves the saved skin alone. Tapping the sample opens the full-screen
 * preview that was there before. The bar stays below the list so Apply is always reachable.
 */
@Composable
internal fun SkinPicker(title: String, state: SettingsUiState, skin: Skin, glassSkin: Skin, activeSkin: Skin,
    services: LauncherServices, look: LookSettings, counts: LayoutCounts, aligned: Boolean, mirrored: Boolean,
    target: String?, onApply: (Skin) -> Unit, onFullPreview: (Skin) -> Unit, onEdit: () -> Unit) {
    val cards = remember(state.skins, state.selectedSkinId) { skinCards(state.skins, state.selectedSkinId).filter { it.skin != null } }
    val applied = cards.firstOrNull { it.selected }?.skin ?: activeSkin
    var pendingId by rememberSaveable { mutableStateOf<String?>(null) }
    val pending = cards.firstOrNull { it.skin?.id == pendingId }?.skin ?: applied
    val status = SkinPickerRules.apply(pending, applied, SkinSafety.readable(pending))
    Column(Modifier.fillMaxSize()) {
        ParcPage(Modifier.weight(1f), safeTop = false, padBottom = false) {
            BasicText(title, style = settingsTitle(skin))
            HomeSample(services, pending, glassSkin, look, HomeSampleFocus.Arcs, counts, aligned, mirrored,
                label = "Preview: ${pending.name}", onClick = { onFullPreview(pending) })
            // The page opens at its top whatever opened it: the sample is the first thing to read, the tiles sit right under it.
            Column {
                val cache = remember { mutableMapOf<Skin, ImageBitmap>() }
                val appearance = rememberHubAppearance()
                val scene = rememberTileScene(services, counts, aligned, mirrored)
                val phoneColour = rememberPhoneWallpaperColour()
                val hubSize = rememberHubSize()
                val statusInset = rememberStatusInsetDp()
                remember(scene, look, appearance, hubSize) { cache.clear(); true }
                val columns = SettingsLayoutRules.tileColumns(LocalDensity.current.fontScale)
                SkinTileSection("Built-in skins", cards.filter { !it.editable }, columns, pending, skin, services, scene, look,
                    cache, appearance, phoneColour, hubSize, statusInset) { pendingId = it.id }
                if (cards.any { it.editable }) SkinTileSection("Your skins", cards.filter { it.editable }, columns, pending, skin,
                    services, scene, look, cache, appearance, phoneColour, hubSize, statusInset) { pendingId = it.id }
            }
            SettingsSection("Customise", skin)
            SettingsCard(skin) { SettingRow("Skin editor", skin, SettingKind.Page, divider = false, onClick = onEdit) }
        }
        Row(Modifier.fillMaxWidth().parcGutter().padding(top = Metrics.itemGapDp.dp)
            .padding(bottom = rememberPageInsets().bottomDp.dp),
            horizontalArrangement = Arrangement.spacedBy(Metrics.itemGapDp.dp), verticalAlignment = Alignment.CenterVertically) {
            if (SkinPickerRules.showCancel(pending, applied)) Box(Modifier.heightIn(min = Metrics.settingsApplyButtonMinDp.dp)
                .clip(RoundedCornerShape(percent = 50)).clickable(role = Role.Button) { pendingId = null }
                .padding(horizontal = Metrics.cardInsetDp.dp), contentAlignment = Alignment.Center) {
                BasicText("Cancel", style = settingsText(skin, skin.colors.accent))
            }
            Column(Modifier.weight(1f)) {
                Box(Modifier.fillMaxWidth().heightIn(min = Metrics.settingsApplyButtonMinDp.dp)
                    .clip(RoundedCornerShape(percent = 50))
                    .background(Color((if (status.enabled) Skin.ParcOrange else skin.colors.surface).toInt()))
                    .border(Dp.Hairline, Color((if (status.enabled) Skin.ParcOrange else skin.colors.lineActive).toInt()), RoundedCornerShape(percent = 50))
                    .clickable(enabled = status.enabled, role = Role.Button) { onApply(pending); pendingId = null }
                    .padding(horizontal = Metrics.cardInsetDp.dp, vertical = Metrics.itemGapDp.dp), contentAlignment = Alignment.Center) {
                    BasicText(status.label, maxLines = 2, style = settingsText(skin, if (status.enabled) Skin.Midnight.colors.bg else skin.colors.textSecondary)
                        .copy(fontWeight = FontWeight.Medium, textAlign = TextAlign.Center))
                }
                status.note?.let { BasicText(it, style = settingsText(skin, skin.colors.textSecondary, true),
                    modifier = Modifier.padding(top = Metrics.lineGapDp.dp)) }
            }
        }
    }
}

/** The tiles of one section, [columns] across; a short last row keeps its tiles the same width. */
@Composable
private fun SkinTileSection(heading: String, cards: List<SkinCardModel>, columns: Int, pending: Skin, skin: Skin,
    services: LauncherServices, scene: RingState, look: LookSettings, cache: MutableMap<Skin, ImageBitmap>,
    appearance: HubAppearance, phoneColour: Int?, hubSize: HubSize, statusInset: Float, onPick: (Skin) -> Unit) {
    SettingsSection(heading, skin)
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.settingsTileGapDp.dp)) {
        tileRows(cards, columns).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Metrics.settingsTileGapDp.dp)) {
                row.forEach { card ->
                    val tileSkin = card.skin ?: return@forEach
                    val picked = tileSkin.id == pending.id
                    val shape = RoundedCornerShape(Metrics.settingsSkinTileCornerDp.dp)
                    Column(Modifier.weight(1f).clip(shape).background(Color(skin.colors.moduleSurface.toInt()).copy(alpha = 1f))
                        .border(if (picked) Metrics.settingsSelectedStrokeDp.dp else Dp.Hairline,
                            Color((if (picked) Skin.ParcOrange else skin.colors.lineBase).toInt()), shape)
                        .selectable(picked, role = Role.RadioButton) { onPick(tileSkin) }
                        .semantics { contentDescription = tileSkin.name; stateDescription = if (card.selected) "In use" else if (picked) "Picked" else "" },
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.fillMaxWidth().height(Metrics.settingsSkinTileImageHeightDp.dp)) {
                            CachedSkinHome(tileSkin, services, scene, look, cache, appearance, phoneColour, hubSize, statusInset, HomeCrop.Arcs)
                        }
                        BasicText(tileSkin.name, style = settingsText(skin, if (picked) skin.colors.readableAccent else skin.colors.textPrimary)
                            .copy(fontWeight = FontWeight.Medium, textAlign = TextAlign.Center), maxLines = 2,
                            modifier = Modifier.padding(horizontal = Metrics.itemGapDp.dp, vertical = Metrics.itemGapDp.dp))
                        if (card.selected) BasicText("In use", style = settingsText(skin, skin.colors.textSecondary, true),
                            modifier = Modifier.padding(bottom = Metrics.itemGapDp.dp))
                    }
                }
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/** The tile pictures use the arc count, alignment and side Home uses now. */
@Composable
private fun rememberTileScene(services: LauncherServices, counts: LayoutCounts, aligned: Boolean, mirrored: Boolean): RingState {
    val context = LocalContext.current
    val layout by services.layout.layout.collectAsState()
    val liveApps by services.unlockedApps.collectAsState()
    val apps = if (DemoMode.enabled) DemoHomeLayout.appsIfDemo(true) else liveApps
    val revision by services.iconRevision.collectAsState()
    return remember(layout, apps, revision, counts, aligned, mirrored) {
        skinPreviewScene(services, layout, apps, ParcUiPrefs(context), counts, aligned, mirrored)
    }
}

/** The complete miniature is recorded once; scrolling draws only the cached image. */
@Composable
private fun CachedSkinHome(skin: Skin, services: LauncherServices, scene: RingState, look: LookSettings,
    cache: MutableMap<Skin, ImageBitmap>, appearance: HubAppearance, phoneColour: Int?, hubSize: HubSize, statusInset: Float,
    crop: HomeCrop? = null) {
    var cached by remember(skin, scene, look, appearance) { mutableStateOf(cache[skin]) }
    if (cached != null) Image(cached!!, "${skin.name} home preview", Modifier.fillMaxSize())
    else {
        // Dispose the temporary recording and raw ring bitmap once the cached card is ready.
        val layer = rememberGraphicsLayer()
        val wallpaperFile = WallpaperImageStore(LocalContext.current).file
        val resources = LocalContext.current.resources
        val ring by produceState<ImageBitmap?>(null, skin, scene, look, appearance) {
            value = withContext(Dispatchers.Default) {
                rasterSkinHome(scene, services, skin, look, wallpaperFile, phoneColour, appearance = appearance, hubSize = hubSize, statusInsetDp = statusInset, resources = resources)
            }
        }
        if (ring == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("Preparing home…", style = settingsText(skin, skin.colors.textSecondary, true))
        } else {
            Box(Modifier.fillMaxSize().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }) { SkinHome(skin, services, scene, ring!!, look, appearance, crop) }
            LaunchedEffect(ring) {
                kotlinx.coroutines.delay(skin.motion.durShort.toLong())
                withFrameNanos { }; withFrameNanos { }
                val raster = layer.toImageBitmap()
                cache[skin] = raster; cached = raster
            }
        }
    }
}

/** Uses the production ring raster, panel shell, skin fonts and clock page at home dimensions. */
@Composable
internal fun SkinHome(skin: Skin, services: LauncherServices, scene: RingState, ring: ImageBitmap, look: LookSettings,
    appearance: HubAppearance = HubAppearance(), crop: HomeCrop? = null) {
    val width = services.geometry.widthDp
    val height = services.geometry.heightDp
    val hubSize = rememberHubSize()
    val statusInset = rememberStatusInsetDp()
    val panel = remember(scene, skin, look, hubSize, statusInset) { previewHubPanel(scene, skin, look, statusInset, hubSize) }
    // T173: the stats card shows what Home shows: its colour, opacity and glass. A frosted one is already in the raster.
    val hubs = remember(skin, look, appearance) { HubCards.of(skin, look, appearance) }
    BoxWithConstraints(Modifier.fillMaxSize().clipToBounds(), contentAlignment = Alignment.Center) {
        // T374: a crop fills the pane with one part of Home at a larger scale; no crop fits the whole Home in the pane.
        val rect = crop?.let {
            HomeSampleRules.crop(it, width, height, panel.first, scene.arcs.firstOrNull()?.originXDp ?: width,
                maxWidth.value, maxHeight.value)
        }
        val scale = if (rect != null) maxWidth.value / rect.width else minOf(maxWidth.value / width, maxHeight.value / height)
        Box(Modifier.layout { measurable, constraints ->
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed((width * density).toInt(), (height * density).toInt()))
            if (rect == null) layout((width * density * scale).toInt(), (height * density * scale).toInt()) {
                placeable.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
            } else layout(constraints.maxWidth, constraints.maxHeight) {
                placeable.placeWithLayer((-rect.left * density * scale).toInt(), (-rect.top * density * scale).toInt()) {
                    scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f)
                }
            }
        }.background(Color(skin.colors.bg.toInt()))) {
            Image(ring, null, Modifier.fillMaxSize())
            val settings by services.prefs.settings.collectAsState(initial = com.brightblu.parc.core.data.ParcSettings())
            val focus by services.focus.state.collectAsState()
            val modes by services.focus.modes.collectAsState()
            val frame by services.stats.stats.collectAsState()
            val history by services.stats.history.collectAsState()
            val entries by services.unlockedApps.collectAsState()
            CompositionLocalProvider(LocalHubLook provides look, LocalFocusRepository provides services.focus,
                LocalStepsRepository provides services.steps, LocalRhythmRepository provides services.rhythm,
                LocalLibraryStore provides services.library,
                LocalHubFrostDrawn provides true) {
                StatsHud(powerSource = services.powerStats, networkRepository = services.networkStats,
                    dayLedger = services.dayLedger, newApps = services.newApps, appStorage = services.appStorage,
                    volume = services.volume, comms = services.comms, entries = entries, onLaunch = {}, active = false,
                    alertState = StatsAlertUiState(), onDismissAlert = {}, archive = com.brightblu.parc.core.data.stats.StatsArchive.Empty,
                    presentation = StatsPresentation.of(frame), history = history, skin = hubs.stats,
                    expanded = settings.statsExpanded, onToggleExpanded = {}, focusActiveName = focus.activeName,
                    focusAvailable = modes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.panelGutterDp.dp)
                        .padding(top = panel.first.dp)
                        .height((panel.second - panel.first).dp))
            }
        }
    }
}

@Composable
internal fun SkinFullPreview(candidate: Skin, services: LauncherServices, look: LookSettings,
    onUse: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val layout by services.layout.layout.collectAsState()
    val liveApps by services.unlockedApps.collectAsState()
    // T178: the same apps as the gallery and Home (the sample apps in demo mode), so the full preview shows every icon.
    val apps = if (DemoMode.enabled) DemoHomeLayout.appsIfDemo(true) else liveApps
    val context = LocalContext.current
    val revision by services.iconRevision.collectAsState()
    val scene = remember(layout, apps, revision) { skinPreviewScene(services, layout, apps, ParcUiPrefs(context)) }
    val wallpaperFile = WallpaperImageStore(context).file
    val resources = context.resources
    val appearance = rememberHubAppearance()
    val phoneColour = rememberPhoneWallpaperColour()
    val hubSize = rememberHubSize()
    val statusInset = rememberStatusInsetDp()
    val ring by produceState<ImageBitmap?>(null, candidate, scene, look, appearance, hubSize) {
        value = withContext(Dispatchers.Default) {
            rasterSkinHome(scene, services, candidate, look, wallpaperFile, phoneColour, appearance = appearance, hubSize = hubSize, statusInsetDp = statusInset, resources = resources)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(candidate.colors.bg.toInt()))) {
        ring?.let { SkinHome(candidate, services, scene, it, look, appearance) }
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .parcGutter().padding(bottom = rememberPageInsets().bottomDp.dp)) {
            // OPUS6 (audit F7): the buttons stay readable, and a skin with unreadable text cannot be used.
            val safe = SkinSafety.forSafetyScreens(candidate)
            val usable = SkinSafety.readable(candidate)
            SettingsCard(safe) {
                SettingRow("Use this skin", safe, SettingKind.Action, enabled = usable,
                    description = if (usable) candidate.name else "${candidate.name}: text would be too hard to read",
                    icon = "✓", onClick = onUse)
                SettingRow("Back", safe, SettingKind.Action, icon = "←", onClick = onBack)
            }
        }
    }
}

internal fun rasterSkinHome(scene: RingState, services: LauncherServices, skin: Skin, look: LookSettings, wallpaperFile: java.io.File,
    phoneColour: Int? = null, appearance: HubAppearance = HubAppearance(), hubSize: HubSize = HubSize.Standard,
    statusInsetDp: Float = services.geometry.insets.top, resources: android.content.res.Resources): ImageBitmap {
    val layout = services.layout.layout.value
    val locked = layout.folders.filter { it.locked }.map { it.id }.toSet()
    val previewApps = if (DemoMode.enabled) DemoHomeLayout.appsIfDemo(true) else services.unlockedApps.value
    val wordFace = resources.getFont(com.brightblu.parc.core.model.R.font.figtree_variable)
    val previews = FolderPreviews.prepare(layout, previewApps, services.usage, skin, locked, wordFace = wordFace) { id ->
        if (DemoMode.enabled && DemoHomeLayout.isSampleApp(id)) {
            com.brightblu.parc.core.data.demo.DemoIcons.iconFor(
                demo = true, id = id, sizePx = Metrics.iconBitmapPx, label = DemoHomeLayout.labelFor(id))
        } else services.appIndex.icon(id, Metrics.iconBitmapPx)
    }
    val complete = scene.copy(slots = scene.slots.map { slot ->
        val folder = slot.slot as? Slot.Folder
        if (folder == null) slot else slot.copy(bitmap = previews[folder.id])
    })
    val hubs = HubCards.of(skin, look, appearance)
    // T176/T178: the picture is loaded exactly as Home loads it (dim kept; scrim left out under glass).
    val image = com.brightblu.parc.ring.loadImageWallpaper(wallpaperFile, GlassBackdrop.pictureLook(look, hubs.glass), skin,
        services.geometry.widthDp.toInt(), services.geometry.heightDp.toInt())
    val widthDp = services.geometry.widthDp
    val origin = complete.arcs.firstOrNull()
    val arcCard = origin?.let {
        val r = com.brightblu.parc.ring.module.ModuleCard.rect(it.originXDp, it.originYDp, widthDp)
        com.brightblu.parc.ring.module.HomeCard(hubs.arc, r.left, r.top, r.width, r.height, hubs.refraction)
    }
    val (panelTop, panelBottom) = previewHubPanel(scene, skin, look, statusInsetDp, hubSize)
    // T178: both hub cards are always in the raster, frosted or flat, through the same HubCardPaint as Home, in Home's place.
    val statsCard = com.brightblu.parc.ring.module.HomeCard(hubs.stats,
        Metrics.panelGutterDp, panelTop, widthDp - 2 * Metrics.panelGutterDp, panelBottom - panelTop, hubs.refraction)
    return rasterHome(complete, hubs.arc, widthDp, services.geometry.heightDp, 1f,
        look, image, phoneColour, arcCard, statsCard,
        wordFace, resources.getFont(com.brightblu.parc.core.model.R.font.doto_variable)).asImageBitmap()
}

/** Where the stats card ends in a preview: above the arcs, and (as on Home, T165) never closer than the gap to the arc card. */
internal fun previewHudBottomDp(scene: RingState, skin: Skin, look: LookSettings): Float {
    val arcBottom = com.brightblu.parc.ring.RingHudBounds.bottomDp(scene,
        skin.typography.label.sizeSp + Metrics.labelPaddingVerticalDp * 2, look.iconSizeDp.toFloat())
    val origin = scene.arcs.firstOrNull() ?: return arcBottom
    return HubPanelLayout.bottomDp(arcBottom, origin.originYDp - Metrics.moduleCardAboveOriginDp)
}

/**
 * T178: the stats card's top and bottom (dp) in a preview, by Home's own rule (LauncherHome): below the status bar by the hub
 * gap, as tall as the chosen hub size allows above the arcs.
 */
internal fun previewHubPanel(scene: RingState, skin: Skin, look: LookSettings, statusInsetDp: Float, size: HubSize): Pair<Float, Float> {
    val top = HubPanelLayout.topDp(statusInsetDp)
    return top to top + HubPanelLayout.heightDp(size, previewHudBottomDp(scene, skin, look) - top)
}

/** The status bar height in dp, as Home measures it for the stats card's top (T178). */
@Composable
internal fun rememberStatusInsetDp(): Float {
    val density = androidx.compose.ui.platform.LocalDensity.current
    return androidx.compose.foundation.layout.WindowInsets.statusBars.getTop(density) / density.density
}

/** The saved stats hub size, for the previews. */
@Composable
internal fun rememberHubSize(): HubSize {
    val context = LocalContext.current
    val store = remember(context) { HubConfigStore(context) }
    return store.config.collectAsState(initial = HubConfig()).value.size
}

internal fun skinPreviewScene(services: LauncherServices, layout: ParcLayout, apps: List<AppEntry>, prefs: ParcUiPrefs,
    countsOverride: LayoutCounts? = null, alignedOverride: Boolean? = null, mirroredOverride: Boolean? = null): RingState {
    val counts = countsOverride ?: prefs.layoutCounts()
    val base = services.geometry
    val geometry = com.brightblu.parc.ring.RingLayout(base.widthDp, base.heightDp, base.insets,
        base.options.copy(arcsShown = counts.arcs, iconsPerArc = counts.icons,
            alignAcrossArcs = alignedOverride ?: prefs.alignAcrossArcs(),
            mirrored = mirroredOverride ?: base.options.mirrored))
    return LauncherScenes.build(geometry, layout, apps, ShellState(), "", rotations = prefs.rotations(),
        lockedFolders = layout.folders.filter { it.locked }.map { it.id }.toSet(), hideLockedName = prefs.lockHideName(),
        icon = { id ->
            // T130: the skin preview is part of demo mode too; real ids keep the app index.
            if (DemoMode.enabled && DemoHomeLayout.isSampleApp(id)) {
                com.brightblu.parc.core.data.demo.DemoIcons.iconFor(
                    demo = true, id = id, sizePx = Metrics.iconBitmapPx, label = DemoHomeLayout.labelFor(id))
            } else services.appIndex.icon(id, Metrics.iconBitmapPx)
        })
}
