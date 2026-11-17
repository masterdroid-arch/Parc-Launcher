package com.brightblu.parc

import com.brightblu.parc.core.data.backup.BackupSpecs
import com.brightblu.parc.core.data.backup.FullBackup
import com.brightblu.parc.core.data.comms.CommsHistoryStore
import com.brightblu.parc.core.data.comms.CommsMessageDao
import com.brightblu.parc.core.data.comms.CommsMessageEntity
import com.brightblu.parc.core.data.comms.CommsPackages
import com.brightblu.parc.model.fakes.FakeLayoutRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T214's backup-exclusion sentinel. The history file is not a `BackupStore`, is not named in any
 * `BackupSpec`, and its text never appears in `FullBackup.export()`. A backup made before history
 * existed restores without creating a row.
 */
class CommsHistoryBackupTest {

    /** A minimal DAO that holds the sentinel in the same shape the production store would. */
    private class SentinelDao : CommsMessageDao {
        val rows = mutableListOf<CommsMessageEntity>()
        private val state = MutableStateFlow<List<CommsMessageEntity>>(emptyList())

        override fun insert(message: CommsMessageEntity): Long {
            if (rows.any { it.identity == message.identity }) return -1L
            rows += message
            state.value = rows.toList()
            return message.id
        }

        override fun findByIdentity(identity: String): CommsMessageEntity? =
            rows.firstOrNull { it.identity == identity }

        override fun markLive(identity: String, notificationKey: String, conversationTitle: String): Int = 0

        override fun markRemoved(notificationKey: String, removedAtMs: Long): Int = 0

        override fun markAllNotLive(removedAtMs: Long): Int = 0

        override suspend fun clear() {
            rows.clear()
            state.value = emptyList()
        }

        override suspend fun delete(id: Long) {
            rows.removeAll { it.id == id }
            state.value = rows.toList()
        }

        override suspend fun deleteConversation(app: String, conversationKey: String) {
            rows.removeAll { it.app == app && it.conversationKey == conversationKey }
            state.value = rows.toList()
        }

        override fun observeAll(): Flow<List<CommsMessageEntity>> = state
    }

    private fun sentinelRow() = CommsMessageEntity(
        id = 1,
        app = CommsPackages.WhatsApp,
        conversationKey = "Sam",
        conversationTitle = "Sam",
        sender = "Sam",
        notificationTitle = "Sam",
        text = "T214-BACKUP-SENTINEL must never appear in a backup",
        links = "[]",
        timeMs = 1_760_000_000_000L,
        recordedAtMs = 1_760_000_001_000L,
        notificationKey = "sentinel-key",
        identity = "sentinel-identity",
        live = true,
        removedAtMs = null,
    )

    @Test
    fun `a sentinel message is absent from the full backup`() = runTest {
        val dao = SentinelDao()
        dao.insert(sentinelRow())
        val backup = FullBackup(FakeLayoutRepository(), emptyList())

        val text = backup.export()

        assertFalse(text.contains("T214-BACKUP-SENTINEL"))
        assertFalse(text.contains("sentinel-key"))
        assertFalse(text.contains("sentinel-identity"))
        assertFalse(text.contains(CommsHistoryStore.DatabaseName))
        assertFalse(text.contains("comms_messages"))
    }

    @Test
    fun `no backup store or key carries history content`() {
        assertTrue(BackupSpecs.all.none { it.id.contains("comms") })
        val keys = BackupSpecs.all.flatMap { it.keys }.map { it.name }.toSet()
        assertFalse(keys.any { it in setOf("text", "links", "sender", "identity", "notification_key", "conversation_key") })
        // The two settings remain ordinary preferences: the switch and the live allow-list.
        assertTrue("comms_keep_history" in keys)
        assertTrue("comms_apps" in keys)
    }

    @Test
    fun `an old backup restores with an empty history`() = runTest {
        val dao = SentinelDao()
        val backup = FullBackup(FakeLayoutRepository(), emptyList())
        val oldBackupText = backup.export()

        backup.apply(backup.parse(oldBackupText))

        assertTrue(dao.rows.isEmpty())
    }
}
