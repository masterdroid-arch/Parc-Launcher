package com.brightblu.parc.ring

import android.graphics.*
import android.os.SystemClock
import android.os.Debug
import android.util.Log
import android.view.MotionEvent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import com.brightblu.parc.model.*
import kotlinx.coroutines.*
import kotlin.math.*

/** Single Canvas renderer. Rasterization runs off main; animated values are read only in draw. */
class CanvasRingRenderer(private val allocationCheck: Boolean = false,
    private val detectSearchSwipe: Boolean = true,
    private val editing: RingEditing? = null,
    private val hapticsEnabled: () -> Boolean = { true },
    /** T80: the one place haptic events are performed; default prefs equal the old constants. */
    private val feel: FeelPlayer = FeelPlayer(),
    private val joystickEnabled: () -> Boolean = { true },
    /** T69: replaces the built-in edit-mode entry when a hub hold has another configured action. */
    private val hubHold: (() -> Unit)? = null,
    /** T71: look settings, read in composition so a change rebuilds the prepared scene. */
    private val look: () -> LookSettings = { LookSettings() },
    /** T71: optional date/alarm line under the clock, refreshed by the host. */
    private val clockDetail: () -> ClockDetail = { ClockDetail() },
    /** T83: a failed scene preparation is reported to the host instead of silently keeping the old scene. */
    private val onSceneError: ((String) -> Unit)? = null,
    /**
     * OPUS3: a scene to prepare in the background while another is shown (the All-apps ring while
     * Home is up), read in composition. Its rasters are cached so switching to it is quick; it is
     * never drawn by itself. Null prepares nothing extra.
     */
    private val warmScene: () -> RingState? = { null },
    /** OPUS4: external turning of the All-apps ring (the letter strip); null when not offered. */
    private val steer: RingSteer? = null,
    /**
     * OPUS5: touches that START where the host draws its own control over the ring (the All-apps
     * letter arc) are left alone entirely: no hub, aim, rotation or tap handling for that gesture.
     */
    private val ignoreDown: (xDp: Float, yDp: Float) -> Boolean = { _, _ -> false },
    /**
     * OPUS8 (T133): a long-press on empty Home space, with the touch point in dp. Null offers
     * nothing (the field behaves as before). The rules are in [EmptySpacePress].
     */
    private val emptyHold: ((xDp: Float, yDp: Float) -> Unit)? = null) : RingRenderer {
    private var searchSwipeStart: ((Float, Float) -> Boolean)? = null

    /** Uses the rendered scene and its live rotation offsets, in dp. */
    fun canStartSearchSwipe(x: Float, y: Float): Boolean = searchSwipeStart?.invoke(x, y) == true

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    override fun Field(state: RingState, skin: Skin, callbacks: RingCallbacks) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val density = LocalDensity.current.density
            val fontScale = LocalDensity.current.fontScale
            val lookSettings = look()
            val detail = clockDetail()
            val image = rememberImageWallpaper(lookSettings, skin, (maxWidth.value * density).toInt(), (maxHeight.value * density).toInt())
            val imageDestination = remember(maxWidth, maxHeight, density) { Rect(0, 0, (maxWidth.value * density).toInt(), (maxHeight.value * density).toInt()) }
            val imagePaint = remember { Paint(Paint.FILTER_BITMAP_FLAG) }
            val estimate = rememberWallpaperLuminance(lookSettings.wallpaper == WallpaperStyle.System)
            val readability = remember(skin, lookSettings, estimate, screenHeightKey(maxHeight.value, density)) {
                wallpaperScrim(skin, lookSettings, estimate, (maxHeight.value * density).toInt())
            }
            val view = LocalView.current
            val screenWidth = maxWidth.value
            val screenHeight = maxHeight.value
            val scope = rememberCoroutineScope()
            val latest by rememberUpdatedState(callbacks)
            val session = remember(density, skin) { RenderSession(skin) }
            SideEffect {
                searchSwipeStart = { x, y ->
                    val drawing = session.current
                    drawing != null && !drawing.hubHit(x, y) && !drawing.searchButtonHit(x, y) &&
                        drawing.hit(x, y, session.rotation.aligned) < 0 && drawing.rotationArc(x, y) < 0
                }
            }
            DisposableEffect(session) { onDispose { searchSwipeStart = null } }
            val environment = rememberRingEnvironment()
            val unread = !state.searchOpen && state.slots.any { (it.slot as? Slot.App)?.id in state.pulse }
            LaunchedEffect(session, environment.visible, environment.animatorScale) {
                session.finish()
                session.effects.settle()
                session.frame.intValue++
                session.motionScale = environment.animatorScale
                if (environment.visible) {
                    session.effects.resume(SystemClock.uptimeMillis())
                    session.activity.intValue++
                } else {
                    session.press?.cancel()
                    session.touching.value = false
                    session.focus.intValue = -1
                    session.dragging.value = false
                    session.aiming.value = false
                    session.hubGesture.cancel()
                    session.edgeHold.reset()
                    latest.onAim(null)
                }
            }
            LaunchedEffect(session, environment.visible, environment.animatorScale, session.activity.intValue, unread, state.searchHint) {
                if (!environment.visible) return@LaunchedEffect
                if (environment.animatorScale == 0f) {
                    session.rotation.snapAll { arcId, offset -> latest.onRotate(arcId, offset) }
                    session.frame.intValue++
                }
                while (isActive) {
                    val wait = withFrameNanos {
                        val now = SystemClock.uptimeMillis()
                        val effectsWait = session.effects.step(now, environment.animatorScale, unread, state.searchHint)
                        val rotating = session.rotation.step(now) { arcId, offset ->
                            latest.onRotate(arcId, offset)
                        }
                        if (session.aiming.value) {
                            val drawing = session.current
                            val x = session.x.floatValue / density
                            val y = session.y.floatValue / density
                            val zone = drawing?.edgeZone(x, y) ?: 0
                            val step = session.edgeHold.step(zone, now)
                            if (step != 0 && drawing != null) {
                                val id = drawing.state.arcs[kotlin.math.abs(zone) - 1].id
                                session.rotation.drag(id, -step.toFloat())
                                latest.onRotate(id, session.rotation.offset(id))
                                if (hapticsEnabled()) feel.play(view, FeelEvent.Tick)
                                val next = drawing.aim(x, y, -1, session.rotation.aligned)
                                session.lastAim = next
                                session.focus.intValue = next
                                latest.onAim(drawing.state.slots.getOrNull(next)?.position)
                            }
                        } else session.edgeHold.reset()
                        session.reportSteer(steer)
                        session.frame.intValue++
                        if (rotating || session.aiming.value) 0L else effectsWait
                    }
                    if (wait == Long.MAX_VALUE) break
                    if (wait > 0) delay(wait)
                }
            }
            val decorations = remember(skin, density, screenWidth, screenHeight) {
                SkinDecorationCache(skin, screenWidth, screenHeight, density)
            }
            var prepared by remember { mutableStateOf<SceneDrawing?>(null) }
            var warmed by remember { mutableStateOf<SceneDrawing?>(null) }
            // OPUS2: one preparation at a time, newest waiting request next, every result published.
            // Rasters are reused between preparations of the same look (see ScenePrepareQueue).
            val queue = remember { ScenePrepareQueue<SceneRequest>() }
            // OPUS3: rasters are kept per scene family (Home, All apps, the last folder), so the
            // warmed All-apps rasters survive while Home is prepared and shown.
            val reuse = remember { RasterFamilies<Pair<Layer, FolderId?>, SlotRasters>(3) { SlotRasters() } }
            fun runWorker() = scope.launch(Dispatchers.Default) {
                while (true) {
                    val (requested, warmOnly) = withContext(Dispatchers.Main.immediate) { queue.takeNext() } ?: break
                    val family = requested.state.layer to requested.state.arcs.firstOrNull()?.folderId
                    val drawing = try {
                        SceneDrawing(requested.state, requested.skin, requested.density, requested.fontScale,
                            requested.width, requested.height, requested.decorations.get(requested.state.arcs),
                            requested.look, requested.detail, reuse.get(family))
                    } catch (failure: Throwable) {
                        val detailText = "${requested.state.layer}/slots=${requested.state.slots.size}: " +
                            "${failure.javaClass.simpleName}: ${failure.message.orEmpty()}"
                        Log.e("Parc", "Scene preparation failed ($detailText)", failure)
                        onSceneError?.invoke(detailText)
                        null
                    }
                    // A warm-up is kept aside (its rasters are the cached ones, so it costs no extra
                    // memory) and only shown as a stand-in when its layer is asked for.
                    if (drawing != null) withContext(Dispatchers.Main.immediate) {
                        if (warmOnly) warmed = drawing else prepared = drawing
                    }
                }
            }
            LaunchedEffect(state, skin, density, fontScale, screenWidth, screenHeight, lookSettings, detail) {
                // OPUS3: switching to a layer that was warmed in the background shows the warmed
                // ring at once (icons included) instead of leaving the old scene up while the exact
                // one is prepared; the exact one replaces it as soon as it is ready.
                val standIn = warmed
                if (standIn != null && WarmStandIn.usable(standIn.state, state, prepared?.state, skin == standIn.skinUsed,
                        lookSettings == standIn.lookUsed)) prepared = standIn
                if (queue.offer(SceneRequest(state, skin, density, fontScale, screenWidth, screenHeight,
                        decorations, lookSettings, detail))) runWorker()
            }
            // OPUS4: the letter strip asks for an item at the front of the All-apps ring.
            val steerRequest = steer?.request?.value
            LaunchedEffect(steerRequest) {
                val request = steerRequest ?: return@LaunchedEffect
                val drawing = session.current ?: return@LaunchedEffect
                if (drawing.state.layer != Layer.AllApps || drawing.state.arcs.size != 1) return@LaunchedEffect
                val id = drawing.state.arcs[0].id
                val target = RingRotation.nearestOffsetFor(request.itemIndex, session.rotation.offset(id), drawing.state.slots.size)
                session.rotation.turnTo(id, target, SystemClock.uptimeMillis(),
                    if (request.animate) session.motionScale else 0f) { arcId, offset -> latest.onRotate(arcId, offset) }
                session.reportSteer(steer)
                session.activity.intValue++
                session.frame.intValue++
            }
            val warm = warmScene()
            LaunchedEffect(warm, skin, density, fontScale, screenWidth, screenHeight, lookSettings) {
                if (warm != null && warm.layer != state.layer &&
                    queue.offerWarm(SceneRequest(warm, skin, density, fontScale, screenWidth, screenHeight,
                        decorations, lookSettings, detail))) runWorker()
            }
            LaunchedEffect(prepared, session) {
                val next = prepared ?: return@LaunchedEffect
                if (session.current == null && environment.visible) {
                    session.effects.resume(SystemClock.uptimeMillis())
                    session.activity.intValue++
                }
                session.accept(next, scope, skin, environment.visible && environment.animatorScale > 0f) { latest.onSettled(it) }
                session.frame.intValue++
            }
            DisposableEffect(session) { onDispose { session.cancel() } }
            LaunchedEffect(session) {
                while (allocationCheck) {
                    delay(2_000)
                    Log.i("Parc", "T07 allocations supported=${session.counter.supported} samples=${session.counter.samples} objects=${session.counter.objects} max=${session.counter.maximum}")
                }
            }
            val editEnabled = editing?.enabled == true
            LaunchedEffect(editEnabled, state.layer, state.arcs.firstOrNull()?.folderId, state.searchOpen) {
                session.dragging.value = false
                session.rotating.value = false
            }
            val counter = session.counter
            LaunchedEffect(allocationCheck) { if (allocationCheck) counter.enable() }
            val input = if (state.searchOpen) Modifier else Modifier.pointerInteropFilter { event ->
                val x = event.x / density
                val y = event.y / density
                if (event.actionMasked == MotionEvent.ACTION_DOWN && ignoreDown(x, y)) return@pointerInteropFilter false
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        session.interrupt { latest.onInterrupted(it) }
                        session.rotation.snapAll { arcId, offset -> latest.onRotate(arcId, offset) }
                        session.effects.touch(SystemClock.uptimeMillis())
                        session.effects.finger(event.x, event.y, down = true)
                        session.activity.intValue++
                        session.touching.value = true
                        session.x.floatValue = event.x
                        session.y.floatValue = event.y
                        session.hubTravelDp = 0f
                        session.downX = x
                        session.downY = y
                        val drawing = session.current
                        val onHub = drawing?.hubHit(x, y) == true
                        // One monotonic clock for the tap deadline and the hold timer.
                        val now = SystemClock.uptimeMillis()
                        session.hubGesture.down(onHub && !editEnabled, now)
                        session.hubGesture.move(drawing?.hubReach(x, y) ?: 0f)
                        session.hubDownOnHub = onHub && !editEnabled
                        session.hubDownTime = now
                        session.hubTapSent = false
                        session.edgeHold.reset()
                        session.aimCandidate = onHub && !editEnabled && joystickEnabled()
                        session.aiming.value = false
                        session.lastAim = -1
                        session.rotating.value = false
                        session.rotateCandidate = if (!onHub && !editEnabled) drawing?.rotationArc(x, y) ?: -1 else -1
                        session.rotateLastRounded = if (session.rotateCandidate >= 0) {
                            val id = drawing?.state?.arcs?.getOrNull(session.rotateCandidate)?.id.orEmpty()
                            round(session.rotation.offset(id)).toInt()
                        } else 0
                        session.focus.intValue = if (onHub) -1 else drawing?.hit(x, y, session.rotation.aligned) ?: -1
                        session.downSlot = session.focus.intValue
                        session.longPressed = false
                        session.emptyHold = false
                        val slot = drawing?.state?.slots?.getOrNull(session.downSlot)
                        session.dragFrom = if (editEnabled) slot?.position else null
                        session.dragging.value = session.dragFrom != null
                        // Long-press routing (T30): in edit mode nothing opens a menu; a press on a
                        // slot opens its menu (app, folder, action); only a hub hold enters edit mode.
                        if (editEnabled) {
                            session.press = null
                        } else if (onHub) {
                            session.press = scope.launch {
                                delay(Metrics.longPressMs)
                                if (session.hubGesture.longPress(SystemClock.uptimeMillis())) {
                                    session.longPressed = true
                                    if (hubHold != null) hubHold()
                                    else if (drawing?.state?.layer != Layer.Recents) editing?.enter()
                                }
                            }
                        } else if (slot != null) {
                            session.press = scope.launch {
                                delay(Metrics.longPressMs)
                                session.longPressed = true
                                latest.onLongPress(SlotHit(slot.position, slot.slot, x, y))
                            }
                        } else if (emptyHold != null && EmptySpacePress.armed(drawing?.state?.layer ?: Layer.Home,
                                editEnabled, onHub, onSlot = false, onSearchButton = drawing?.searchButtonHit(x, y) == true,
                                offered = drawing != null)) {
                            session.emptyHold = true
                            session.press = scope.launch {
                                delay(Metrics.longPressMs)
                                session.longPressed = true
                                session.focus.intValue = -1
                                emptyHold.invoke(x, y)
                            }
                        }
                    }
                    MotionEvent.ACTION_MOVE -> {
                        session.effects.touch(SystemClock.uptimeMillis())
                        session.effects.finger(event.x, event.y)
                        if (session.motionScale == 0f) session.effects.settle()
                        session.activity.intValue++
                        session.hubTravelDp += hypot(x - session.x.floatValue / density, y - session.y.floatValue / density)
                        session.x.floatValue = event.x
                        session.y.floatValue = event.y
                        val drawing = session.current
                        session.hubGesture.move(drawing?.hubReach(x, y) ?: 0f)
                        if (session.hubGesture.aiming) session.press?.cancel()
                        if (session.emptyHold && !EmptySpacePress.holds(hypot(x - session.downX, y - session.downY),
                                session.rotating.value, event.pointerCount)) session.press?.cancel()
                        if (session.dragging.value) {
                            val to = session.current?.position(x, y)
                            session.dropPosition = to
                            session.dropAllowed = to != null && editing?.canDrop(session.dragFrom!!, to) == true
                        } else if (session.rotateCandidate >= 0) {
                            val drawing = session.current
                            val arcId = drawing?.state?.arcs?.getOrNull(session.rotateCandidate)?.id.orEmpty()
                            if (!session.rotating.value &&
                                hypot(x - session.downX, y - session.downY) > Metrics.rotateSlopDp) {
                                session.rotating.value = true
                                session.press?.cancel()
                                session.focus.intValue = -1
                                session.rotateLastX = x
                                session.rotateLastY = y
                                session.rotateLastTime = SystemClock.uptimeMillis()
                                session.rotateVelocity = 0f
                            }
                            if (session.rotating.value) {
                                val slots = drawing?.rotateSlots(session.rotateCandidate, x, y,
                                    x - session.rotateLastX, y - session.rotateLastY) ?: 0f
                                session.rotation.drag(arcId, slots)
                                val rounded = round(session.rotation.offset(arcId)).toInt()
                                if (rounded != session.rotateLastRounded) {
                                    session.rotateLastRounded = rounded
                                    session.reportSteer(steer)
                                    if (hapticsEnabled()) feel.play(view, FeelEvent.Tick)
                                }
                                val now = SystemClock.uptimeMillis()
                                val dt = (now - session.rotateLastTime).coerceAtLeast(1L) / 1000f
                                // drag subtracts `slots` from the offset, so the offset velocity is -slots/dt.
                                if (dt > 0f) session.rotateVelocity = -slots / dt
                                session.rotateLastX = x
                                session.rotateLastY = y
                                session.rotateLastTime = now
                            }
                        } else if (session.aimCandidate) {
                            if (!session.aiming.value &&
                                session.hubGesture.aiming) {
                                session.aiming.value = true
                            }
                            if (session.aiming.value) {
                                val drawing = session.current
                                val next = drawing?.aim(x, y, session.lastAim, session.rotation.aligned) ?: -1
                                if (next != session.focus.intValue) {
                                    session.focus.intValue = next
                                    val aimed = if (next >= 0) drawing?.state?.slots?.getOrNull(next)?.position else null
                                    if (next != -1 && hapticsEnabled()) feel.play(view, FeelEvent.Tick)
                                    latest.onAim(aimed)
                                }
                                session.lastAim = next
                            }
                        } else {
                            val next = session.current?.hit(x, y, session.rotation.aligned) ?: -1
                            if (next >= 0 && next != session.focus.intValue && hapticsEnabled())
                                feel.play(view, FeelEvent.Tick)
                            session.focus.intValue = next
                            if (hypot(x - session.downX, y - session.downY) > Metrics.gridDp * 2) session.press?.cancel()
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        val now = SystemClock.uptimeMillis()
                        session.effects.touch(now)
                        session.effects.release()
                        session.activity.intValue++
                        session.press?.cancel()
                        session.hubTravelDp += hypot(x - session.x.floatValue / density, y - session.y.floatValue / density)
                        val drawing = session.current
                        session.hubGesture.move(drawing?.hubReach(x, y) ?: 0f)
                        session.aiming.value = session.aimCandidate && session.hubGesture.aiming
                        if (session.dragging.value) {
                            val from = session.dragFrom!!
                            val to = drawing?.position(x, y)
                            if (to != null && editing?.canDrop(from, to) == true) editing.drop(from, to)
                            else if (hapticsEnabled()) feel.play(view, FeelEvent.Reject)
                        } else if (session.rotating.value) {
                            val arcId = drawing?.state?.arcs?.getOrNull(session.rotateCandidate)?.id.orEmpty()
                            session.rotation.startFling(arcId, session.rotateVelocity, SystemClock.uptimeMillis(),
                                session.motionScale) { id, off -> latest.onRotate(id, off) }
                        } else if (session.aiming.value) {
                            val aimed = session.hubGesture.release(drawing?.hubReach(x, y) ?: 0f, now,
                                drawing?.aim(x, y, session.lastAim, session.rotation.aligned) ?: -1,
                                session.hubTravelDp)
                            if (aimed == RingAim.BACK) latest.onHubBack()
                            else if (aimed == HubGesture.TAP) { latest.onHubTap(); session.hubTapSent = true }
                            if (aimed >= 0) {
                                val slot = drawing?.state?.slots?.getOrNull(aimed)
                                if (slot != null) {
                                    latest.onTap(SlotHit(slot.position, slot.slot, x, y))
                                }
                            }
                            latest.onAim(null)
                            // A quick return to the dead zone is a tap; a long aim cancels there.
                        } else if (editEnabled) {
                            if (drawing?.state?.layer == Layer.Home &&
                                hypot(x - session.downX, y - session.downY) < Metrics.gridDp * 2) {
                                val position = drawing.position(x, y)
                                if (position != null && drawing.state.slots.none { it.position == position }) editing?.empty(position)
                            }
                        } else if (session.hubGesture.active) {
                            if (session.hubGesture.release(drawing?.hubReach(x, y) ?: Float.POSITIVE_INFINITY, now, -1,
                                    session.hubTravelDp) == HubGesture.TAP) {
                                latest.onHubTap(); session.hubTapSent = true
                            }
                        } else if (!session.longPressed && drawing != null) {
                            val hit = drawing.hit(x, y, session.rotation.aligned)
                            if (detectSearchSwipe && y - session.downY >= Metrics.searchSwipeDp && session.downY > Metrics.keepOutTopDp) latest.onSearch()
                            else if (drawing.searchButtonHit(x, y)) latest.onSearch()
                            else if (hit >= 0 && hit == session.downSlot) {
                                val slot = drawing.state.slots[hit]
                                latest.onTap(SlotHit(slot.position, slot.slot, x, y))
                            }
                        }
                        // T82 safety net: a finger that went down and up on the hub under the
                        // long-press deadline is a tap even if the gesture state was cancelled
                        // between DOWN and UP (scene swap, visibility effect, stray cancel).
                        if (!session.hubTapSent && !editEnabled && !session.longPressed &&
                            session.hubDownOnHub && !session.dragging.value && !session.rotating.value &&
                            drawing?.hubHit(x, y) == true &&
                            session.hubTravelDp < Metrics.hubTapTravelDp &&
                            (now - session.hubDownTime).coerceAtLeast(0L) < Metrics.longPressMs) {
                            latest.onHubTap()
                            session.hubTapSent = true
                        }
                        session.dragging.value = false
                        session.rotating.value = false
                        session.rotateCandidate = -1
                        session.dropPosition = null
                        session.aiming.value = false
                        session.aimCandidate = false
                        session.hubGesture.cancel()
                        session.hubDownOnHub = false
                        session.edgeHold.reset()
                        session.lastAim = -1
                        session.touching.value = false
                        session.focus.intValue = -1
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        session.effects.release()
                        session.activity.intValue++
                        session.press?.cancel()
                        if (session.aiming.value) latest.onAim(null)
                        if (session.rotating.value) session.rotation.snapAll { id, off -> latest.onRotate(id, off) }
                        session.dragging.value = false
                        session.rotating.value = false
                        session.rotateCandidate = -1
                        session.dropPosition = null
                        session.aiming.value = false
                        session.aimCandidate = false
                        session.hubGesture.cancel()
                        session.edgeHold.reset()
                        session.lastAim = -1
                        session.touching.value = false
                        session.focus.intValue = -1
                    }
                }
                true
            }
            Canvas(Modifier.fillMaxSize().then(input)) {
                val before = counter.before()
                session.frame.intValue // Subscribe in draw only.
                val effects = session.effects
                val canvas = drawContext.canvas.nativeCanvas
                if (lookSettings.wallpaper == WallpaperStyle.System) {
                    // A translucent window keeps the previous frame's buffer; clear it before the
                    // dim so closing Settings cannot leave a ghost of the old surface behind.
                    canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                }
                canvas.drawColor(if (lookSettings.wallpaper == WallpaperStyle.Image) Color.BLACK else backgroundArgb(skin, lookSettings))
                if (lookSettings.wallpaper == WallpaperStyle.Image && image != null) canvas.drawBitmap(image, null, imageDestination, imagePaint)
                if (readability != null) canvas.drawRect(0f, 0f, size.width, size.height, readability)
                val progress = session.progress.floatValue
                val old = session.outgoing
                val current = session.current
                current?.drawBackground(canvas)
                val home = session.sharedHome
                val folder = session.sharedFolder
                val shared = old != null && home != null && folder != null && progress < 1f
                if (shared) {
                    val opening = if (current?.state?.layer == Layer.Folder) progress else 1f - progress
                    val eased = FolderTransition.emphasis(opening)
                    home!!.draw(canvas, 1f - (1f - Metrics.folderGhostAlpha) * eased,
                        1f - (1f - Metrics.homeRecedeScale) * eased, -1, false, omitted = session.sharedIndex,
                        showCaptions = false)
                    folder!!.draw(canvas, eased, 1f, -1, false, expansion = opening)
                    home.drawShared(canvas, session.sharedIndex, opening)
                } else if (old != null && progress < 1f) old.draw(canvas, RingSnap.outgoingAlpha(progress), RingSnap.outgoingScale(progress), -1, false)
                else if (current?.state?.layer == Layer.Folder && home != null) {
                    home.draw(canvas, Metrics.folderGhostAlpha, Metrics.homeRecedeScale, -1, false,
                        omitted = session.sharedIndex, showCaptions = false)
                    home.drawShared(canvas, session.sharedIndex, 1f)
                }
                if (current != null) {
                    if (!shared) {
                        current.draw(canvas, if (old == null) 1f else RingSnap.incomingAlpha(progress),
                            if (old == null) 1f else RingSnap.incomingScale(progress), session.focus.intValue,
                            aiming = session.aiming.value,
                            editing = editEnabled, dragged = if (session.dragging.value) session.downSlot else -1,
                            dragX = session.x.floatValue, dragY = session.y.floatValue,
                            drop = session.dropPosition, allowed = session.dropAllowed,
                            sweep = effects.sweep, slotsAlpha = effects.slots,
                            touching = session.touching.value, lineAlpha = effects.lineAlpha, pulse = if (unread) effects.pulse else 0f,
                            swap = effects.swap, rotations = session.rotation.aligned)
                    }
                    current.drawChrome(canvas, session.touching.value, effects.glowX, effects.glowY,
                        session.aiming.value && session.focus.intValue == RingAim.BACK,
                        hint = effects.hint, showHint = state.searchHint)
                }
                counter.after(before)
            }
            RingAccessibility(state, callbacks)
        }
    }
}

