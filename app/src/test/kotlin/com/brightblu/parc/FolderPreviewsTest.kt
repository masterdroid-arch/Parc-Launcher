package com.brightblu.parc

import com.brightblu.parc.model.*
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderPreviewsTest {
    private val ids = (0..6).map { AppId("fixture/$it") }
    private val folder = Folder(FolderId("social"), "Social", appIds = ids.take(6))

    @Test fun rankedMembersComeFirstThenStoredOrderWithoutDuplicatesOrForeignApps() {
        assertEquals(listOf(ids[4], ids[2], ids[0], ids[1]),
            FolderPreviews.members(folder, listOf(ids[6], ids[4], ids[2], ids[4]), ids.toSet(), emptySet()))
    }

    @Test fun absentRankingFallsBackToStoredOrderAndExcludesHiddenOrUnavailableApps() {
        assertEquals(listOf(ids[1], ids[3], ids[4], ids[5]),
            FolderPreviews.members(folder, emptyList(), ids.drop(1).toSet(), setOf(ids[2])))
    }

    @Test fun membershipChangesRemoveOldRankedAppsAndEmptyFolderHasNoMembers() {
        assertEquals(listOf(ids[1]), FolderPreviews.members(folder.copy(appIds = listOf(ids[1])),
            listOf(ids[4], ids[1]), ids.toSet(), emptySet()))
        assertEquals(emptyList<AppId>(), FolderPreviews.members(folder.copy(appIds = emptyList()),
            ids, ids.toSet(), emptySet()))
    }
}
