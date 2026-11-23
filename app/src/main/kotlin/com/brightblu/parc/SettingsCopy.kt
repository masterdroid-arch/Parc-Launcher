package com.brightblu.parc

/**
 * T379: the reading rules for Settings. A row label is short; its helper fits one line at normal type
 * (eight words or fewer); a longer explanation lives behind the row's Info target. Pure, so the copy is
 * checked by a unit test instead of by eye.
 */
internal object SettingsCopy {
    const val MaxHelperWords = 8

    fun words(text: String): Int = text.trim().split(Regex("\\s+")).count { it.isNotBlank() }

    fun fitsOneLine(helper: String): Boolean = words(helper) <= MaxHelperWords
}

/**
 * T379: one removal confirmation for both paths that can remove a folder (Settings' folder editor and
 * the Home folder menu). It names the folder and says where its apps go, and the dialog always offers
 * Cancel beside the destructive action.
 */
internal object FolderRemovalCopy {
    fun title(folderName: String): String = "Remove $folderName?"

    const val Consequence = "Its apps move to New."
    const val Confirm = "Remove"
    const val Cancel = "Cancel"
}
