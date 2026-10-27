package com.brightblu.parc.core.data.skins

import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.model.Skin
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The `SkinProvider` implementation: the persisted id selects a validated skin, and an id that is no
 * longer authored (a downgrade, a hand-edited setting) falls back to the default instead of throwing.
 *
 * The repository collects in a real [Dispatchers.Unconfined] scope, so the flow is driven
 * deterministically without a test scheduler: each value change is applied before the next line runs.
 */
class SkinRepositoryTest {

    private val catalog = SkinCatalogLoader.parse(
        listOf(
            SkinAsset("midnight.json", File("src/main/assets/skins/midnight.json").readText()),
            SkinAsset("dusk.json", File("src/main/assets/skins/dusk.json").readText()),
        ),
    )

    private fun scope() = CoroutineScope(Dispatchers.Unconfined)

    @Test
    fun `active follows the persisted skin id`() {
        val scope = scope()
        val settings = MutableStateFlow(ParcSettings())
        val repository = SkinRepository(catalog, settings, scope)

        assertEquals(Skin.Midnight, repository.active.value)

        settings.value = ParcSettings(skinId = "dusk")
        assertEquals(Skin.Dusk, repository.active.value)
        scope.cancel()
    }

    @Test
    fun `an unknown persisted id falls back to the default`() {
        val scope = scope()
        val repository = SkinRepository(catalog, MutableStateFlow(ParcSettings(skinId = "neon")), scope)

        assertEquals(Skin.Midnight, repository.active.value)
        scope.cancel()
    }

    @Test
    fun `available exposes the validated catalog, default first`() {
        val scope = scope()
        val repository = SkinRepository(catalog, MutableStateFlow(ParcSettings()), scope)

        assertEquals(listOf(Skin.Midnight, Skin.Dusk), repository.available)
        assertEquals(emptyList<SkinRejection>(), repository.rejections)
        scope.cancel()
    }
}
