package com.brightblu.parc

import com.brightblu.parc.model.Metrics

/**
 * T330: the pure side of the stats hub's full-screen view. Home owns one Boolean; these rules decide when
 * a gesture opens or closes it, what size a page reports while it is open, and the words TalkBack reads.
 * Nothing here knows about Compose.
 */
internal object HubFullScreen {
    /** Pulling the grip up by this much opens the view (a shorter up-drag keeps resizing, as before). */
    const val OpenPullDp = Metrics.hubFullOpenPullDp
    /** A downward drag on the page or the grip closes the view once it passes this distance. */
    const val CloseSwipeDp = Metrics.hubFullCloseSwipeDp

    // The one set of words for the controls. They are also the accessibility names.
    const val ExpandLabel = "Expand"
    const val ExpandDescription = "Open full-screen hub"
    const val CloseLabel = "Close"
    const val CloseDescription = "Close full-screen hub"
    const val GripDescription = "Full-screen hub. Tap Close, swipe down or press Back to return"
    const val ContainerDescription = "Full-screen hub"

    /** A grip pull is a vertical travel in dp, negative upward. The view opens only while the setting is on. */
    fun opensOnGripPull(travelDp: Float, enabled: Boolean): Boolean =
        enabled && travelDp.isFinite() && travelDp <= -OpenPullDp

    fun closesOnGripPull(travelDp: Float): Boolean =
        travelDp.isFinite() && travelDp >= CloseSwipeDp

    /** A page swipe closes only from the top of the page, downward and clearly more vertical than sideways. */
    fun closesOnPageSwipe(atTop: Boolean, dxDp: Float, dyDp: Float): Boolean =
        atTop && dxDp.isFinite() && dyDp.isFinite() && dyDp >= CloseSwipeDp && dyDp > kotlin.math.abs(dxDp)

    /**
     * Pages show every tile in the full-screen view (Tall's rule). The compact view keeps the size the
     * user picked for the page, so closing returns Home exactly as it was.
     */
    fun sizeFor(fullScreen: Boolean, pageSize: HubSize): HubSize = if (fullScreen) HubSize.Tall else pageSize

    /** The page on show when the view opens or closes. A page hidden meanwhile falls back to the first tab. */
    fun pageAfterToggle(current: String, pages: List<String>): String =
        current.takeIf { it in pages } ?: pages.firstOrNull().orEmpty()
}