private class RenderSession(skin: Skin) {
    /** OPUS4: tells the letter strip which slot offset the All-apps ring shows. Allocation-free. */
    fun reportSteer(steer: RingSteer?) {
        if (steer == null) return
        val drawing = current ?: return
        if (drawing.state.layer != Layer.AllApps || drawing.state.arcs.size != 1) return
        steer.report(rotation.offset(drawing.state.arcs[0].id))
    }

    val effects = RingMotion(skin)
    val frame = mutableIntStateOf(0)
    val activity = mutableIntStateOf(0)
    var motionScale = 1f
    var current by mutableStateOf<SceneDrawing?>(null)
    var outgoing by mutableStateOf<SceneDrawing?>(null)
    val progress = mutableFloatStateOf(1f)
    val touching = mutableStateOf(false)
    val x = mutableFloatStateOf(0f)
    val y = mutableFloatStateOf(0f)
    val focus = mutableIntStateOf(-1)
    val aiming = mutableStateOf(false)
    val hubGesture = HubGesture()
    val edgeHold = EdgeHold()
    val dragging = mutableStateOf(false)
    var dragFrom: SlotPosition? = null
    var dropPosition: SlotPosition? = null
    var dropAllowed = false
    var hubTravelDp = 0f
    /** T82: taps are decided from the hub DOWN, independently of the gesture's internal state. */
    var hubDownOnHub = false
    var hubDownTime = 0L
    var hubTapSent = false
    var downX = 0f
    var downY = 0f
    var downSlot = -1
    var longPressed = false
    /** OPUS8: the current press is a hold on empty Home space. */
    var emptyHold = false
    var aimCandidate = false
    var lastAim = -1
    var press: Job? = null
    private var snap: Job? = null
    var sharedHome: SceneDrawing? = null
    var sharedFolder: SceneDrawing? = null
    var sharedIndex = -1
    val counter = DrawCounter()
    val rotation = RotationRuntime()
    val rotating = mutableStateOf(false)
    var rotateCandidate = -1
    var rotateVelocity = 0f
    var rotateLastX = 0f
    var rotateLastY = 0f
    var rotateLastTime = 0L
    var rotateLastRounded = 0
    private var rotationArcIds: List<String> = emptyList()
    private var lastSwapPulse: Set<SlotPosition> = emptySet()

