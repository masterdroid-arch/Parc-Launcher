package com.brightblu.parc.ring.module

import com.brightblu.parc.model.*
import com.brightblu.parc.ring.RingLayout

/** Fixed folder windows, independent of Home arc count and icon capacity preferences. */
object FolderModuleLayout {
    fun arcs(layout: RingLayout, folderId: FolderId): List<ArcSpec> = List(2) { index ->
        val inner = index == 0
        val capacity = if (inner) Metrics.folderInnerCapacity else Metrics.folderOuterCapacity
        val pitch = if (inner) Metrics.folderInnerPitchDegrees else Metrics.folderOuterPitchDegrees
        val angles = List(capacity) { slot ->
            if (layout.options.mirrored) 360f - slot * pitch else 180f + slot * pitch
        }
        ArcSpec("${folderId.value}/A${index + 1}", index,
            if (inner) Metrics.folderInnerRadiusDp else Metrics.folderOuterRadiusDp,
            angles.first(), angles.last(), capacity, Layer.Folder, folderId = folderId,
            originXDp = layout.originXDp, originYDp = layout.originYDp, slotAngles = angles)
    }
}
