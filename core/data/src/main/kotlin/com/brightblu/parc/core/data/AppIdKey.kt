package com.brightblu.parc.core.data

import com.brightblu.parc.model.AppId

/** Builds the stable key from a flattened component and a user serial (see AppId KDoc). */
internal fun appIdOf(component: String, userSerial: Long): AppId = AppId("$component@$userSerial")

/** Flattened "pkg/pkg.Class" part of an AppId; the whole value when no separator is present. */
internal fun AppId.flattenedComponent(): String = value.substringBeforeLast('@')

/** User serial part of an AppId; defaults to 0 when the value carries no serial. */
internal fun AppId.userSerial(): Long = value.substringAfterLast('@').toLongOrNull() ?: 0L
