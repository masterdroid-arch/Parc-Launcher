package com.brightblu.parc.core.data.library

import java.util.Locale

/**
 * T253: which entries of a CBZ are pages, and in what order. A CBZ is a plain zip of images; the page
 * order is the file-name order with numbers compared as numbers (2 before 10). Folder entries, hidden
 * files and macOS resource folders are ignored.
 */
object ComicPages {
    val imageExtensions: Set<String> = setOf("jpg", "jpeg", "png", "webp")

    fun isPage(name: String): Boolean {
        val clean = name.replace('\\', '/')
        if (clean.endsWith("/")) return false
        val parts = clean.split('/')
        if (parts.any { it.startsWith(".") || it.equals("__MACOSX", ignoreCase = true) }) return false
        val extension = parts.last().substringAfterLast('.', "").lowercase(Locale.ROOT)
        return extension in imageExtensions
    }

    fun pages(entryNames: List<String>): List<String> =
        entryNames.filter(::isPage).sortedWith(::naturalCompare)

    /** Compares runs of digits by value and everything else case-insensitively. */
    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val x = a[i]
            val y = b[j]
            if (x.isDigit() && y.isDigit()) {
                var ei = i
                while (ei < a.length && a[ei].isDigit()) ei++
                var ej = j
                while (ej < b.length && b[ej].isDigit()) ej++
                val da = a.substring(i, ei).trimStart('0')
                val db = b.substring(j, ej).trimStart('0')
                if (da.length != db.length) return da.length - db.length
                val c = da.compareTo(db)
                if (c != 0) return c
                i = ei
                j = ej
            } else {
                val c = x.lowercaseChar().compareTo(y.lowercaseChar())
                if (c != 0) return c
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

/** T253: zoom and pan limits for one comic page. Pure so the gesture code stays small. */
object ComicZoom {
    const val Fit = 1f
    const val Max = 5f
    const val DoubleTap = 2.5f

    fun clamp(zoom: Float): Float = if (zoom.isFinite()) zoom.coerceIn(Fit, Max) else Fit

    /** Double tap: from fit zoom in, from any zoom back to fit. */
    fun doubleTap(zoom: Float): Float = if (zoom > Fit + 0.05f) Fit else DoubleTap

    /** How far the page may be moved on one axis. Zero at fit, so a fitted page always stays centred. */
    fun panLimit(viewport: Float, zoom: Float): Float =
        if (!viewport.isFinite() || viewport <= 0f) 0f else (viewport * (clamp(zoom) - 1f) / 2f).coerceAtLeast(0f)

    fun clampPan(value: Float, viewport: Float, zoom: Float): Float {
        val limit = panLimit(viewport, zoom)
        return if (value.isFinite()) value.coerceIn(-limit, limit) else 0f
    }
}
