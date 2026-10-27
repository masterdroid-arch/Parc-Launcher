package com.brightblu.parc.core.data.usage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The rank-state document: round trip, tolerance and atomicity. */
class UsageStateStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun file(): File = File(temp.root, "usage_rank_state.json")

    private fun store(): FileUsageStateStore = FileUsageStateStore(file())

    @Test
    fun `a missing file reads as the empty state`() {
        assertEquals(UsageRankState(), store().read())
    }

    @Test
    fun `every field round trips`() {
        val state = UsageRankState(
            lastRecomputeDay = 20_231L,
            ranked = mapOf("social" to listOf("a@0", "b@0")),
            committed = mapOf("social" to mapOf("a@0" to 0, "b@0" to 1)),
            pending = mapOf("social" to mapOf("b@0" to 0)),
            enrichmentWatermarkMillis = 1_700_000_000_000L,
        )

        store().write(state)

        assertEquals(state, store().read())
    }

    @Test
    fun `an empty state round trips as the empty state`() {
        store().write(UsageRankState())

        assertEquals(UsageRankState(), store().read())
    }

    @Test
    fun `a corrupt file reads as the empty state instead of throwing`() {
        file().writeText("{ this is not json")

        assertEquals(UsageRankState(), store().read())
    }

    @Test
    fun `a document from another schema is discarded, not half-applied`() {
        file().writeText("""{"version":9,"lastRecomputeDay":5}""")

        assertEquals(UsageRankState(), store().read())
    }

    @Test
    fun `an unknown key is rejected rather than half-applied`() {
        file().writeText("""{"version":1,"somethingNew":true}""")

        assertEquals(UsageRankState(), store().read())
    }

    @Test
    fun `writing leaves no temporary file behind`() {
        store().write(UsageRankState(lastRecomputeDay = 1L))

        assertTrue(file().exists())
        assertFalse(File(temp.root, "usage_rank_state.json.tmp").exists())
    }

    @Test
    fun `a replaced document is readable and leaves no second file`() {
        val store = store()
        store.write(UsageRankState(lastRecomputeDay = 1L))
        store.write(UsageRankState(lastRecomputeDay = 2L))

        assertEquals(2L, store().read().lastRecomputeDay)
        assertEquals(1, temp.root.listFiles().orEmpty().count { it.name.startsWith("usage_rank_state.json") })
    }
}
