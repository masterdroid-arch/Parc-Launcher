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
    remember(scene, look) { cache.clear(); true }
    val density = LocalDensity.current.density
    val reduceMotion = (rememberCoroutineScope().coroutineContext[androidx.compose.ui.MotionDurationScale]?.scaleFactor ?: 1f) == 0f
    HorizontalPager(state = pager, pageSize = PageSize.Fixed(Metrics.settingsCardWidthDp.dp),
        beyondViewportPageCount = cards.size,
        flingBehavior = PagerDefaults.flingBehavior(pager,
            snapAnimationSpec = if (reduceMotion) androidx.compose.animation.core.snap<Float>()
                else spring<Float>(dampingRatio = Metrics.settingsSpringDamping, stiffness = Metrics.settingsSpringStiffness)),
        pageSpacing = Metrics.gridDp.dp * 4,
        contentPadding = PaddingValues(vertical = Metrics.gridDp.dp * 4)) { index ->
            val card = cards[index]
            val shape = RoundedCornerShape(Metrics.menuCornerDp.dp)
            Column(Modifier.width(Metrics.settingsCardWidthDp.dp).clip(shape)
                .background(Color(skin.colors.surface.toInt()))
                .border(skin.shape.surfaceBorderDp.dp, Color((if (card.selected) skin.colors.accent else skin.colors.lineBase).toInt()), shape)
                .clickable { card.skin?.let(onPreview) ?: onEdit() }
                .padding(Metrics.gridDp.dp * 3)) {
                if (card.skin != null) {
                    Box(Modifier.fillMaxWidth().height(Metrics.settingsCardHeightDp.dp).clip(shape)
                        .graphicsLayer {
                            if (!reduceMotion) {
                                translationX = ((pager.currentPage - index) + pager.currentPageOffsetFraction) * Metrics.settingsParallaxDp * density
                            }
                        }) {
                        CachedSkinHome(card.skin, services, scene, look, cache)
                        Box(Modifier.fillMaxSize().clickable { onPreview(card.skin) })
                    }
                } else Box(Modifier.fillMaxWidth().height(Metrics.settingsCardHeightDp.dp), contentAlignment = Alignment.Center) {
                    BasicText("+\nMake your own", style = settingsText(skin))
                }
                BasicText((if (card.selected) "✓  " else "") + card.name,
                    style = settingsText(skin, if (card.selected) skin.colors.accent else skin.colors.textPrimary),
                    modifier = Modifier.padding(top = Metrics.gridDp.dp * 3))
                if (card.editable) SettingRow("Edit", skin, SettingKind.Action, icon = "✎", onClick = onEdit)
            }
    }
    BasicText("Swipe to explore · tap to preview", style = settingsText(skin, skin.colors.textSecondary, true))
}

/** The complete miniature is recorded once; scrolling draws only the cached image. */
@Composable
private fun CachedSkinHome(skin: Skin, services: LauncherServices, scene: RingState, look: LookSettings,
    cache: MutableMap<Skin, ImageBitmap>) {
    var cached by remember(skin, scene, look) { mutableStateOf(cache[skin]) }
    if (cached != null) Image(cached!!, "${skin.name} home preview", Modifier.fillMaxSize())
    else {
        // Dispose the temporary recording and raw ring bitmap once the cached card is ready.
        val layer = rememberGraphicsLayer()
        val wallpaperFile = WallpaperImageStore(LocalContext.current).file
        val ring by produceState<ImageBitmap?>(null, skin, scene, look) {
            value = withContext(Dispatchers.Default) {
                rasterSkinHome(scene, services, skin, look, wallpaperFile)
            }
        }
        if (ring == null) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            BasicText("Preparing home…", style = settingsText(skin, skin.colors.textSecondary, true))
        } else {
            Box(Modifier.fillMaxSize().drawWithContent {
                layer.record { this@drawWithContent.drawContent() }
                drawLayer(layer)
            }) { SkinHome(skin, services, scene, ring!!, look) }
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
internal fun SkinHome(skin: Skin, services: LauncherServices, scene: RingState, ring: ImageBitmap, look: LookSettings) {
    val width = services.geometry.widthDp
    val height = services.geometry.heightDp
    val bottom = remember(scene, skin, look) { com.brightblu.parc.ring.RingHudBounds.bottomDp(scene,
        skin.typography.label.sizeSp + Metrics.labelPaddingVerticalDp * 2, look.iconSizeDp.toFloat()) }
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
            CompositionLocalProvider(LocalHubLook provides look, LocalFocusRepository provides services.focus) {
                StatsHud(powerSource = services.powerStats, networkRepository = services.networkStats,
                    screenTime = services.screenTime, newApps = services.newApps, appStorage = services.appStorage,
                    volume = services.volume, comms = services.comms, entries = entries, onLaunch = {}, active = false,
                    clock = { ClockHubPage(skin, look, rememberHubTime(), ClockDetail(), settings.clockVisible) },
                    alertState = StatsAlertUiState(), onDismissAlert = {}, archive = com.brightblu.parc.core.data.stats.StatsArchive.Empty,
                    presentation = StatsPresentation.of(frame), history = history, skin = skin,
                    expanded = settings.statsExpanded, onToggleExpanded = {}, focusActiveName = focus.activeName,
                    focusAvailable = modes.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Metrics.panelGutterDp.dp)
                        .padding(top = Metrics.keepOutTopDp.dp)
                        .height(com.brightblu.parc.ring.RingHudBounds.heightDp(Metrics.keepOutTopDp, bottom).dp))
            }
        }
    }
}

@Composable
internal fun SkinFullPreview(candidate: Skin, services: LauncherServices, look: LookSettings,
    onUse: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val layout by services.layout.layout.collectAsState()
    val apps by services.unlockedApps.collectAsState()
    val context = LocalContext.current
    val revision by services.iconRevision.collectAsState()
    val scene = remember(layout, apps, revision) { skinPreviewScene(services, layout, apps, ParcUiPrefs(context)) }
    val wallpaperFile = WallpaperImageStore(context).file
    val ring by produceState<ImageBitmap?>(null, candidate, scene, look) {
        value = withContext(Dispatchers.Default) {
            rasterSkinHome(scene, services, candidate, look, wallpaperFile)
        }
    }
    Box(Modifier.fillMaxSize().background(Color(candidate.colors.bg.toInt()))) {
        ring?.let { SkinHome(candidate, services, scene, it, look) }
        Box(Modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent().changes.forEach { it.consume() } }
        })
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            .padding(horizontal = Metrics.textGutterDp.dp).padding(bottom = Metrics.keepOutBottomDp.dp)) {
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
    fallbackBackground: android.graphics.Bitmap? = null): ImageBitmap {
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
    val background = com.brightblu.parc.ring.loadImageWallpaper(wallpaperFile, look, skin,
        services.geometry.widthDp.toInt(), services.geometry.heightDp.toInt()) ?: fallbackBackground
    return rasterHome(complete, skin, services.geometry.widthDp, services.geometry.heightDp, 1f,
        look, background).asImageBitmap()
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