    private fun adopt(next: SceneDrawing) {
        val ids = next.state.arcs.map { it.id }
        if (ids != rotationArcIds) {
            rotation.stopFlings()
            rotationArcIds = ids
        }
        rotation.seed(next.state.arcs, next.state.rotations)
    }

    fun accept(next: SceneDrawing, scope: CoroutineScope, skin: Skin, animated: Boolean, settled: (RingState) -> Unit) {
        val previous = current
        adopt(next)
        // Usage-layer swap (T35): play the cross-fade once per distinct set of changed slots.
        if (next.state.swapPulse != lastSwapPulse) {
            lastSwapPulse = next.state.swapPulse
            if (next.state.swapPulse.isNotEmpty()) effects.startSwap(SystemClock.uptimeMillis())
        }
        if (!animated) {
            if (previous?.state?.layer == Layer.Home && next.state.layer == Layer.Folder) {
                sharedHome = previous; sharedFolder = next
                sharedIndex = previous.folderIndex(next.state.arcs.firstOrNull()?.folderId)
            } else if (next.state.layer != Layer.Folder) {
                sharedHome = null; sharedFolder = null
            }
            snap?.cancel(); outgoing = null; current = next; progress.floatValue = 1f
            settled(next.state)
            return
        }
        if (previous == null || previous.sameScene(next)) {
            current = next
            progress.floatValue = if (outgoing == null) 1f else progress.floatValue
            return
        }
        // A newer host target wins; only a pointer interruption reports its chosen endpoint.
        interrupt { }
        outgoing = current
        current = next
        sharedHome = if (outgoing?.state?.layer == Layer.Home && next.state.layer == Layer.Folder) outgoing
            else if (outgoing?.state?.layer == Layer.Folder && next.state.layer == Layer.Home) next else null
        sharedFolder = if (sharedHome == null) null else if (next.state.layer == Layer.Folder) next else outgoing
        sharedIndex = sharedHome?.folderIndex(sharedFolder?.state?.arcs?.firstOrNull()?.folderId) ?: -1
        progress.floatValue = 0f
        snap = scope.launch {
            val ease = skin.effectiveMotion.easeEmphasized
            animate(0f, 1f, animationSpec = tween(((if (sharedHome != null) Metrics.folderTransitionMs else skin.effectiveMotion.durSnap) * skin.motionScale).toInt(),
                easing = if (sharedHome != null) LinearEasing else CubicBezierEasing(ease.x1, ease.y1, ease.x2, ease.y2))) { value, _ ->
                progress.floatValue = value
            }
            outgoing = null
            progress.floatValue = 1f
            current?.let { settled(it.state) }
        }
    }
    fun interrupt(settled: (RingState) -> Unit) {
        if (outgoing == null) return
        snap?.cancel()
        if (!RingSnap.endpoint(progress.floatValue)) current = outgoing
        if (current?.state?.layer != Layer.Folder) { sharedHome = null; sharedFolder = null }
        outgoing = null
        progress.floatValue = 1f
        current?.let { settled(it.state) }
    }
    fun finish() {
        snap?.cancel(); outgoing = null; progress.floatValue = 1f; rotation.stopFlings()
    }
    fun cancel() { snap?.cancel(); press?.cancel(); rotation.stopFlings() }
}

/**
 * T71 background: the default black stays byte-identical to T07; a solid skin surface is the
 * darker surface token; a system wallpaper leaves the window showing through with a black dim.
 */
private fun backgroundArgb(skin: Skin, look: LookSettings): Int = when (look.wallpaper) {
    WallpaperStyle.Image -> Color.BLACK
    WallpaperStyle.Black -> skin.colors.bg.toInt()
    WallpaperStyle.Solid -> skin.colors.surface.toInt()
    WallpaperStyle.System -> {
        val alpha = (look.wallpaperDimPercent * 255 / 100).coerceIn(0, 255)
        (alpha shl 24) or (skin.colors.bg.toInt() and 0x00FFFFFF)
    }
}

/** One scene to prepare, with everything it was asked with (OPUS2). */
private class SceneRequest(val state: RingState, val skin: Skin, val density: Float, val fontScale: Float,
    val width: Float, val height: Float, val decorations: SkinDecorationCache, val look: LookSettings,
    val detail: ClockDetail)

/** OPUS2: what a slot raster depends on besides the scene-wide style. */
private data class SlotRasterKey(val kind: Int, val source: Bitmap?, val slot: Slot?, val label: String)

/** OPUS2: slot rasters kept between preparations; only the preparation worker touches it. */
private class SlotRasters {
    val rasters = RasterReuse<SlotRasterKey, Bitmap>()
    val labels = RasterReuse<SlotRasterKey, Optional<Bitmap>>()
}

/** Nullable values in a map that must not store null. */
private class Optional<T>(val value: T?)

