package com.brightblu.parc.feature.settings.hidden

import com.brightblu.parc.model.AppEntry
import com.brightblu.parc.model.AppId

/** Component-shaped test id; matches the AppId contract. */
internal fun hiddenId(name: String): AppId = AppId("com.test.$name/com.test.$name.Main@0")

/** Installed-app row for the hidden list tests. */
internal fun hiddenEntry(id: AppId, label: String, suspended: Boolean = false): AppEntry = AppEntry(
    id = id,
    label = label,
    packageName = id.value.substringBefore('/'),
    isSystem = false,
    installTime = 0L,
    suspended = suspended,
)
