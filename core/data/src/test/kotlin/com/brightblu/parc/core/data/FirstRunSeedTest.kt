package com.brightblu.parc.core.data

import com.brightblu.parc.core.data.firstrun.AppKind
import com.brightblu.parc.core.data.firstrun.InstalledApp
import com.brightblu.parc.core.data.firstrun.SeedFolderRules
import com.brightblu.parc.model.ActionKind
import com.brightblu.parc.model.AppId
import com.brightblu.parc.model.FolderId
import com.brightblu.parc.model.ParcLayout
import com.brightblu.parc.model.Slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * First-run seed (T62): neutral fake apps, no device. The seed is built from the installed list and
 * the picker choice, so every assertion here is about rules, not about a packaged document.
 */
class FirstRunSeedTest {

    private val alpha = app("Alpha", "com.alpha.app")
    private val beta = app("beta", "com.beta.app")
    private val gamma = app("Gamma", "com.gamma.app")
    private val delta = app("Delta", "com.delta.app")
    private val omega = app("Omega", "com.omega.app", AppKind.Uncategorised)

    private fun app(label: String, pkg: String, kind: AppKind = AppKind.Uncategorised): InstalledApp =
        InstalledApp(AppId("$pkg/$pkg.Main@0"), label, pkg, kind)

    private fun build(
        installed: List<InstalledApp>,
        main: List<AppId> = listOf(alpha.id, beta.id, gamma.id, delta.id),
    ): ParcLayout = FirstRunSeed.build(installed, main, homeArcSpecs())

    private fun ParcLayout.slots(arc: String) = arcs.first { it.arc.id == arc }.slots

    private fun ParcLayout.folderNames(): List<String> = folders.map { it.name }

    @Test
    fun `main apps sit on A1 in picker order and are pinned`() {
        val layout = build(listOf(alpha, beta, gamma, delta, omega))

        assertEquals(
            listOf(alpha, beta, gamma, delta).map { Slot.App(it.id) },
            (0..3).map { layout.slots("A1")[it] },
        )
        assertEquals(setOf(alpha.id, beta.id, gamma.id, delta.id), layout.pins)
    }

    @Test
    fun `A2 starts empty and A4 slot zero is the settings gear`() {
        val layout = build(listOf(alpha, beta))

        assertTrue(layout.slots("A2").isEmpty())
        assertEquals(Slot.Action(ActionKind.Settings), layout.slots("A4")[0])
        assertTrue(layout.arcs.flatMap { it.slots.values }.none { it == Slot.Action(ActionKind.More) })
    }

    @Test
    fun `fewer than four main apps leaves the remaining A1 slots empty`() {
        val layout = build(listOf(alpha, beta), main = listOf(alpha.id))

        assertEquals(mapOf(0 to Slot.App(alpha.id)), layout.slots("A1"))
        assertEquals(setOf(alpha.id), layout.pins)
    }

    @Test
    fun `main apps are not repeated inside folders`() {
        val layout = build(
            listOf(alpha, beta, gamma, delta),
            main = listOf(alpha.id, beta.id, gamma.id, delta.id),
        )

        assertTrue(layout.folders.all { folder -> folder.appIds.none { it in layout.pins } })
        assertEquals(listOf("New"), layout.folderNames())
    }

    @Test
    fun `category kinds build folders with alphabetical contents`() {
        val zeta = app("Zeta", "com.zeta.viewer", AppKind.Image)
        val music = app("alpha", "com.alpha.player", AppKind.Audio)
        val notes = app("Beta", "com.beta.notes", AppKind.Productivity)

        val layout = build(listOf(zeta, music, notes), main = emptyList())

        val media = layout.folders.first { it.name == "Media" }
        assertEquals(listOf(music.id, zeta.id), media.appIds)
        val tools = layout.folders.first { it.name == "Tools" }
        assertEquals(listOf(notes.id), tools.appIds)
        assertEquals(listOf("Media", "Tools", "New"), layout.folderNames())
    }

    @Test
    fun `the public keyword table files well known app families`() {
        val cases = listOf(
            app("Chatter", "com.whatsapp") to "Chat",
            app("Photos", "com.instagram.android") to "Social",
            app("Tube", "com.google.android.youtube") to "Media",
            app("Fox", "org.mozilla.firefox") to "Web",
            app("Atlas", "com.google.android.apps.maps") to "Maps",
            app("Purse", "com.paypal.android.p2pmobile") to "Money",
            app("Mail", "com.google.android.gm") to "Google",
            app("Blocks", "com.example.blocks", AppKind.Game) to "Games",
            app("Sum", "com.example.calculator") to "Tools",
        )

        val layout = build(cases.map { it.first }, main = emptyList())

        cases.forEach { (candidate, folder) ->
            val built = layout.folders.firstOrNull { it.name == folder }
            requireNotNull(built) { "expected a $folder folder" }
            assertTrue("$folder must hold ${candidate.label}", candidate.id in built.appIds)
        }
        assertEquals(
            listOf("Chat", "Social", "Media", "Web", "Maps", "Money", "Google", "Games", "Tools", "New"),
            layout.folderNames(),
        )
    }

