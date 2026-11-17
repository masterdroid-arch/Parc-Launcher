package com.brightblu.parc

/** What the left side of the hub grip shows. Today keeps the Clock page's Alarms action. */
internal enum class HubGripStart { Alarms, Focus, None }

internal object HubGripPriority {
    /**
     * The grip's left slot. Alarms belongs to Today (the old Clock page), so the Focus chip must
     * never take its place there; on every other page a visible Focus chip leads.
     */
    fun start(page: String?, focusVisible: Boolean): HubGripStart = when {
        page != null && HubCatalog.canonicalPage(page) == TodayPage.Id -> HubGripStart.Alarms
        focusVisible -> HubGripStart.Focus
        else -> HubGripStart.None
    }
}
