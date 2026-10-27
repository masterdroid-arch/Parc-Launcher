package com.brightblu.parc.core.data.icons

/**
 * One installed icon pack. Immutable, non-null. [packageName] is the pack's own package; [label] is
 * its launcher label, falling back to the package name when the pack ships no label.
 */
data class IconPack(val packageName: String, val label: String)

/** Ordering and de-duplication rules for the pack list. Pure. */
internal object IconPackRules {
    /**
     * One entry per package (first wins), sorted by label then package. Two of the three theme
     * actions are often declared by the same pack, so de-duplication is required, not cosmetic.
     */
    fun distinctSorted(packs: List<IconPack>): List<IconPack> {
        val byPackage = LinkedHashMap<String, IconPack>()
        packs.forEach { pack ->
            val key = pack.packageName.trim()
            if (key.isNotEmpty()) byPackage.putIfAbsent(key, pack.copy(packageName = key))
        }
        return byPackage.values.sortedWith(
            compareBy<IconPack, String>(String.CASE_INSENSITIVE_ORDER) { it.label }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { pack: IconPack -> pack.packageName },
        )
    }

    /** A selection is a package name, or none when blank or absent. */
    fun normalizeSelection(packageName: String?): String? =
        packageName?.trim()?.takeIf { it.isNotEmpty() }
}