    @Test
    fun `folder contents sort case-insensitively by label then id`() {
        val b = app("beta", "com.one.b", AppKind.Audio)
        val a = app("Alpha", "com.two.a", AppKind.Audio)
        val c = app("gamma", "com.three.c", AppKind.Audio)
        val sameOne = app("Same", "com.two.same", AppKind.Audio)
        val sameTwo = app("same", "com.one.same", AppKind.Audio)

        val layout = build(listOf(b, a, c, sameOne, sameTwo), main = emptyList())
        val media = layout.folders.first { it.id == FolderId("media") }

        assertEquals(listOf(a.id, b.id, c.id, sameTwo.id, sameOne.id), media.appIds)
    }

    @Test
    fun `uncategorised apps are unfiled and an empty category makes no folder`() {
        val layout = build(listOf(omega), main = emptyList())

        assertEquals(listOf("New"), layout.folderNames())
        assertTrue(layout.folders.all { it.appIds.isEmpty() })
    }

    @Test
    fun `folders fill A3 then A4 from slot one and never cover the gear`() {
        val kinds = listOf(
            app("A", "com.a.one", AppKind.Game),
            app("B", "com.b.two", AppKind.Social),
            app("C", "com.c.three", AppKind.Maps),
            app("D", "com.d.four", AppKind.Productivity),
        )
        val layout = build(kinds, main = emptyList())

        val expected = listOf("Social", "Maps", "Games", "Tools")
        assertEquals(expected.size, layout.folders.size - 1)
        val placed = layout.arcs
            .filter { it.arc.id == "A3" || it.arc.id == "A4" }
            .flatMap { assignment -> assignment.slots.map { assignment.arc.id to it } }
            .filter { it.second.value is Slot.Folder }
            .sortedWith(compareBy({ it.first }, { it.second.key }))
        assertEquals(
            listOf("A3" to 0, "A3" to 1, "A3" to 2, "A3" to 3),
            placed.map { it.first to it.second.key },
        )
        assertEquals(Slot.Action(ActionKind.Settings), layout.slots("A4")[0])
        assertEquals(
            placed.take(4).map { (it.second.value as Slot.Folder).id.value },
            layout.folders.filter { it.id != NewFolderId }.map { it.id.value },
        )
    }

    @Test
    fun `folder names are at most eight characters and unique`() {
        val names = SeedFolderRules.rules.map { it.name }

        assertTrue(names.all { it.length <= SeedFolderRuleNameLimit })
        assertEquals(names.size, names.toSet().size)
        assertTrue(SeedFolderRules.rules.map { it.id }.toSet().size == SeedFolderRules.rules.size)
    }

    @Test
    fun `a missing installed list still yields the gear and New`() {
        val layout = build(emptyList(), main = emptyList())

        assertTrue(layout.slots("A1").isEmpty())
        assertEquals(Slot.Action(ActionKind.Settings), layout.slots("A4")[0])
        assertEquals(listOf(FolderId("new")), layout.folders.map { it.id })
        assertTrue(layout.pins.isEmpty())
        assertNull(layout.arcs.first { it.arc.id == "A1" }.slots[0])
    }

    /**
     * The card's grep exclusion names this test: it is the only place allowed to print the public
     * keyword table. It asserts the table's shape (public package patterns and fixed generic words)
     * and prints every row so the proof in `reports/T62.md` can be compared line for line.
     */
    @Test
    fun `the keyword table prints only the keyword table`() {
        val dump = SeedFolderRules.keywordTableDump()
        println(dump)

        assertTrue(dump.lines().filter { it.isNotBlank() }.size == SeedFolderRules.rules.size)
        SeedFolderRules.rules.forEach { rule ->
            assertTrue("${rule.id} name too long", rule.name.length <= SeedFolderRuleNameLimit)
            rule.patterns.forEach { pattern ->
                val publicShape = pattern.contains('.') || pattern in GenericPatterns
                assertTrue("unexpected pattern: $pattern", publicShape)
            }
        }
    }

    private companion object {
        const val SeedFolderRuleNameLimit = 8
        val GenericPatterns = setOf(
            "bank", "banco", "banking", "wallet", "finance", "calculator", "camera", "clock",
            "calendar", "contacts", "documentsui", "fileexplorer", "files", "recorder",
        )
    }
}
