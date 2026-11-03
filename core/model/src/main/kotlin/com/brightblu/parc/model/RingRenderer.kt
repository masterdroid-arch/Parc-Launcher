package com.brightblu.parc.model

import android.graphics.Bitmap
import androidx.compose.runtime.Composable

/** Renderable slot snapshot; bitmap alone may be null while loading. Never mutate/recycle bitmaps. */
data class RingSlot(
    val position: SlotPosition,
    val slot: Slot,
    val label: String,
    val bitmap: Bitmap? = null,
    val enabled: Boolean = true,
    val unreadCount: Int = 0,
    /**
     * T75: the app belongs to a managed profile and the `Mark work apps` setting is on. The renderer
     * draws the small briefcase badge for such a slot. False for folders, actions and links, and for
     * every slot while the setting is off.
     */
    val isWork: Boolean = false,
)

/**
 * Renderer inputs; level 0 = home, 1 = folder. Collections must not be mutated.
 * highlight null means no search; an empty set means active search with no matches.
 */
data class RingState(
    val arcs: List<ArcSpec>,
    val slots: List<RingSlot>,
    val level: Float,
    val highlight: Set<AppId>? = null,
    val pulse: Set<AppId> = emptySet(),
    val layer: Layer = arcs.firstOrNull()?.layer ?: Layer.Home,
    val title: String = "",
    val clock: String = "",
    val emptyMessage: String = "",
    /** Empty search overlay dims the entire field by 40%; non-null highlight wins. */
    val searchOpen: Boolean = false,
    /** T157: the host dims the whole module on the sheet spring while the search sheet shows, so the renderer does not dim it a second time. */
    val hostDimsModule: Boolean = false,
    /** Host-provided accessibility focus; null when no virtual slot has focus. */
    val accessibilityFocus: SlotPosition? = null,
    /** Slot aimed at by an active hub joystick drag; null when not aiming. */
    val aim: SlotPosition? = null,
    /** Modal surfaces own accessibility while visible. */
    val accessibilityHidden: Boolean = false,
    /**
     * Settled rotation offset per arc id, in slot units (T29). Each entry is the integer offset
     * the ring last snapped to; the renderer seeds its live offsets from this map when a scene's
     * arc set changes and reports every new settle via [RingCallbacks.onRotate]. Transient UI
     * state: never serialized into the layout. Offsets wrap into `0..itemCount-1`.
     */
    val rotations: Map<String, Float> = emptyMap(),
    /**
     * Usage-layer swap (T35): positions whose newcomer animates in with a quick cross-fade and one
     * soft accent pulse. Empty means no swap animation. The host clears it once played.
     */
    val swapPulse: Set<SlotPosition> = emptySet(),
    /**
     * Search discoverability (T35): when true the search button carries a "Search" caption and
     * plays one soft accent pulse on this launcher start.
     */
    val searchHint: Boolean = false,
    /**
     * Where the folder title, the clock and the empty message start, in dp from the top (T166): below the status
     * bar and any cutout like every page ([ParcSpacing.pageTopDp]). Arcs and slots never read it.
     */
    val chromeTopDp: Float = Metrics.keepOutTopDp,
)

/** Hit payload with pointer position in dp. Immutable, non-null. */
data class SlotHit(val position: SlotPosition, val slot: Slot, val xDp: Float, val yDp: Float)

/** Drag phase. Immutable, non-null. */
enum class DragPhase { Start, Move, End, Cancel }

/** Main-thread callbacks; hit is always non-null and identifies the originating slot for drags. */
interface RingCallbacks {
    fun onHubTap() = Unit
    /** Joystick return target; separate from released taps for double-tap timing. */
    fun onHubBack() = onHubTap()
    fun onSearch() = Unit
    /** Reports a completed host target; a newer navigation request may already supersede it. */
    fun onSettled(state: RingState) = Unit
    /** A pointer chose the nearest endpoint of an interrupted snap. */
    fun onInterrupted(state: RingState) = onSettled(state)
    fun onTap(hit: SlotHit)
    fun onLongPress(hit: SlotHit)
    fun onDrag(hit: SlotHit, phase: DragPhase)
    /** A hub joystick drag changed its aimed slot; null means it left the dead zone or ended. */
    fun onAim(position: SlotPosition?) = Unit
    /** An arc settled on a new rotation offset (slot units, wrapped); the host persists it. */
    fun onRotate(arcId: String, offset: Float) = Unit
}

/** Main-thread renderer swap point; non-null immutable inputs. No Android View in the contract. */
interface RingRenderer {
    @Composable
    fun Field(state: RingState, skin: Skin, callbacks: RingCallbacks)
}
