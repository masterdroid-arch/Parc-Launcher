package com.brightblu.parc.core.data.backup

import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Text armour keeps SAF's existing UTF-8 transport. The entire binary header is authenticated. */
object BackupEncryption {
    const val Magic = "PARC-ENCRYPTED\n"
    const val Iterations = 600_000
    private const val HeaderSize = 4 + 4 + 16 + 12
    fun isEncrypted(text: String): Boolean = text.startsWith("PARC-ENCRYPTED")

    fun encrypt(text: String, passphrase: CharArray): String {
        require(passphrase.size >= 8)
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val header = ByteBuffer.allocate(HeaderSize).putInt(1).putInt(Iterations).put(salt).put(iv).array()
        val cipher = cipher(Cipher.ENCRYPT_MODE, passphrase, salt, iv, Iterations)
        cipher.updateAAD(Magic.toByteArray(Charsets.US_ASCII) + header)
        return Magic + Base64.getEncoder().encodeToString(header + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(text: String, passphrase: CharArray): String {
        require(text.startsWith(Magic))
        val encoded = text.substring(Magic.length)
        val bytes = Base64.getDecoder().decode(encoded)
        // The decoder accepts missing padding and unused trailing bits. Reject altered armour too.
        require(Base64.getEncoder().encodeToString(bytes) == encoded)
        require(bytes.size >= HeaderSize + 16)
        val buffer = ByteBuffer.wrap(bytes)
        require(buffer.int == 1)
        val iterations = buffer.int
        // Bound attacker-controlled work while retaining a versioned migration path.
        require(iterations in Iterations..2_000_000)
        val salt = ByteArray(16).also { buffer.get(it) }
        val iv = ByteArray(12).also { buffer.get(it) }
        val cipher = cipher(Cipher.DECRYPT_MODE, passphrase, salt, iv, iterations)
        cipher.updateAAD(Magic.toByteArray(Charsets.US_ASCII) + bytes.copyOfRange(0, HeaderSize))
        return cipher.doFinal(bytes, HeaderSize, bytes.size - HeaderSize).toString(Charsets.UTF_8)
    }

    private fun cipher(mode: Int, passphrase: CharArray, salt: ByteArray, iv: ByteArray, iterations: Int): Cipher {
        val spec = PBEKeySpec(passphrase, salt, iterations, 256)
        val key = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
        return try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            }
        } finally { key.fill(0) }
    }
}
