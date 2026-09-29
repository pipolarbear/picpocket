package com.picpocket.app.domain.workflow.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Password-based encryption for a single workflow artifact.
 *
 * This is intentionally separate from the sync engine's at-rest
 * [com.picpocket.app.drive.EncryptionManager] (magic header "PKE1", global
 * passphrase): a workflow "encrypt" action protects one artifact with a
 * passphrase the user supplies for that action. A distinct magic header
 * ("PKW1") keeps the two formats from being confused.
 */
@Singleton
class ArtifactCipher @Inject constructor() {

    fun encrypt(passphrase: String, data: ByteArray): ByteArray {
        require(passphrase.isNotBlank()) { "passphrase must not be blank" }
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val key = SecretKeySpec(deriveKey(passphrase, salt), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(data)
        return MAGIC_HEADER + salt + iv + ciphertext
    }

    private fun deriveKey(passphrase: String, salt: ByteArray): ByteArray {
        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withSalt(salt)
            .withParallelism(4)
            .withMemoryAsKB(65536)
            .withIterations(3)
            .build()
        val generator = Argon2BytesGenerator()
        generator.init(params)
        val result = ByteArray(32)
        generator.generateBytes(passphrase.toCharArray(), result)
        return result
    }

    companion object {
        val MAGIC_HEADER = byteArrayOf(0x50, 0x4B, 0x57, 0x31) // "PKW1"

        fun isWorkflowEncrypted(data: ByteArray): Boolean =
            data.size >= MAGIC_HEADER.size &&
                data.copyOf(MAGIC_HEADER.size).contentEquals(MAGIC_HEADER)
    }
}