/** All paints, bitmaps, geometry and labels are prepared before publication to the UI thread. */
private class SceneDrawing(val state: RingState, private val skin: Skin,
    private val density: Float, private val fontScale: Float, private val screenWidth: Float, private val screenHeight: Float,
    private val decoration: SkinDecoration,
    private val look: LookSettings = LookSettings(),
    private val clockDetail: ClockDetail = ClockDetail(),
    /** OPUS2: rasters reused from the previous preparation; null builds everything (gallery rasters). */
    private val reuse: SlotRasters? = null) {
    /** OPUS3: what this drawing was prepared with, so a warmed one is only shown when still right. */
    val skinUsed: Skin get() = skin
    val lookUsed: LookSettings get() = look
    private val spec = skin.drawingSpec()
    private val legacyIconFrame = spec.iconFrame == IconFrame.disc && spec.iconOutlineDp == 0f
    private val railPaint = decoration.railPaint()
    private val bearingPaint = decoration.bearingPaint()
    private val bearingVisible: BooleanArray
    private val searchButton = RingSearchButtonPlacement(state.arcs)
    private val cx = (state.arcs.firstOrNull()?.originXDp ?: 0f) * density
    private val cy = (state.arcs.firstOrNull()?.originYDp ?: 0f) * density
    private val paths = Array(state.arcs.size) { Path() }
    private val wakePaths = Array(state.arcs.size) { Path() }
    private val measures = Array(state.arcs.size) { PathMeasure() }
    private val pulseSlots = BooleanArray(state.slots.size) {
        (state.slots[it].slot as? Slot.App)?.id in state.pulse
    }
    private val badgeText = Array(state.slots.size) {
        when (state.slots[it].slot) {
            is Slot.App -> notificationBadgeText(state.slots[it].unreadCount)
            is Slot.Folder -> folderBadgeText(state.slots[it].unreadCount)
            else -> null
        }
    }
    /** T75: prepared per scene; the draw loop reads this array and allocates nothing. */
    private val workSlots = BooleanArray(state.slots.size) { state.slots[it].isWork }
    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.colors.accent.toInt() }
    private val badgeTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.bg.toInt()
        textSize = skin.typography.caption.sizeSp * density
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(spec.annotationFamily, Typeface.BOLD)
    }
    private val badgeBaseline = -(badgeTextPaint.ascent() + badgeTextPaint.descent()) / 2f
    private val workPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.colors.accent.toInt() }
    private val workGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.bg.toInt(); style = Paint.Style.FILL
    }
    private val pulsePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.pulse.toInt(); style = Paint.Style.STROKE
        strokeWidth = skin.lineWidths.activeDp * density
    }
    private val lines = Array(state.arcs.size * 2) { Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND
    } }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val hub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.accent.toInt(); style = Paint.Style.STROKE
        strokeWidth = skin.lineWidths.activeDp * density
    }
    private val distanceLabels = Array(state.slots.size) { i ->
        val arc = state.arcs.firstOrNull { it.id == state.slots[i].position.arcId }
        if (spec.aim == AimStyle.distance && arc != null) text("Reach ${arc.radiusDp.toInt()}", skin.typography.caption, 1f,
            textColor = skin.colors.accent.toInt()) else null
    }
    private val coordinateGlyphs = if (spec.aim == AimStyle.coordinates)
        Array(12) { text(if (it == 10) "X" else if (it == 11) "Y" else it.toString(),
            skin.typography.caption, 1f, textColor = skin.colors.pulse.toInt())!! } else emptyArray()
    private fun drawCoordinate(canvas: Canvas, value: Int, axis: Int, x: Float, y: Float) {
        val width = coordinateGlyphs[0].width.toFloat()
        canvas.drawBitmap(coordinateGlyphs[axis], x, y, paint)
        val n = value.coerceIn(0, 999)
        canvas.drawBitmap(coordinateGlyphs[n / 100], x + width, y, paint)
        canvas.drawBitmap(coordinateGlyphs[n / 10 % 10], x + width * 2, y, paint)
        canvas.drawBitmap(coordinateGlyphs[n % 10], x + width * 3, y, paint)
    }
    private val aimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = (if (spec.aim == AimStyle.coordinates) skin.colors.pulse else skin.colors.accent).toInt(); style = Paint.Style.STROKE
        strokeWidth = skin.lineWidths.activeDp * density; strokeCap = Paint.Cap.ROUND
    }
    private val aimLinePaint = Paint(aimPaint).apply {
        if (spec.aim == AimStyle.coordinates) color = skin.colors.pulse.toInt()
        if (spec.aim == AimStyle.dotted || spec.aim == AimStyle.coordinates) pathEffect = DashPathEffect(floatArrayOf(
            skin.lineWidths.activeDp * density, Metrics.gridDp * density), 0f)
    }
    private val modeGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.textSecondary.toInt(); style = Paint.Style.FILL
    }
    private val searchGlyphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.accent.toInt(); style = Paint.Style.STROKE
        strokeWidth = skin.lineWidths.activeDp * density; strokeCap = Paint.Cap.ROUND
    }
    private val searchCaption = text("Search", skin.typography.caption, 1f, textColor = skin.colors.textSecondary.toInt())
    private val rect = RectF()
    private val positions = state.arcs.flatMap { arc ->
        List(arc.slotCapacity) { SlotPosition(arc.id, it) }
    }
    private val emptyPositions = positions.filter { pos -> state.slots.none { it.position == pos } }
    private val emptyX = FloatArray(emptyPositions.size)
    private val emptyY = FloatArray(emptyPositions.size)
    private val editPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = skin.lineWidths.activeDp * density
    }
    private val px = FloatArray(state.slots.size)
    private val py = FloatArray(state.slots.size)
    private val captions = arrayOfNulls<Bitmap>(state.slots.size)
    private val captionX = FloatArray(state.slots.size)
    private val captionY = FloatArray(state.slots.size)
    private val arcIndex = IntArray(state.slots.size)
    private val dim = FloatArray(state.slots.size)
    private val swapFade = BooleanArray(state.slots.size) { state.slots[it].position in state.swapPulse }
    private val icons = arrayOfNulls<Bitmap>(state.slots.size)
    private val labels = arrayOfNulls<Bitmap>(state.slots.size)
    private val depthScale = FloatArray(state.arcs.size) { skin.depthScale[it.coerceAtMost(3)] }
    private val depthAlpha = FloatArray(state.arcs.size) { skin.depthAlpha[it.coerceAtMost(3)] }
    private val itemIndex = IntArray(state.slots.size)
    private val arcAngle0 = FloatArray(state.arcs.size)
    private val arcPitch = FloatArray(state.arcs.size)
    private val arcRadiusPx = FloatArray(state.arcs.size)
    private val arcCapacity = IntArray(state.arcs.size)
    private val arcCount = IntArray(state.arcs.size)
    private val arcRotates = BooleanArray(state.arcs.size)
    private val settled = FloatArray(state.arcs.size)
    /** OPUS8 (T133): names on the front icon of each arc, in All apps and folders only. */
    private val frontNames = FrontNames.shown(state.layer)
    private val arcLowestItem = IntArray(state.arcs.size) { -1 }
    /** Per arc: flat slot index for each item index (filled in init); never resized in draw. */
    private val arcItemSlot = Array(state.arcs.size) { a ->
        val arc = state.arcs[a]
        IntArray(maxOf(arc.slotCapacity, state.slots.count { it.position.arcId == arc.id })) { -1 }
    }
    private val frontRects = FloatArray(state.arcs.size * 4)
    /** Result of [placeLabel] in dp; fields so the draw loop allocates nothing. */
    private var labelLeftDp = 0f
    private var labelTopDp = 0f
    private val emptyArc = IntArray(emptyPositions.size) { i -> state.arcs.indexOfFirst { it.id == emptyPositions[i].arcId } }
    private val discRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = skin.colors.lineActive.toInt(); style = Paint.Style.STROKE
        strokeWidth = Metrics.discRimDp * density
    }
    private val contact = Bitmap.createBitmap(Metrics.iconBitmapPx, Metrics.iconBitmapPx, Bitmap.Config.ARGB_8888).also { bitmap ->
        val r = bitmap.width / 2f
        val color = (skin.colors.bg.toInt() and 0xFFFFFF) or ((255 * Metrics.contactShadowAlpha).toInt() shl 24)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(r, r, r, intArrayOf(color, color, color and 0xFFFFFF),
                floatArrayOf(0f, 0.7f, 1f), Shader.TileMode.CLAMP)
        }
        Canvas(bitmap).drawCircle(r, r, r, p)
    }
    private val half = look.iconSizeDp * density / 2f
    private val glowRadius = skin.glowRadiusDp * density
    private val glow = Bitmap.createBitmap(Metrics.iconBitmapPx, Metrics.iconBitmapPx, Bitmap.Config.ARGB_8888)
    private val headerTypeface = if (spec.header == HeaderStyle.ruled) Typeface.create(spec.clockFamily, Typeface.NORMAL) else null
    private val title = text(state.title, skin.typography.title, fontScale, typefaceOverride = headerTypeface)
    private val clock = clockText(state.clock)
    private val clockSubline = if (clock == null) null else clockDetail.text.takeIf { it.isNotEmpty() }?.let {
        text(it, skin.typography.caption, 1f, textColor = skin.colors.textSecondary.toInt())
    }
    private val empty = text(state.emptyMessage, skin.typography.search, fontScale)
    private val chromeDim = if (state.searchOpen && state.highlight == null) Metrics.searchIdleAlpha else 1f

    private val accessibilityFocus = state.slots.indexOfFirst { it.position == state.accessibilityFocus }

    private fun cachedLabel(kind: Int, label: String, make: () -> Bitmap?): Bitmap? =
        reuse?.labels?.get(SlotRasterKey(kind, null, null, label)) { Optional(make()) }?.value ?: if (reuse == null) make() else null

    /** The parts of a slot that change its icon raster when there is no source bitmap. */
    private fun slotIdentity(slot: RingSlot): Slot? = when (val s = slot.slot) {
        is Slot.App -> if (slot.bitmap == null) s else null
        is Slot.Folder -> s
        else -> s
    }

    init {
        reuse?.rasters?.begin(listOf(skin, look, density))
        reuse?.labels?.begin(listOf(skin, look, density, fontScale, screenWidth))
        emptyPositions.forEachIndexed { i, pos ->
            val arc = state.arcs.first { it.id == pos.arcId }
            val angle = Math.toRadians(arc.slotAngle(pos.index).toDouble())
            emptyX[i] = cos(angle).toFloat() * arc.radiusDp * density
            emptyY[i] = sin(angle).toFloat() * arc.radiusDp * density
        }
        state.arcs.forEachIndexed { i, arc ->
            val radius = arc.radiusDp * density
            rect.set(-radius, -radius, radius, radius)
            paths[i].addArc(rect, arc.startAngle, arc.endAngle - arc.startAngle)
            measures[i].setPath(paths[i], false)
            measures[i].getSegment(0f, measures[i].length, wakePaths[i], true)
            wakePaths[i].rewind()
            val start = min(arc.startAngle, arc.endAngle) / 360f
            val end = max(arc.startAngle, arc.endAngle) / 360f
            val fade = (end - start) * skin.shape.lineEndFadeFraction
            for (active in 0..1) {
                val color = (if (active == 1) skin.colors.lineActive else skin.colors.lineBase).toInt()
                lines[i * 2 + active].apply {
                    if (spec.dashOnDp > 0f || (skin.style.arc == ArcStyle.contour && i % 2 == 1)) {
                        pathEffect = DashPathEffect(floatArrayOf(
                            (if (spec.dashOnDp > 0f) spec.dashOnDp else Metrics.atlasMajorTickDp) * density,
                            (if (spec.dashOffDp > 0f) spec.dashOffDp else Metrics.gridDp * 1.5f) * density), 0f)
                    }
                    strokeWidth = (if (active == 1) skin.lineWidths.activeDp else skin.lineWidths.baseDp) * density
                    shader = SweepGradient(0f, 0f, intArrayOf(color and 0xffffff, color and 0xffffff,
                        color, color, color and 0xffffff, color and 0xffffff),
                        floatArrayOf(0f, start, start + fade, end - fade, end, 1f))
                }
            }
        }
        state.arcs.forEachIndexed { a, arc ->
            arcAngle0[a] = if (arc.slotCapacity > 0) arc.slotAngle(0) else 0f
            arcPitch[a] = RingRotation.pitchDeg(arc)
            arcRadiusPx[a] = arc.radiusDp * density
            arcCapacity[a] = arc.slotCapacity
            arcCount[a] = 0
            settled[a] = state.rotations[arc.id] ?: 0f
        }
        for (slot in state.slots) {
            val a = state.arcs.indexOfFirst { it.id == slot.position.arcId }
            if (a >= 0) arcCount[a]++
        }
        for (a in state.arcs.indices) arcRotates[a] = RingRotation.rotates(arcCount[a], arcCapacity[a])
        state.slots.forEachIndexed { i, slot ->
            val a = state.arcs.indexOfFirst { it.id == slot.position.arcId }
            require(a >= 0)
            val arc = state.arcs[a]
            arcIndex[i] = a
            itemIndex[i] = ringItemIndex(slot, state.slots, arc.slotCapacity)
            if (itemIndex[i] in arcItemSlot[a].indices) arcItemSlot[a][itemIndex[i]] = i
            if (arcLowestItem[a] < 0 || itemIndex[i] < arcLowestItem[a]) arcLowestItem[a] = itemIndex[i]
            val angle = Math.toRadians(
                RingRotation.windowAngleDeg(arc, itemIndex[i], settled[a], arcCount[a]).toDouble())
            px[i] = cos(angle).toFloat() * arc.radiusDp * density
            py[i] = sin(angle).toFloat() * arc.radiusDp * density
            val app = slot.slot as? Slot.App
            val highlight = state.highlight
            dim[i] = if (!slot.enabled || (highlight != null && (app == null || app.id !in highlight))) Metrics.searchDimAlpha else chromeDim
            icons[i] = reuse?.rasters?.get(SlotRasterKey(a, slot.bitmap, slotIdentity(slot), if (slot.bitmap == null) slot.label else "")) { icon(slot) } ?: icon(slot)
            if (slot.slot is Slot.Folder) captions[i] = cachedLabel(1, slot.label) { if ((look.wallpaper == WallpaperStyle.System || look.wallpaper == WallpaperStyle.Image) && spec.label != LabelStyle.mapLabel) backedText(folderCaption(slot.label), skin.typography.caption, Metrics.folderLabelMaxWidthDp) else text(if (spec.label == LabelStyle.brackets) "[ ${folderCaption(slot.label)} ]" else folderCaption(slot.label), skin.typography.caption,
                1f, Metrics.folderLabelMaxWidthDp, halo = spec.label == LabelStyle.mapLabel) }
            labels[i] = cachedLabel(2, slot.label) { labelPill(slot.label, min(Metrics.labelMaxWidthDp,
                screenWidth - Metrics.keepOutLeftDp - Metrics.keepOutRightDp)) }
        }
        bearingVisible = decoration.visibleBearings(px, py,
            (look.iconSizeDp / 2f + Metrics.gridDp * 4) * density)
        val folderPlacement = FolderLabels(
            FloatArray(px.size) { (px[it] + cx) / density },
            FloatArray(py.size) { (py[it] + cy) / density },
            FloatArray(px.size) { (captions[it]?.width ?: 0) / density },
            FloatArray(px.size) { (captions[it]?.height ?: 0) / density },
            BooleanArray(px.size) { captions[it] != null }, screenWidth, screenHeight, look.iconSizeDp.toFloat())
        for (i in captions.indices) {
            captionX[i] = folderPlacement.x[i] * density - cx
            captionY[i] = folderPlacement.y[i] * density - cy
        }
        val size = glow.width / 2f
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = RadialGradient(size, size, size, skin.colors.glow.toInt(), skin.colors.glow.toInt() and 0xffffff, Shader.TileMode.CLAMP)
        }
        Canvas(glow).drawCircle(size, size, size, p)
        // Prime Android's lazy shader/bitmap handles outside the measured draw callback.
        val picture = Picture()
        val canvas = picture.beginRecording(1, 1)
        for (i in lines.indices) canvas.drawPath(paths[i / 2], lines[i])
        draw(canvas, 1f, 1f, 0)
        drawChrome(canvas, true, cx, cy)
        picture.endRecording()
        reuse?.rasters?.end()
        reuse?.labels?.end()
    }

    fun sameScene(other: SceneDrawing): Boolean = state.layer == other.state.layer &&
        state.arcs.firstOrNull()?.folderId == other.state.arcs.firstOrNull()?.folderId

    private fun text(value: String, token: TypeToken, scale: Float, widthDp: Float = Float.POSITIVE_INFINITY,
        textColor: Int = skin.colors.textPrimary.toInt(), typefaceOverride: Typeface? = null,
        halo: Boolean = false): Bitmap? {
        if (value.isEmpty()) return null
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = textColor
            textSize = token.sizeSp * density * scale
            letterSpacing = token.trackingSp / token.sizeSp
            typeface = typefaceOverride
                ?: Typeface.create(Typeface.create(spec.annotationFamily, Typeface.NORMAL), token.weight, false)
        }
        var label = value
        val padding = if (halo) Metrics.gridDp * density else 0f
        val limit = widthDp * density - padding * 2
        if (spec.label == LabelStyle.brackets && value.startsWith("[ ") && value.endsWith(" ]")) {
            var content = value.substring(2, value.length - 2)
            while (p.measureText(label) > limit && content.isNotEmpty()) {
                content = content.dropLast(1)
                label = "[ $content… ]"
            }
        } else while (p.measureText(label) > limit && label.length > 1) label = label.dropLast(2) + "…"
        val bitmap = Bitmap.createBitmap(ceil(p.measureText(label) + padding * 2).toInt().coerceAtLeast(1),
            ceil(p.descent() - p.ascent() + padding * 2).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (halo) {
            // Opaque glyph stroke, rasterised once. No blur or runtime text effects.
            p.style = Paint.Style.STROKE
            p.strokeWidth = padding
            p.strokeJoin = Paint.Join.ROUND
            p.color = skin.colors.surface.toInt()
            canvas.drawText(label, padding, padding - p.ascent(), p)
            p.style = Paint.Style.FILL
            p.color = textColor
        }
        canvas.drawText(label, padding, padding - p.ascent(), p)
        return bitmap
    }

    /** T71 clock face. Four text treatments share the skin token; digital is drawn as segments. */
    private fun clockText(value: String): Bitmap? {
        if (value.isEmpty() || look.clockStyle == ClockStyle.Hidden) return null
        if (look.clockStyle == ClockStyle.Digital) return digitalClock(value)
        val weight = LookRules.clockWeight(look.clockStyle, spec.clockWeight)
        val token = skin.typography.clock.copy(weight = weight)
        val mono = when {
            look.clockStyle == ClockStyle.Mono -> Typeface.create(Typeface.MONOSPACE, if (weight >= 600) Typeface.BOLD else Typeface.NORMAL)
            skin.style.clockFamily.isNotEmpty() -> Typeface.create(Typeface.create(spec.clockFamily, Typeface.NORMAL), weight, false)
            else -> null
        }
        return text(value, token, fontScale,
            textColor = (if (spec.header == HeaderStyle.ruled) skin.colors.accent else skin.colors.textPrimary).toInt(),
            typefaceOverride = mono)
    }

    /**
     * Seven-segment clock bitmap. Segment geometry is prepared here, never in the draw callback.
     * Unsupported text (a locale using a non-digit marker) falls back to the thin face so the clock
     * can never disappear silently.
     */
    private fun digitalClock(value: String): Bitmap? {
        if (!SevenSegment.supports(value)) return text(value, skin.typography.clock, fontScale)
        val height = Metrics.digitalClockHeightRatio * skin.typography.clock.sizeSp * density * fontScale
        val digitWidth = height * Metrics.digitalClockDigitWidthRatio
        val stroke = height * Metrics.digitalClockStrokeRatio
        val gap = height * Metrics.digitalClockGapRatio
        val colonWidth = height * Metrics.digitalClockColonWidthRatio
        var width = 0f
        value.forEachIndexed { index, ch ->
            if (index > 0) width += gap
            width += if (ch == ':') colonWidth else digitWidth
        }
        val bitmap = Bitmap.createBitmap(ceil(width).toInt().coerceAtLeast(1),
            ceil(height).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = skin.colors.textPrimary.toInt() }
        val segment = RectF()
        val radius = stroke / 2f
        var x = 0f
        value.forEachIndexed { index, ch ->
            if (index > 0) x += gap
            if (ch == ':') {
                val dot = stroke * Metrics.digitalClockDotRadiusRatio
                val cx = x + colonWidth / 2f
                canvas.drawCircle(cx, height * 0.30f, dot, p)
                canvas.drawCircle(cx, height * 0.70f, dot, p)
                x += colonWidth
            } else {
                SevenSegment.segmentsFor(ch)?.forEach { which ->
                    when (which) {
                        ClockSegment.Top -> segment.set(x + stroke / 2f, 0f, x + digitWidth - stroke / 2f, stroke)
                        ClockSegment.Middle -> segment.set(x + stroke / 2f, height / 2f - stroke / 2f,
                            x + digitWidth - stroke / 2f, height / 2f + stroke / 2f)
                        ClockSegment.Bottom -> segment.set(x + stroke / 2f, height - stroke,
                            x + digitWidth - stroke / 2f, height)
                        ClockSegment.UpperLeft -> segment.set(x, stroke / 2f, x + stroke, height / 2f)
                        ClockSegment.LowerLeft -> segment.set(x, height / 2f, x + stroke, height - stroke / 2f)
                        ClockSegment.UpperRight -> segment.set(x + digitWidth - stroke, stroke / 2f,
                            x + digitWidth, height / 2f)
                        ClockSegment.LowerRight -> segment.set(x + digitWidth - stroke, height / 2f,
                            x + digitWidth, height - stroke / 2f)
                    }
                    canvas.drawRoundRect(segment, radius, radius, p)
                }
                x += digitWidth
            }
        }
        return bitmap
    }

    private fun labelPill(value: String, maxWidthDp: Float): Bitmap? {
        if ((look.wallpaper != WallpaperStyle.System && look.wallpaper != WallpaperStyle.Image) && spec.label == LabelStyle.brackets) return text("[ $value ]", skin.typography.label, 1f, maxWidthDp)
        if ((look.wallpaper != WallpaperStyle.System && look.wallpaper != WallpaperStyle.Image) && spec.label == LabelStyle.plain) return text(value, skin.typography.label, 1f, maxWidthDp)
        if (spec.label == LabelStyle.mapLabel) return text(value, skin.typography.label, 1f, maxWidthDp, halo = true)
        return backedText(value, skin.typography.label, maxWidthDp)
    }

    private fun backedText(value: String, token: TypeToken, maxWidthDp: Float): Bitmap? {
        val horizontal = Metrics.labelPaddingHorizontalDp * density
        val vertical = Metrics.labelPaddingVerticalDp * density
        val label = text(if (spec.label == LabelStyle.brackets) "[ $value ]" else value, token, 1f,
            maxWidthDp - Metrics.labelPaddingHorizontalDp * 2) ?: return null
        val bitmap = Bitmap.createBitmap(ceil(label.width + horizontal * 2).toInt(),
            ceil(label.height + vertical * 2).toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (skin.colors.surface or 0xFF000000L).toInt()
            if ((look.wallpaper == WallpaperStyle.System || look.wallpaper == WallpaperStyle.Image) && spec.label != LabelStyle.pill) {
                alpha = (WallpaperReadability.safeAlpha(color, listOf(skin.colors.textPrimary.toInt()), Metrics.wallpaperLabelScrim) * 255).toInt()
            }
        }
        val radius = min(skin.shape.surfaceCornerDp * density, bitmap.height / 2f)
        canvas.drawRoundRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), radius, radius, p)
        p.alpha = 255
        canvas.drawBitmap(label, horizontal, vertical, p)
        return bitmap
    }

    private fun icon(slot: RingSlot): Bitmap {
        val source = baseIcon(slot)
        if (legacyIconFrame && spec.iconScale == 1f) return source
        val size = Metrics.iconBitmapPx.toFloat()
        val b = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val inset = size * (1f - spec.iconScale) / 2f
        c.drawBitmap(source, null, RectF(inset, inset, size - inset, size - inset), p)
        p.color = skin.colors.textPrimary.toInt(); p.style = Paint.Style.STROKE
        p.strokeWidth = spec.iconOutlineDp / look.iconSizeDp * size
        if (spec.iconOutlineDp > 0f && spec.iconFrame != IconFrame.none) {
            val edge = max(p.strokeWidth, size * Metrics.atlasRegistrationInset)
            when (spec.iconFrame) {
                IconFrame.disc, IconFrame.registration -> c.drawCircle(size / 2f, size / 2f, size / 2f - edge, p)
                IconFrame.roundedSquare -> c.drawRoundRect(edge, edge, size - edge, size - edge, size / 4f, size / 4f, p)
                IconFrame.brackets -> {
                    val len = size * Metrics.atlasRegistrationLength
                    c.drawLine(edge + len, edge, edge, edge, p)
                    c.drawLine(edge, edge, edge, size - edge, p)
                    c.drawLine(edge, size - edge, edge + len, size - edge, p)
                    c.drawLine(size - edge - len, edge, size - edge, edge, p)
                    c.drawLine(size - edge, edge, size - edge, size - edge, p)
                    c.drawLine(size - edge, size - edge, size - edge - len, size - edge, p)
                }
                IconFrame.none -> Unit
            }
            if (spec.iconFrame == IconFrame.registration) {
                val r = size / 2f
                val len = size * Metrics.atlasRegistrationLength
                c.drawLine(r, 0f, r, len, p); c.drawLine(r, size - len, r, size, p)
                c.drawLine(0f, r, len, r, p); c.drawLine(size - len, r, size, r, p)
            }
        }
        return b
    }

    private fun baseIcon(slot: RingSlot): Bitmap {
        val source = slot.bitmap
        val size = Metrics.iconBitmapPx
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val path = Path()
        val shape = when (spec.iconFrame) {
            IconFrame.disc -> look.iconShape
            IconFrame.registration -> IconShape.Circle
            IconFrame.roundedSquare -> IconShape.RoundedSquare
            IconFrame.none, IconFrame.brackets -> IconShape.None
        }
        val masked = buildMask(path, shape, size.toFloat())
        if (masked) c.clipPath(path)
        p.color = liftedSurface(skin)
        if (masked) c.drawPath(path, p)
        else if (source == null) c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
        if (source != null) {
            if (slot.slot !is Slot.Folder) p.colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(skin.icons.saturation) })
            c.drawBitmap(source, null, RectF(0f, 0f, size.toFloat(), size.toFloat()), p)
        } else {
            if (!masked) { p.color = liftedSurface(skin); c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p) }
            p.color = skin.colors.accent.toInt()
            if (slot.slot == Slot.Action(ActionKind.Settings)) {
                val gear = Path()
                val center = size / 2f
                val outer = size * Metrics.gearRadiusRatio
                for (i in 0 until Metrics.gearTeeth * 4) {
                    val angle = i * Math.PI * 2 / (Metrics.gearTeeth * 4)
                    val radius = outer * if (i % 4 < 2) 1f else Metrics.gearInnerRatio
                    val x = center + cos(angle).toFloat() * radius
                    val y = center + sin(angle).toFloat() * radius
                    if (i == 0) gear.moveTo(x, y) else gear.lineTo(x, y)
                }
                gear.close()
                gear.addCircle(center, center, outer * Metrics.gearHoleRatio, Path.Direction.CCW)
                gear.fillType = Path.FillType.EVEN_ODD
                c.drawPath(gear, p)
            } else if (slot.slot !is Slot.Folder) {
                p.textAlign = Paint.Align.CENTER
                val token = skin.typography.search
                p.textSize = token.sizeSp / Metrics.iconDp * size
                p.typeface = Typeface.create(skin.typography.fallbackFamily, Typeface.NORMAL)
                val glyph = if (slot.slot is Slot.Action) "⋮" else slot.label.take(1)
                c.drawText(glyph, size / 2f, size / 2f - (p.ascent() + p.descent()) / 2f, p)
            }
        }
        p.colorFilter = null
        if (masked && legacyIconFrame) {
            p.style = Paint.Style.STROKE
            val arc = state.arcs.indexOfFirst { it.id == slot.position.arcId }
            p.strokeWidth = Metrics.discRimDp / look.iconSizeDp * size / depthScale[arc]
            p.color = skin.colors.lineActive.toInt()
            c.drawPath(path, p)
        }
        return b
    }

    /**
     * Builds the icon mask. [IconShape.None] returns false and leaves the raw bitmap untouched;
     * the other shapes fill [out] in a square of [size] px. Called only while preparing a scene.
     */
    private fun buildMask(out: Path, shape: IconShape, size: Float): Boolean {
        out.rewind()
        val centre = size / 2f
        when (shape) {
            IconShape.None -> return false
            IconShape.Circle -> out.addCircle(centre, centre, centre, Path.Direction.CW)
            IconShape.RoundedSquare -> {
                val radius = size * LookRules.RoundedSquareCornerFraction
                out.addRoundRect(0f, 0f, size, size, radius, radius, Path.Direction.CW)
            }
            IconShape.Squircle -> {
                val exponent = LookRules.SquircleExponent
                val power = 2f / exponent
                val steps = 96
                for (i in 0..steps) {
                    val angle = i * Math.PI * 2 / steps
                    val cosA = cos(angle).toFloat()
                    val sinA = sin(angle).toFloat()
                    val x = centre + centre * Math.signum(cosA) * abs(cosA).pow(power)
                    val y = centre + centre * Math.signum(sinA) * abs(sinA).pow(power)
                    if (i == 0) out.moveTo(x, y) else out.lineTo(x, y)
                }
                out.close()
            }
        }
        return true
    }

    /**
     * Live angle of slot `i` (degrees). The item sits at its folded window position, so an item
     * that wraps around the ring re-enters at the opposite end of the window instead of being
     * drawn outside the arc, and every slot stays filled while the ring holds enough items (T37).
     */
    private fun slotAngleDeg(i: Int, offsets: FloatArray): Float {
        val a = arcIndex[i]
        return RingAim.normalizeDegrees(RingRotation.windowAngleDeg(arcAngle0[a], arcPitch[a],
            itemIndex[i], offsets[a], arcCount[a], arcCapacity[a]))
    }

    /**
     * The name pill's top-left (dp) for slot [i] at its live centre: outward from the hub, flipped
     * inward when it would cross a keep-out, then clamped. Writes [labelLeftDp]/[labelTopDp] so the
     * draw loop allocates nothing (same rule as [RingLabels.place]).
     */
    private fun placeLabel(i: Int, label: Bitmap, rotations: FloatArray) {
        val lxDp = (cx + slotX(i, rotations)) / density
        val lyDp = (cy + slotY(i, rotations)) / density
        val wDp = label.width / density
        val hDp = label.height / density
        val offset = look.iconSizeDp / 2f + Metrics.labelGapDp
        val left = Metrics.keepOutLeftDp
        val right = screenWidth - Metrics.keepOutRightDp
        val top = Metrics.keepOutTopDp
        val bottom = screenHeight - Metrics.keepOutBottomDp
        val ddx = lxDp - cx / density
        val ddy = lyDp - cy / density
        val horizontal = abs(ddx) >= abs(ddy)
        val outward = if ((if (horizontal) ddx else ddy) <= 0f) -1f else 1f
        var side = outward
        var lx = if (horizontal) (if (side < 0f) lxDp - offset - wDp else lxDp + offset) else lxDp - wDp / 2f
        var ly = if (horizontal) lyDp - hDp / 2f else (if (side < 0f) lyDp - offset - hDp else lyDp + offset)
        if (lx < left || lx + wDp > right || ly < top || ly + hDp > bottom) {
            side = -outward
            lx = if (horizontal) (if (side < 0f) lxDp - offset - wDp else lxDp + offset) else lxDp - wDp / 2f
            ly = if (horizontal) lyDp - hDp / 2f else (if (side < 0f) lyDp - offset - hDp else lyDp + offset)
        }
        labelLeftDp = lx.coerceIn(left, max(left, right - wDp))
        labelTopDp = ly.coerceIn(top, max(top, bottom - hDp))
    }

    private fun slotX(i: Int, offsets: FloatArray): Float {
        val a = arcIndex[i]
        val angle = Math.toRadians(slotAngleDeg(i, offsets).toDouble())
        return cos(angle).toFloat() * arcRadiusPx[a]
    }

    private fun slotY(i: Int, offsets: FloatArray): Float {
        val a = arcIndex[i]
        val angle = Math.toRadians(slotAngleDeg(i, offsets).toDouble())
        return sin(angle).toFloat() * arcRadiusPx[a]
    }

    fun hit(x: Float, y: Float, offsets: FloatArray): Int {
        val dx = x * density - cx
        val dy = y * density - cy
        val radius = hypot(dx, dy)
        val target = Metrics.touchTargetDp * density / 2f
        var best = -1
        var distance = target * target
        var i = 0
        while (i < px.size) {
            val a = arcIndex[i]
            // Off-window ring items (A2 holds more than its visible slots) are not tappable.
            val window = RingRotation.windowPosition(itemIndex[i], offsets[a], arcCount[a], arcCapacity[a])
            if (RingRotation.edgeAlpha(window, arcCapacity[a]) <= 0f) { i++; continue }
            if (abs(radius - state.arcs[a].radiusDp * density) <= target) {
                val x1 = dx - slotX(i, offsets); val y1 = dy - slotY(i, offsets)
                val d = x1 * x1 + y1 * y1
                if (d <= distance) { best = i; distance = d }
            }
            i++
        }
        return best
    }

    /** The rotating arc whose band contains (x, y), or -1 when no rotating arc is under the finger. */
    fun rotationArc(x: Float, y: Float): Int {
        val radius = hypot(x * density - cx, y * density - cy) / density
        for (a in state.arcs.indices) {
            if (!arcRotates[a]) continue
            if (abs(radius - state.arcs[a].radiusDp) <= Metrics.rotateBandDp) return a
        }
        return -1
    }

    /** Tangential slot steps (in dp movement terms) for a drag at (x, y) moving by (dx, dy). */
    fun rotateSlots(arcIndex: Int, x: Float, y: Float, dx: Float, dy: Float): Float {
        val arc = state.arcs[arcIndex]
        val px = x * density - cx
        val py = y * density - cy
        val theta = atan2(py.toDouble(), px.toDouble()).toFloat()
        val tangent = -sin(theta) * (dx * density) + cos(theta) * (dy * density)
        val pitchPx = arc.radiusDp * density * RingRotation.pitchDeg(arc) * PI.toFloat() / 180f
        return if (pitchPx != 0f) tangent / pitchPx else 0f
    }

    fun position(x: Float, y: Float): SlotPosition? {
        val target = Metrics.touchTargetDp / 2f
        var best: SlotPosition? = null
        var distance = target * target
        for (pos in positions) {
            val arc = state.arcs.first { it.id == pos.arcId }
            val angle = Math.toRadians(arc.slotAngle(pos.index).toDouble())
            val dx = x - cx / density - cos(angle).toFloat() * arc.radiusDp
            val dy = y - cy / density - sin(angle).toFloat() * arc.radiusDp
            val d = dx * dx + dy * dy
            if (d <= distance) { best = pos; distance = d }
        }
        return best
    }

    fun hubReach(x: Float, y: Float): Float = hypot(x * density - cx, y * density - cy) / density

    fun edgeZone(x: Float, y: Float): Int {
        val reach = hubReach(x, y)
        val band = RingAim.band(state.arcs.size, reach)
        if (band < 0) return 0
        if (state.layer != Layer.Home && RingAim.isBack(x - cx / density, y - cy / density)) return 0
        val angle = Math.toDegrees(atan2((y * density - cy).toDouble(), (x * density - cx).toDouble())).toFloat()
        val arc = state.arcs[band]
        return EdgeHold.zone(band, state.arcs.size, reach, angle, arc.slotAngle(0),
            arc.slotAngle(arc.slotCapacity - 1), arcRotates[band])
    }

    fun hubHit(x: Float, y: Float): Boolean = hypot(x * density - cx, y * density - cy) <= Metrics.hubTouchDp * density / 2f

    /** Circular search button hit test in dp; matches [RingSearchButtonPlacement]'s touch box. */
    fun searchButtonHit(x: Float, y: Float): Boolean =
        hypot(x - searchButton.centerX, y - searchButton.centerY) <= searchButton.radius

    /**
     * Joystick aim over the scene's populated slots (x, y in dp). Reach picks the arc band, the
     * direction projected to that radius picks the nearest visible centre, and `previous` (a slot
     * list index) enables 15 %-of-pitch hysteresis. Rotated positions are read from `offsets`.
     */
    fun aim(x: Float, y: Float, previous: Int, offsets: FloatArray): Int {
        val dx = x * density - cx
        val dy = y * density - cy
        val reach = hypot(dx, dy) / density
        if (state.layer != Layer.Home && RingAim.isBack(dx / density, dy / density)) return RingAim.BACK
        val band = RingAim.band(state.arcs.size, reach)
        if (band < 0) return -1
        val arc = state.arcs[band]
        val angle = RingAim.normalizeDegrees(Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat())
        val pitch = RingAim.pitchDegrees(arc)
        var best = -1
        var bestDistance = Float.POSITIVE_INFINITY
        var bestAngle = angle
        var i = 0
        while (i < px.size) {
            if (state.slots[i].position.arcId == arc.id) {
                // Only items inside the window are aimable.
                val a = arcIndex[i]
                val p = RingRotation.windowPosition(itemIndex[i], offsets[a], arcCount[a], arcCapacity[a])
                if (RingRotation.edgeAlpha(p, arcCapacity[a]) <= 0f) { i++; continue }
                val slotAngle = slotAngleDeg(i, offsets)
                val d = RingAim.distance(angle, slotAngle, arc.radiusDp)
                if (d < bestDistance) { best = i; bestDistance = d; bestAngle = slotAngle }
            }
            i++
        }
        if (previous in px.indices && state.slots[previous].position.arcId == arc.id &&
            RingRotation.edgeAlpha(RingRotation.windowPosition(itemIndex[previous], offsets[band], arcCount[band], arcCapacity[band]), arcCapacity[band]) > 0f) {
            val previousAngle = slotAngleDeg(previous, offsets)
            if (RingAim.circularDistance(angle, previousAngle) <=
                RingAim.circularDistance(angle, bestAngle) + Metrics.aimHysteresisFraction * pitch) {
                best = previous
            }
        }
        return best
    }


    fun drawBackground(canvas: Canvas) = decoration.drawBackground(canvas)

    fun draw(canvas: Canvas, alpha: Float, scale: Float, focus: Int, showFocus: Boolean = true,
        aiming: Boolean = false, editing: Boolean = false, dragged: Int = -1,
        dragX: Float = 0f, dragY: Float = 0f, drop: SlotPosition? = null, allowed: Boolean = false,
        sweep: Float = 1f, slotsAlpha: Float = 1f, lineAlpha: Float = 1f, pulse: Float = 0f, omitted: Int = -1, expansion: Float = 1f, touching: Boolean = false,
        swap: Float = 1f, rotations: FloatArray = settled, showCaptions: Boolean = true) {
        if (alpha <= 0f) return
        canvas.save(); canvas.translate(cx, cy); canvas.scale(scale * (Metrics.collapsedScale + (1f - Metrics.collapsedScale) * sweep),
            scale * (Metrics.collapsedScale + (1f - Metrics.collapsedScale) * sweep))
        var i = 0
        val selected = if (!showFocus || focus == RingAim.BACK) -1 else if (focus in icons.indices) focus else accessibilityFocus
        val directionX = if (aiming && selected >= 0) slotX(selected, rotations) else if (touching) dragX - cx else 0f
        val directionY = if (aiming && selected >= 0) slotY(selected, rotations) else if (touching) dragY - cy else 0f
        val directionLength = hypot(directionX, directionY)
        val shiftX = if (directionLength > 0f) directionX / directionLength * skin.parallaxDp * density else 0f
        val shiftY = if (directionLength > 0f) directionY / directionLength * skin.parallaxDp * density else 0f
        val active = focusedArc(arcIndex, selected)
        while (i < paths.size) {
            val p = lines[i * 2 + if (active == i) 1 else 0]
            p.alpha = (255 * alpha * chromeDim * lineAlpha / (1f + skin.breatheAmount)).toInt().coerceIn(0, 255)
            // Radial reveal: the inner arc finishes first, followed by the outer arcs.
            val reveal = (sweep * paths.size - i).coerceIn(0f, 1f)
            canvas.save()
            canvas.translate(shiftX * i / 3f, shiftY * i / 3f)
            if (reveal >= 1f) canvas.drawPath(paths[i], p)
            else if (reveal > 0f) {
                wakePaths[i].rewind()
                measures[i].getSegment(0f, measures[i].length * reveal, wakePaths[i], true)
                canvas.drawPath(wakePaths[i], p)
            }
            canvas.restore()
            i++
        }
        decoration.drawRails(canvas, alpha * lineAlpha, railPaint, bearingPaint, bearingVisible)
        if (editing) {
            editPaint.style = Paint.Style.FILL
            editPaint.color = skin.colors.textSecondary.toInt()
            editPaint.alpha = (255 * alpha).toInt()
            i = 0
            while (i < emptyX.size) {
                if (state.layer == Layer.Home) {
                    val radius = Metrics.editPlusRadiusDp * density
                    editPaint.strokeWidth = Metrics.editPlusStrokeDp * density
                    canvas.drawLine(emptyX[i] - radius, emptyY[i], emptyX[i] + radius, emptyY[i], editPaint)
                    canvas.drawLine(emptyX[i], emptyY[i] - radius, emptyX[i], emptyY[i] + radius, editPaint)
                } else canvas.drawCircle(emptyX[i], emptyY[i], Metrics.editDotRadiusDp * density, editPaint)
                i++
            }
            if (drop != null) {
                // Indexed loop: no iterator, geometry object or paint is allocated in draw.
                i = 0
                while (i < state.arcs.size) {
                    val arc = state.arcs[i]
                    if (arc.id == drop.arcId && drop.index in 0 until arc.slotCapacity) {
                        val angle = Math.toRadians(arc.slotAngle(drop.index).toDouble())
                        val x = cos(angle).toFloat() * arc.radiusDp * density
                        val y = sin(angle).toFloat() * arc.radiusDp * density
                        editPaint.style = Paint.Style.STROKE
                        editPaint.color = (if (allowed) skin.colors.accent else skin.colors.danger).toInt()
                        editPaint.alpha = (255 * alpha).toInt()
                        canvas.drawCircle(x, y, Metrics.touchTargetDp * density / 2f, editPaint)
                    }
                    i++
                }
            }
        }
        if (aiming && selected in icons.indices) {
            aimLinePaint.alpha = (255 * alpha).toInt()
            canvas.drawLine(0f, 0f, slotX(selected, rotations) + shiftX * arcIndex[selected] / 3f, slotY(selected, rotations) + shiftY * arcIndex[selected] / 3f, aimLinePaint)
            if (spec.aim == AimStyle.coordinates) {
                paint.alpha = (255 * alpha).toInt()
                val x = -coordinateGlyphs[0].width * 9f - (Metrics.hubRadiusDp + Metrics.gridDp) * density
                val y = -Metrics.hubRadiusDp * density
                drawCoordinate(canvas, ((cx + slotX(selected, rotations) + shiftX * arcIndex[selected] / 3f) / density).toInt(), 10, x, y)
                drawCoordinate(canvas, ((cy + slotY(selected, rotations) + shiftY * arcIndex[selected] / 3f) / density).toInt(), 11, x + coordinateGlyphs[0].width * 5f, y)
            }
            val distance = distanceLabels[selected]
            if (distance != null) {
                paint.alpha = (255 * alpha).toInt()
                canvas.drawBitmap(distance, -distance.width.toFloat() - (Metrics.hubRadiusDp + Metrics.gridDp) * density,
                    -Metrics.hubRadiusDp * density, paint)
            }
            rect.set(slotX(selected, rotations) - glowRadius, slotY(selected, rotations) - glowRadius,
                slotX(selected, rotations) + glowRadius, slotY(selected, rotations) + glowRadius)
            paint.alpha = (255 * alpha).toInt()
            canvas.drawBitmap(glow, null, rect, paint)
        }
        i = 0
        while (i < icons.size) {
            val a = arcIndex[i]
            val window = RingRotation.windowPosition(itemIndex[i], rotations[a], arcCount[a], arcCapacity[a])
            val edge = RingRotation.edgeAlpha(window, arcCapacity[a])
            if (edge <= 0f) { i++; continue }
            val liveX = slotX(i, rotations)
            val liveY = slotY(i, rotations)
            if (pulseSlots[i] && pulse > 0f) {
                pulsePaint.alpha = (255 * alpha * slotsAlpha * dim[i] * pulse).toInt()
                canvas.drawCircle(liveX, liveY, half * (1f + (Metrics.pulseRadiusScale - 1f) * pulse), pulsePaint)
            }
            // Usage-layer swap (T35): one soft accent swell on the newcomer, then a cross-fade in.
            if (swapFade[i] && swap < 1f) {
                paint.alpha = (255 * alpha * slotsAlpha * dim[i] * sin(PI * swap).toFloat().coerceIn(0f, 1f)).toInt()
                rect.set(liveX - glowRadius, liveY - glowRadius, liveX + glowRadius, liveY + glowRadius)
                canvas.drawBitmap(glow, null, rect, paint)
            }
            paint.alpha = (255 * alpha * slotsAlpha * dim[i] * depthAlpha[a] * edge
                * (if (swapFade[i]) swap else 1f)
                * if (selected == i) skin.icons.alphaFocused else skin.icons.alphaUnfocused).toInt()
            if (i != dragged && i != omitted) {
                val spring = FolderTransition.slot(expansion, i)
                val depth = depthScale[a] * RingRotation.edgeScale(window, arcCapacity[a])
                val x = liveX * spring + shiftX * a / 3f
                val y = liveY * spring + shiftY * a / 3f
                val radius = half * spring * depth
                val shadowRadius = radius + Metrics.contactShadowDp * density
                rect.set(x - shadowRadius, y - shadowRadius, x + shadowRadius, y + shadowRadius)
                canvas.drawBitmap(contact, null, rect, paint)
                rect.set(x - radius, y - radius, x + radius, y + radius)
                canvas.drawBitmap(icons[i]!!, null, rect, paint)
                if (state.slots[i].unreadCount > 0) {
                    val text = badgeText[i]
                    val badgeX = x + radius * Metrics.badgeCornerFraction
                    val badgeY = y - radius * Metrics.badgeCornerFraction
                    val badgeRadius = (if (text == null) Metrics.badgeDotRadiusDp else Metrics.badgeCountRadiusDp) * density
                    badgePaint.alpha = paint.alpha
                    canvas.drawCircle(badgeX, badgeY, badgeRadius, badgePaint)
                    if (text != null) {
                        badgeTextPaint.alpha = paint.alpha
                        canvas.drawText(text, badgeX, badgeY + badgeBaseline, badgeTextPaint)
                    }
                }
                if (workSlots[i]) {
                    // T75: a small briefcase on the lower-right (the unread dot stays upper-right).
                    // Geometry is derived from tokens; the loop constructs no objects.
                    val workX = x + radius * Metrics.badgeCornerFraction
                    val workY = y + radius * Metrics.badgeCornerFraction
                    val disc = Metrics.workBadgeRadiusDp * density
                    workPaint.alpha = paint.alpha
                    canvas.drawCircle(workX, workY, disc, workPaint)
                    val bodyW = Metrics.workBadgeBodyWidthDp * density
                    val bodyH = Metrics.workBadgeBodyHeightDp * density
                    val handleW = Metrics.workBadgeHandleWidthDp * density
                    val handleH = Metrics.workBadgeHandleHeightDp * density
                    val lift = Metrics.workBadgeHandleLiftDp * density
                    val corner = lift / 2f
                    val bodyTop = workY - bodyH / 2f + corner
                    workGlyphPaint.alpha = paint.alpha
                    canvas.drawRoundRect(workX - bodyW / 2f, bodyTop, workX + bodyW / 2f,
                        bodyTop + bodyH, corner, corner, workGlyphPaint)
                    val handleTop = bodyTop - handleH + corner
                    canvas.drawRoundRect(workX - handleW / 2f, handleTop, workX + handleW / 2f,
                        handleTop + handleH, corner, corner, workGlyphPaint)
                }
            }
            i++
        }
        if (showCaptions) {
            i = 0
            while (i < captions.size) {
                val caption = captions[i]
                if (caption != null && i != selected && i != omitted) {
                    val a = arcIndex[i]
                    val edge = RingRotation.edgeAlpha(RingRotation.windowPosition(itemIndex[i], rotations[a], arcCount[a], arcCapacity[a]), arcCapacity[a])
                    if (edge > 0f) {
                        var x = captionX[i]
                        var y = captionY[i]
                        if (arcRotates[a]) {
                            val margin = Metrics.folderLabelScreenMarginDp * density
                            val offset = (look.iconSizeDp / 2f + Metrics.folderLabelGapDp) * density
                            val centerY = cy + slotY(i, rotations)
                            x = (cx + slotX(i, rotations) - caption.width / 2f)
                                .coerceIn(margin, max(margin, screenWidth * density - margin - caption.width)) - cx
                            val below = centerY + offset
                            y = (if (below + caption.height <= screenHeight * density - margin) below
                                else centerY - offset - caption.height)
                                .coerceIn(margin, max(margin, screenHeight * density - margin - caption.height)) - cy
                        }
                        paint.alpha = (255 * alpha * slotsAlpha * dim[i] * edge).toInt()
                        canvas.drawBitmap(caption, x + shiftX * a / 3f, y + shiftY * a / 3f, paint)
                    }
                }
                i++
            }
        }
        if (dragged in icons.indices) {
            val x = dragX - cx
            val y = dragY - cy - Metrics.editLiftDp * density
            paint.alpha = (255 * alpha).toInt()
            rect.set(x - glowRadius, y - glowRadius, x + glowRadius, y + glowRadius)
            canvas.drawBitmap(glow, null, rect, paint)
            val liftedHalf = half * Metrics.editLiftScale
            rect.set(x - liftedHalf, y - liftedHalf, x + liftedHalf, y + liftedHalf)
            canvas.drawBitmap(icons[dragged]!!, null, rect, paint)
        }
        // The focused label draws last so neighbouring icons cannot cover its pill. Its position
        // follows the live (possibly rotated) slot centre; the placement is inlined to stay
        // allocation-free in the draw loop.
        // OPUS8 (T133): in All apps and folders, the front icon of each arc shows its name while
        // nothing is touched or aimed (the touched name below takes over then). A name that would
        // cover another arc's icon or another front name is skipped. Indexed loops, no allocation.
        if (frontNames && selected < 0 && dragged < 0 && !editing) {
            var placed = 0
            var a = 0
            while (a < arcCapacity.size) {
                val item = FrontNames.frontItem(arcRotates[a], rotations[a], arcCount[a], arcLowestItem[a])
                val bySlot = arcItemSlot[a]
                val s = if (item in bySlot.indices) bySlot[item] else -1
                val label = if (s >= 0 && s != omitted) labels[s] else null
                val fade = if (s >= 0 && arcRotates[a])
                    FrontNames.fade(RingRotation.windowPosition(itemIndex[s], rotations[a], arcCount[a], arcCapacity[a])) else 1f
                if (label != null && fade > 0f) {
                    placeLabel(s, label, rotations)
                    val wDp = label.width / density
                    val hDp = label.height / density
                    val discDp = look.iconSizeDp / 2f
                    var clear = true
                    var j = 0
                    while (clear && j < icons.size) {
                        val aj = arcIndex[j]
                        if (aj != a && j != omitted && RingRotation.edgeAlpha(RingRotation.windowPosition(
                                itemIndex[j], rotations[aj], arcCount[aj], arcCapacity[aj]), arcCapacity[aj]) > 0f &&
                            FrontNames.rectHitsDisc(labelLeftDp, labelTopDp, wDp, hDp,
                                (cx + slotX(j, rotations)) / density, (cy + slotY(j, rotations)) / density, discDp)) clear = false
                        j++
                    }
                    var k = 0
                    while (clear && k < placed) {
                        if (FrontNames.rectsOverlap(labelLeftDp, labelTopDp, wDp, hDp, frontRects[k * 4],
                                frontRects[k * 4 + 1], frontRects[k * 4 + 2], frontRects[k * 4 + 3])) clear = false
                        k++
                    }
                    if (clear) {
                        frontRects[placed * 4] = labelLeftDp
                        frontRects[placed * 4 + 1] = labelTopDp
                        frontRects[placed * 4 + 2] = wDp
                        frontRects[placed * 4 + 3] = hDp
                        placed++
                        paint.alpha = (255 * alpha * slotsAlpha * dim[s] * fade).toInt()
                        canvas.drawBitmap(label, labelLeftDp * density - cx, labelTopDp * density - cy, paint)
                    }
                }
                a++
            }
        }
        if (selected in labels.indices && selected != omitted) {
            val label = labels[selected]
            if (label != null) {
                placeLabel(selected, label, rotations)
                paint.alpha = (255 * alpha * slotsAlpha).toInt()
                canvas.drawBitmap(label, labelLeftDp * density - cx, labelTopDp * density - cy, paint)
            }
        }
        if (aiming && selected in icons.indices) {
            aimPaint.alpha = (255 * alpha).toInt()
            val x = slotX(selected, rotations) + shiftX * arcIndex[selected] / 3f
            val y = slotY(selected, rotations) + shiftY * arcIndex[selected] / 3f
            val r = half * depthScale[arcIndex[selected]] + Metrics.aimRingGapDp * density
            if (spec.iconFrame == IconFrame.brackets) {
                val arm = r * Metrics.atlasRegistrationLength
                canvas.drawLine(x - r + arm, y - r, x - r, y - r, aimPaint)
                canvas.drawLine(x - r, y - r, x - r, y + r, aimPaint)
                canvas.drawLine(x - r, y + r, x - r + arm, y + r, aimPaint)
                canvas.drawLine(x + r - arm, y - r, x + r, y - r, aimPaint)
                canvas.drawLine(x + r, y - r, x + r, y + r, aimPaint)
                canvas.drawLine(x + r, y + r, x + r - arm, y + r, aimPaint)
            } else canvas.drawCircle(x, y, r, aimPaint)
        }
        canvas.restore()
    }

    fun folderIndex(id: FolderId?): Int = state.slots.indexOfFirst { (it.slot as? Slot.Folder)?.id == id }

    fun drawShared(canvas: Canvas, index: Int, progress: Float) {
        if (index !in icons.indices) return
        val travel = FolderTransition.emphasis(progress)
        val x = cx + px[index] * (1f - travel)
        val y = cy + py[index] * (1f - travel)
        val radius = half * (1f + Metrics.sharedDiscSwell * sin(Math.PI * progress).toFloat())
        paint.alpha = 255
        rect.set(x - glowRadius, y - glowRadius, x + glowRadius, y + glowRadius)
        canvas.drawBitmap(glow, null, rect, paint)
        rect.set(x - radius, y - radius, x + radius, y + radius)
        canvas.drawBitmap(icons[index]!!, null, rect, paint)
    }

    fun drawChrome(canvas: Canvas, touching: Boolean, x: Float, y: Float, back: Boolean = false,
        hint: Float = 0f, showHint: Boolean = false) {
        paint.alpha = (255 * chromeDim).toInt()
        if (clock != null) {
            val top = Metrics.keepOutTopDp * density
            canvas.drawBitmap(clock, Metrics.textGutterDp * density, top, paint)
            if (clockSubline != null) {
                canvas.drawBitmap(clockSubline, Metrics.textGutterDp * density,
                    top + clock.height + Metrics.clockSublineGapDp * density, paint)
            }
        }
        if (title != null) canvas.drawBitmap(title, Metrics.textGutterDp * density, Metrics.keepOutTopDp * density, paint)
        if (empty != null) canvas.drawBitmap(empty, Metrics.textGutterDp * density,
            (Metrics.keepOutTopDp + skin.typography.title.sizeSp + Metrics.gridDp * 4) * density, paint)
        if (touching) {
            rect.set(x - glowRadius, y - glowRadius, x + glowRadius, y + glowRadius)
            canvas.drawBitmap(glow, null, rect, paint)
        }
        if (back) {
            aimPaint.alpha = 255
            val bx = cx + Metrics.backOffsetDp * density
            val r = Metrics.backChevronDp * density
            rect.set(bx - glowRadius, cy - glowRadius, bx + glowRadius, cy + glowRadius)
            canvas.drawBitmap(glow, null, rect, paint)
            canvas.drawLine(bx + r / 2f, cy - r, bx - r / 2f, cy, aimPaint)
            canvas.drawLine(bx - r / 2f, cy, bx + r / 2f, cy + r, aimPaint)
        }
        hub.alpha = (255 * chromeDim).toInt()
        canvas.drawCircle(cx, cy, Metrics.hubRadiusDp * density, hub)
        decoration.drawHub(canvas, cx, cy)
        if (clock != null && spec.header == HeaderStyle.ruled) {
            canvas.drawLine(Metrics.textGutterDp * density,
                (Metrics.keepOutTopDp + skin.typography.clock.sizeSp + Metrics.gridDp * 4) * density,
                (Metrics.textGutterDp + Metrics.gridDp * 6) * density,
                (Metrics.keepOutTopDp + skin.typography.clock.sizeSp + Metrics.gridDp * 4) * density, hub)
        }
        // Mode glyph: a quiet single dot on Home, a 3×3 grid on All-apps, nothing inside a folder.
        if (state.layer == Layer.Home || state.layer == Layer.AllApps) {
            val glyphX = cx - Metrics.modeGlyphOffsetDp * density
            val glyphY = cy - Metrics.modeGlyphOffsetDp * density
            val dotR = Metrics.modeGlyphDotDp * density / 2f
            val pitch = Metrics.modeGlyphPitchDp * density
            modeGlyphPaint.alpha = (255 * chromeDim).toInt()
            if (state.layer == Layer.AllApps) {
                for (r in 0..2) for (c in 0..2) {
                    canvas.drawCircle(glyphX + (c - 1) * pitch, glyphY + (r - 1) * pitch, dotR, modeGlyphPaint)
                }
            } else {
                canvas.drawCircle(glyphX, glyphY, dotR, modeGlyphPaint)
            }
        }
        // Circular search button (T35): hub-style ring with a magnifier glyph, bottom-left.
        val bx = searchButton.centerX * density
        val by = searchButton.centerY * density
        val bR = searchButton.radius * density
        if (hint > 0f) {
            rect.set(bx - glowRadius, by - glowRadius, bx + glowRadius, by + glowRadius)
            paint.alpha = (255 * chromeDim * hint).toInt()
            canvas.drawBitmap(glow, null, rect, paint)
        }
        hub.alpha = (255 * chromeDim).toInt()
        canvas.drawCircle(bx, by, bR, hub)
        searchGlyphPaint.alpha = (255 * chromeDim).toInt()
        val gr = Metrics.searchButtonGlyphRadiusDp * density
        val gcx = bx - gr * 0.28f
        val gcy = by - gr * 0.28f
        canvas.drawCircle(gcx, gcy, gr, searchGlyphPaint)
        val handleLength = Metrics.searchButtonGlyphHandleDp * density * 0.7f
        canvas.drawLine(gcx + gr * 0.7f, gcy + gr * 0.7f, gcx + gr * 0.7f + handleLength, gcy + gr * 0.7f + handleLength, searchGlyphPaint)
        if (showHint && searchCaption != null) {
            paint.alpha = (255 * chromeDim).toInt()
            canvas.drawBitmap(searchCaption, bx + bR + Metrics.searchButtonCaptionGapDp * density,
                by - searchCaption.height / 2f, paint)
        }
    }
}

