package com.brightblu.parc.core.data.skins

/** Naming rules for user skins: 1 to 16 visible characters; ids are `custom_<slug>` and never collide. */
object SkinNaming {
    const val MaxNameLength = 16
    const val IdPrefix = "custom_"

    fun clean(name: String): String = name.trim().replace(Regex("\\s+"), " ")

    fun isValidName(name: String): Boolean = clean(name).length in 1..MaxNameLength

    fun slug(name: String): String {
        val s = clean(name).lowercase().map { if (it in 'a'..'z' || it in '0'..'9') it else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_')
        return s.ifEmpty { "skin" }.take(24)
    }

    /** `custom_<slug>`, with `_2`, `_3`, … appended until it is not in [taken]. */
    fun uniqueId(name: String, taken: Set<String>): String {
        val base = IdPrefix + slug(name)
        if (base !in taken) return base
        var n = 2
        while ("${base}_$n" in taken) n++
        return "${base}_$n"
    }

    /** OPUS6 (audit F15): only what [slug] produces, so an id can never act as a path. */
    fun isCustomId(id: String): Boolean = id.startsWith(IdPrefix) && id.length > IdPrefix.length &&
        id.substring(IdPrefix.length).all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
}
