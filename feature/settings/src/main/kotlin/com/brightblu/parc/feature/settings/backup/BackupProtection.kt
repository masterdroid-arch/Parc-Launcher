package com.brightblu.parc.feature.settings.backup

/** Phone-only preference and secret; never registered with FullBackup. */
interface BackupProtection {
    val enabled: Boolean
    fun enable(passphrase: CharArray)
    fun disable()
}

enum class PassphrasePurpose { Enable, Export, Restore }
