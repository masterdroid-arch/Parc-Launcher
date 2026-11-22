package com.brightblu.parc

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.brightblu.parc.core.data.backup.BackupKey
import com.brightblu.parc.core.data.backup.BackupStore
import com.brightblu.parc.core.data.backup.BackupStoreSpec
import com.brightblu.parc.core.data.backup.BackupValueType
import com.brightblu.parc.core.data.backup.StoreAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** T196: encrypts and decrypts the DeepL key. The real one uses an Android Keystore key; tests use a fake. */
internal interface KeyCipher {
    fun encrypt(plain: ByteArray): ByteArray
    /** Null when the blob cannot be decrypted (for example after the Keystore key was lost). */
    fun decrypt(blob: ByteArray): ByteArray?
}

/** AES-256-GCM with a key that lives in the Android Keystore and never leaves it. The blob is the 12-byte IV followed by the ciphertext. */
internal class AndroidKeystoreCipher(private val alias: String = "parc_deepl_key") : KeyCipher {
    private fun secret(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(blob: ByteArray): ByteArray? = try {
        if (blob.size <= 12) null else {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            cipher.doFinal(blob, 12, blob.size - 12)
        }
    } catch (e: Exception) { null }
}

/** Where the encrypted text sits. */
internal interface KeyBlobStorage {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

/** A private SharedPreferences file that is not registered with the Parc backup (and the app does not allow system backup). */
internal class PrefsKeyBlobStorage(context: Context) : KeyBlobStorage {
    private val prefs = context.applicationContext.getSharedPreferences("parc_translate_key", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("blob", null)
    override fun write(value: String) { check(prefs.edit().putString("blob", value).commit()) }
    override fun clear() { prefs.edit().remove("blob").commit() }
}

/** Search, the reader and backup restore share this Keystore-backed credential. */
internal class DeepLKeyStore(private val cipher: KeyCipher, private val storage: KeyBlobStorage) {
    /** Saves a pasted key. False when it does not look like a DeepL key (nothing is stored then). */
    fun save(pasted: String): Boolean {
        val key = DeepLRules.cleanKey(pasted) ?: return false
        storage.write(Base64.getEncoder().encodeToString(cipher.encrypt(key.toByteArray(Charsets.UTF_8))))
        return true
    }

    fun load(): String? {
        val stored = storage.read() ?: return null
        val plain = try { cipher.decrypt(Base64.getDecoder().decode(stored)) } catch (e: IllegalArgumentException) { null }
        return plain?.toString(Charsets.UTF_8)?.let(DeepLRules::cleanKey)
    }

    fun has(): Boolean = load() != null
    fun clear() = storage.clear()

    fun backupStore() = BackupStore(BackupSpec, object : StoreAccess {
        override suspend fun read(spec: BackupStoreSpec): Map<String, Any> =
            withContext(Dispatchers.IO) { load()?.let { mapOf(KeyField to it) }.orEmpty() }

        override suspend fun write(spec: BackupStoreSpec, values: Map<String, Any>) = withContext(Dispatchers.IO) {
            require(save(values[KeyField] as String)) { "Invalid translation key" }
        }
    })

    companion object {
        private const val KeyField = "key"
        val BackupSpec = BackupStoreSpec("deepl", listOf(BackupKey(KeyField, BackupValueType.Text, 100)))
        fun of(context: Context) = DeepLKeyStore(AndroidKeystoreCipher(), PrefsKeyBlobStorage(context))
    }
}