@Suppress("DEPRECATION")
private class DrawCounter {
    var supported = false
    var samples = 0L
    var objects = 0L
    var maximum = 0
    private var frames = 0
    private var calibration: ByteArray? = null
    fun enable() {
        Debug.startAllocCounting()
        val before = Debug.getThreadAllocCount()
        calibration = ByteArray(1024)
        supported = Debug.getThreadAllocCount() > before
    }
    fun before(): Int = if (supported) Debug.getThreadAllocCount() else 0
    fun after(before: Int) {
        frames++
        if (!supported || frames < 120) return
        val allocated = Debug.getThreadAllocCount() - before
        samples++; objects += allocated
        maximum = max(maximum, allocated)
    }
}

/** Mix the existing surface toward lineActive; no new palette colour. Worker preparation only. */
private fun liftedSurface(skin: Skin): Int {
    fun channel(shift: Int): Int {
        val base = (skin.colors.surface shr shift and 255).toInt()
        val light = (skin.colors.lineActive shr shift and 255).toInt()
        return (base + (light - base) * Metrics.discLiftFraction).toInt()
    }
    return (255 shl 24) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
}

/** One active fling: an exact critically damped spring from a recorded start toward [target]. */
private class RotationFling(val offset0: Float, val velocity0: Float, val target: Float,
    val omega: Float, val start: Long)

