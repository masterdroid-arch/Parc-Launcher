package com.brightblu.parc

import android.content.Context
import com.brightblu.parc.core.data.backup.BackupStore

/** T198: the builds without extra pages (play, and any build made from the public sources). */
internal object PersonalExtras : ExtraPageSet {
    override val pages: List<ExtraHubPage> = emptyList()
    override fun backupStores(context: Context): List<BackupStore> = emptyList()
}
