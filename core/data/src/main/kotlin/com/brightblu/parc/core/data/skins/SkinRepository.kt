package com.brightblu.parc.core.data.skins

import android.content.Context
import com.brightblu.parc.core.data.ParcPrefs
import com.brightblu.parc.core.data.ParcSettings
import com.brightblu.parc.model.Skin
import com.brightblu.parc.model.SkinProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The `SkinProvider` contract implemented over a validated [SkinCatalog] and the persisted settings
 * (T16, DESIGN §2.8). Thread-safe: [active] is a [StateFlow] fed by DataStore, so a skin change from the
 * picker reaches every collector on the main dispatcher; reads from any thread are safe.
 *
 * The catalog is fixed at construction (assets do not change while the process lives). A persisted id
 * that is unknown, blank or no longer authored resolves to the catalog default through [SkinCatalog.byId],
 * so the stream never emits nothing and never throws.
 */
class SkinRepository(
    private val catalog: SkinCatalog,
    settings: Flow<ParcSettings>,
    scope: CoroutineScope,
    private val custom: StateFlow<List<Skin>> = MutableStateFlow(emptyList()),
    /** T96: the generated Wallpaper skin; null when the device has no dynamic colour. */
    private val dynamic: StateFlow<Skin?> = MutableStateFlow(null),
) : SkinProvider {

    private fun resolve(id: String, extra: List<Skin>, wallpaper: Skin?): Skin =
        catalog.skins.firstOrNull { it.id == id } ?: wallpaper?.takeIf { it.id == id }
            ?: extra.firstOrNull { it.id == id } ?: catalog.default

    override val active: StateFlow<Skin> = combine(settings, custom, dynamic) { s, extra, wp -> resolve(s.skinId, extra, wp) }
        .stateIn(scope, SharingStarted.Eagerly, resolve("", emptyList(), null))

    /** Every built-in skin (default first), the Wallpaper skin when supported, then the user's skins (DESIGN §5 S14). */
    val available: List<Skin> get() = catalog.skins + listOfNotNull(dynamic.value) + custom.value

    /** The same list as a flow, for a picker that must follow edits live. */
    val availableFlow: Flow<List<Skin>> = combine(custom, dynamic) { c, wp -> catalog.skins + listOfNotNull(wp) + c }

    /** Files that were refused at load; empty in a healthy build. */
    val rejections: List<SkinRejection> get() = catalog.rejections

    companion object {
        /** Production factory. Reads the skin assets synchronously; call it off the cold-start path if it matters. */
        fun create(context: Context, prefs: ParcPrefs, scope: CoroutineScope, custom: CustomSkinStore? = null,
            catalog: SkinCatalog = SkinCatalogLoader.parse(AndroidSkinAssets.read(context)),
            wallpaper: WallpaperSkinSource? = null): SkinRepository =
            SkinRepository(catalog, prefs.settings, scope, custom?.skins ?: MutableStateFlow(emptyList()),
                wallpaper?.skin ?: MutableStateFlow(null))
    }
}
