package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.feature.settings.ui.SettingKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** T102: the three search-source switches in the settings catalog and the backup. */
class T102Test {




    @Test fun theSourceSwitchesTravelInTheBackupButThePromptMarkerDoesNot() {
        listOf("source_settings", "source_files", "source_events").forEach { assertNotNull(BackupSpecs.search.key(it)) }
        assertNull(BackupSpecs.search.key("extras_prompt_done"))
        assertNull(BackupSpecs.search.key("recent_searches"))
    }
}
