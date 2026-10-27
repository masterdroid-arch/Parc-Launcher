package com.brightblu.parc.ring

/** Prepared once per scene, never formatted while drawing. Null means a plain dot. */
internal fun notificationBadgeText(count: Int): String? = when {
    count < 2 -> null
    count > 9 -> "9+"
    else -> count.toString()
}

/** T97: a folder disc shows its number from the first notification (an app icon shows a plain dot for one). */
internal fun folderBadgeText(count: Int): String? = when {
    count < 1 -> null
    count > 9 -> "9+"
    else -> count.toString()
}