/**
 * Per-arc live rotation state (T29). Offsets are kept unwrapped while animating and wrapped into
 * `0..count-1` by the scene builders/persistence; item mapping always reads through [RingRotation].
 * [aligned] mirrors the current scene's arcs for the allocation-free draw loop; the frame clock
 * advances active flings and reports each settle so the host can persist it.
 */
private class RotationRuntime {
    private val offsets = HashMap<String, Float>()
    private val flings = HashMap<String, RotationFling>()
    private var indexById = HashMap<String, Int>()
    var aligned = FloatArray(0)
        private set

    fun seed(arcs: List<ArcSpec>, settled: Map<String, Float>) {
        indexById = HashMap(arcs.size * 2)
        aligned = FloatArray(arcs.size)
        for (a in arcs.indices) {
            val id = arcs[a].id
            indexById[id] = a
            val initial = offsets[id] ?: (settled[id] ?: 0f)
            offsets[id] = initial
            aligned[a] = initial
        }
    }

    fun offset(arcId: String): Float = offsets[arcId] ?: 0f

    private fun write(arcId: String, value: Float) {
        offsets[arcId] = value
        indexById[arcId]?.let { if (it in aligned.indices) aligned[it] = value }
    }

    /** A tangential drag of `tangentialSlots` slot steps moves the ring with the finger. */
    fun drag(arcId: String, tangentialSlots: Float) = write(arcId, offset(arcId) - tangentialSlots)

