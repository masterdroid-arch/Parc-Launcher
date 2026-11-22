package com.brightblu.parc

import com.brightblu.parc.model.TypeToken
import com.brightblu.parc.model.TypographyTokens

/**
 * T322: the hub pages' semantic roles, mapped once onto the skin's six-role scale.
 *
 * No size, weight or family lives here. A page asks for the role its text plays (a figure, a heading,
 * a row label, reading text or a quiet caption) and gets whatever the selected skin says.
 */
internal object HubType {
    /** A big number: 28 sp, weight 600 in the default skin, Doto when the skin asks for dot numerals. */
    fun figure(typography: TypographyTokens): TypeToken = typography.stat

    /** A page or section heading: 28 sp, weight 300 in the default skin. */
    fun heading(typography: TypographyTokens): TypeToken = typography.title

    /** A card title or a strong label: 17 sp, weight 500. */
    fun section(typography: TypographyTokens): TypeToken = typography.section

    /** Normal reading text and row values: 15 sp, weight 400. */
    fun body(typography: TypographyTokens): TypeToken = typography.body

    /** Quiet hints and secondary text: 11 sp, weight 500. */
    fun caption(typography: TypographyTokens): TypeToken = typography.caption

    /**
     * The label that belongs to a big figure. Body rather than caption, so a row label stays
     * readable beside a 28 sp number instead of being crushed by it.
     */
    fun figureLabel(typography: TypographyTokens): TypeToken = typography.body

    /** T322: month initials may be small, but never below the caption role. */
    fun monthInitial(typography: TypographyTokens): TypeToken = typography.caption

    /** A figure is a numeral when it contains a digit; words take the section role instead of stat. */
    fun isFigureNumeral(text: String): Boolean = text.any { it.isDigit() }
}
