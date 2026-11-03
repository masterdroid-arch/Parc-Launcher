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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.brightblu.parc.feature.settings.skins.SettingsUiState
import com.brightblu.parc.feature.settings.ui.*
import com.brightblu.parc.model.*
import com.brightblu.parc.ring.rasterHome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun SkinGallery(state: SettingsUiState, skin: Skin, services: LauncherServices,
    look: LookSettings, onPreview: (Skin) -> Unit, onEdit: () -> Unit) {
    val cards = remember(state.skins, state.selectedSkinId) { skinCards(state.skins, state.selectedSkinId) }
    val pager = rememberPagerState(initialPage = cards.indexOfFirst { it.selected }.coerceAtLeast(0)) { cards.size }
    LaunchedEffect(state.selectedSkinId) {
        pager.scrollToPage(cards.indexOfFirst { it.selected }.coerceAtLeast(0))
    }
    val cache = remember { mutableMapOf<Skin, ImageBitmap>() }
    val layout by services.layout.layout.collectAsState()
    val liveApps by services.unlockedApps.collectAsState()
    // T130: the gallery preview is the demo home in demo mode, so it needs the sample entries.
    val apps = if (DemoMode.enabled) DemoHomeLayout.appsIfDemo(true) else liveApps
    val revision by services.iconRevision.collectAsState()
    val context = LocalContext.current
    val scene = remember(layout, apps, revision) { skinPreviewScene(services, layout, apps, ParcUiPrefs(context)) }
    // A changed layout, icon set or look invalidates the bounded, picker-local raster cache.
    val appearance = rememberHubAppearance()
    val phoneColour = rememberPhoneWallpaperColour()
    val hubSize = rememberHubSize()
    val statusInset = rememberStatusInsetDp()
    remember(scene, look, appearance, hubSize) { cache.clear(); true }
    val density = LocalDensity.current.density
    val reduceMotion = (rememberCoroutineScope().coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f) == 0f
    HorizontalPager(state = pager, pageSize = PageSize.Fixed(Metrics.settingsCardWidthDp.dp),
        beyondViewportPageCount = cards.size,
        flingBehavior = PagerDefaults.flingBehavior(pager,
            snapAnimationSpec = if (reduceMotion) androidx.compose.animation.core.snap<Float>()
                else spring<Float>(dampingRatio = Metrics.settingsSpringDamping, stiffness = Metrics.settingsSpringStiffness)),
        pageSpacing = Metrics.cardInsetDp.dp,
        contentPadding = PaddingValues(vertical = Metrics.cardInsetDp.dp)) { index ->
            val card = cards[index]
            val shape = RoundedCornerShape(Metrics.menuCornerDp.dp)
            Column(Modifier.width(Metrics.settingsCardWidthDp.dp).clip(shape)
                .background(Color(skin.colors.surface.toInt()))
                .border(skin.shape.surfaceBorderDp.dp, Color((if (card.selected) skin.colors.accent else skin.colors.lineBase).toInt()), shape)
                .clickable { card.skin?.let(onPreview) ?: onEdit() }) {
                Column(Modifier.parcSettingsPadding()) {
                if (card.skin != null) {
                    Box(Modifier.fillMaxWidth().height(Metrics.settingsCardHeightDp.dp).clip(shape)
                        .graphicsLayer {
                            if (!reduceMotion) {
                                translationX = ((pager.currentPage - index) + pager.currentPageOffsetFraction) * Metrics.settingsParallaxDp * density
                            }
                        }) {
                        CachedSkinHome(card.skin, services, scene, look, cache, appearance, phoneColour, hubSize, statusInset)
                        Box(Modifier.fillMaxSize().clickable { onPreview(card.skin) })
                    }
                } else Box(Modifier.fillMaxWidth().height(Metrics.settingsCardHeightDp.dp), contentAlignment = Alignment.Center) {
                    BasicText("+\nMake your own", style = settingsText(skin))
                }
                BasicText((if (card.selected) "✓  " else "") + card.name,
                    style = settingsText(skin, if (card.selected) skin.colors.accent else skin.colors.textPrimary),
                    modifier = Modifier.padding(top = Metrics.rowGapDp.dp))
                }
                if (card.editable) SettingRow("Edit", skin, SettingKind.Action, icon = "✎", divider = false, onClick = onEdit)
            }
    }
    BasicText("Swipe to explore · tap to preview", style = settingsText(skin, skin.colors.textSecondary, true))
}

/** The complete miniature is recorded once; scrolling draws only the cached image. */
@Composable
private fun CachedSkinHome(skin: Skin, services: LauncherServices, scene: RingState, look: LookSettings,
    cache: MutableMap<Skin, ImageBitmap>, appearance: HubAppearance, phoneColour: Int?, hubSize: HubSize, statusInset: Float) {
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
            }) { SkinHome(skin, services, scene, ring!!, look, appearance) }
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
    appearance: HubAppearance = HubAppearance()) {
    val width = services.geometry.widthDp
    val height = services.geometry.heightDp
    val hubSize = rememberHubSize()
    val statusInset = rememberStatusInsetDp()
    val panel = remember(scene, skin, look, hubSize, statusInset) { previewHubPanel(scene, skin, look, statusInset, hubSize) }
    // T173: the stats card shows what Home shows: its colour, opacity and glass. A frosted one is already in the raster.
    val hubs = remember(skin, look, appearance) { HubCards.of(skin, look, appearance) }
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val scale = minOf(maxWidth.value / width, maxHeight.value / height)
        Box(Modifier.layout { measurable, _ ->
            val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed((width * density).toInt(), (height * density).toInt()))
            layout((width * density * scale).toInt(), (height * density * scale).toInt()) {
                placeable.placeWithLayer(0, 0) { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) }
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
                LocalHubFrostDrawn provides true) {
                StatsHud(powerSource = services.powerStats, networkRepository = services.networkStats,
                    screenTime = services.screenTime, newApps = services.newApps, appStorage = services.appStorage,
                    volume = services.volume, comms = services.comms, entries = entries, onLaunch = {}, active = false,
                    clock = { ClockHubPage(hubs.stats, look, rememberHubTime(), ClockDetail(), settings.clockVisible) },
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
    statusInsetDp: Float = services.geometry.insets.top, resources: android.content.res.Resources? = null): ImageBitmap {
    val layout = services.layout.layout.value
    val locked = layout.folders.filter { it.locked }.map { it.id }.toSet()
    val previewApps = if (DemoMode.enabled) DemoHomeLayout.appsIfDemo(true) else services.unlockedApps.value
    val previews = FolderPreviews.prepare(layout, previewApps, services.usage, skin, locked) { id ->
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
        resources?.getFont(com.brightblu.parc.core.model.R.font.figtree_variable), resources?.getFont(com.brightblu.parc.core.model.R.font.doto_variable)).asImageBitmap()
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
    countsOverride: LayoutCounts? = null, alignedOverride: Boolean? = null): RingState {
    val counts = countsOverride ?: prefs.layoutCounts()
    val base = services.geometry
    val geometry = com.brightblu.parc.ring.RingLayout(base.widthDp, base.heightDp, base.insets,
        base.options.copy(arcsShown = counts.arcs, iconsPerArc = counts.icons,
            alignAcrossArcs = alignedOverride ?: prefs.alignAcrossArcs()))
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
