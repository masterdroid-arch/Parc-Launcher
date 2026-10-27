package com.brightblu.parc.ring

/**
 * T75 badge rule: a slot carries the briefcase when it is a managed-profile app and the `Mark work
 * apps` setting is on. The rule does not depend on the layer, so the card's All-apps sentence holds
 * in both directions: with the setting off (its only off state) no All-apps slot is marked, and
 * with it on (the default) Home, folders and All apps mark work apps alike.
 */
fun workBadgeVisible(isWorkApp: Boolean, markWorkApps: Boolean): Boolean = isWorkApp && markWorkApps