    /** Begin the fling from the release velocity; an absent animator snaps immediately. */
    fun startFling(arcId: String, velocity: Float, now: Long, scale: Float, report: (String, Float) -> Unit) {
        val current = offset(arcId)
        val target = RingRotation.flingTarget(current, velocity)
        if (scale <= 0f) {
            write(arcId, target)
            report(arcId, target)
            return
        }
        val omega = sqrt(Metrics.rotateSpringStiffness.toDouble()).toFloat() * scale
        flings[arcId] = RotationFling(current, velocity, target, omega, now)
    }

    /** OPUS4: turn [arcId] to [target]: at once (scale 0) or with the same spring a fling settles with. */
    fun turnTo(arcId: String, target: Float, now: Long, scale: Float, report: (String, Float) -> Unit) {
        flings.remove(arcId)
        if (scale <= 0f) {
            write(arcId, target)
            report(arcId, target)
            return
        }
        val omega = sqrt(Metrics.rotateSpringStiffness.toDouble()).toFloat() * scale
        flings[arcId] = RotationFling(offset(arcId), 0f, target, omega, now)
    }

    /** Snap any active fling to its target (touch interruption / scene change / cancel). */
    fun snapAll(report: (String, Float) -> Unit) {
        if (flings.isEmpty()) return
        val pending = flings.entries.toList()
        flings.clear()
        for ((id, fling) in pending) {
            write(id, fling.target)
            report(id, fling.target)
        }
    }

