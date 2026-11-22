package com.brightblu.parc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.brightblu.parc.feature.settings.backup.BackupProtection
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only ciphertext is persisted. Neither this store nor its key is included in Parc backups. */
internal class BackupPassphraseStore(context: Context) : BackupProtection {
    private val prefs = context.applicationContext.getSharedPreferences("backup_protection_local", Context.MODE_PRIVATE)
    private val weekly = WeeklyBackupStore(context.applicationContext)
    override val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    private fun key(create: Boolean): SecretKey? {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(Alias, null) as? SecretKey)?.let { return it }
        if (!create) return null
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(Alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    override fun enable(passphrase: CharArray) {
        require(passphrase.size >= 8)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key(true)) }
        val bytes = String(passphrase).toByteArray(Charsets.UTF_8)
        val sealed = try { cipher.doFinal(bytes) } finally { bytes.fill(0) }
        check(prefs.edit().putString("secret", Base64.getEncoder().encodeToString(cipher.iv + sealed))
            .putBoolean("enabled", true).commit())
        weekly.clearMissingPassphrase()
    }
    override fun disable() {
        check(prefs.edit().putBoolean("enabled", false).remove("secret").commit())
        weekly.clearMissingPassphrase()
    }
    fun read(): CharArray? { return try {
        val bytes = Base64.getDecoder().decode(prefs.getString("secret", null) ?: return null)
        val secretKey = key(false) ?: return null
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        val plain = cipher.doFinal(bytes, 12, bytes.size - 12)
        try { plain.toString(Charsets.UTF_8).toCharArray() } finally { plain.fill(0) }
    } catch (_: Exception) { null } }
    private companion object { const val Alias = "parc-backup-passphrase-v1" }
}
