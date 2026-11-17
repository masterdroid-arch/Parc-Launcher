package com.brightblu.parc

/**
 * T263: ids of the pages that replace the old ones. They are stable because they are stored in the
 * hub document and in the remembered page; the old ids are still decoded through [HubCatalog.canonicalPage].
 */
internal object DevicePage { const val Id = "device" }
internal object ControlsPage { const val Id = "controls" }
internal object CommonplacePage { const val Id = "commonplace" }