    /** Advance active flings; returns true while any is still moving. */
    fun step(now: Long, report: (String, Float) -> Unit): Boolean {
        if (flings.isEmpty()) return false
        var animating = false
        val iterator = flings.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val fling = entry.value
            val t = (now - fling.start) / 1000f
            val (o, v) = RingRotation.spring(fling.offset0, fling.velocity0, fling.target, fling.omega, t)
            write(entry.key, o)
            if (RingRotation.isSettled(o, v, fling.target)) {
                write(entry.key, fling.target)
                iterator.remove()
                report(entry.key, fling.target)
            } else {
                animating = true
            }
        }
        return animating
    }

    fun stopFlings() { flings.clear() }
}

/** Static gallery raster, using exactly the prepared home renderer. Call off the UI thread.
 * The caller owns caching; no renderer sessions, gesture handlers or ambient loops are started.
 */
fun rasterHome(state: RingState, skin: Skin, widthDp: Float, heightDp: Float,
    density: Float, look: LookSettings, background: Bitmap? = null): Bitmap {
    val bitmap = Bitmap.createBitmap((widthDp * density).toInt(), (heightDp * density).toInt(), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(skin.colors.bg.toInt())
    if (background != null) canvas.drawBitmap(background, null,
        Rect(0, 0, bitmap.width, bitmap.height), Paint(Paint.FILTER_BITMAP_FLAG))
    val decorations = SkinDecorationCache(skin, widthDp, heightDp, density)
    val scene = SceneDrawing(state.copy(clock = ""), skin, density, 1f, widthDp, heightDp,
        decorations.get(state.arcs), look)
    scene.drawBackground(canvas)
    scene.draw(canvas, 1f, 1f, -1)
    scene.drawChrome(canvas, false, 0f, 0f)
    return bitmap
}
