package com.picpocket.app.domain.workflow.crypto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ArtifactCipherTest {

    private val cipher = ArtifactCipher()
    private val plaintext = "hello workflow".toByteArray(StandardCharsets.UTF_8)

    @Test
    fun `output carries the PKW1 header, not the sync PKE1 header`() {
        val out = cipher.encrypt("s3cret", plaintext)
        assertTrue(ArtifactCipher.isWorkflowEncrypted(out))
        assertTrue(out.copyOf(4).contentEquals(ArtifactCipher.MAGIC_HEADER))
        assertFalse("must not use the sync at-rest magic", out.copyOf(4).contentEquals("PKE1".toByteArray()))
    }

    @Test
    fun `ciphertext does not leak the plaintext`() {
        val out = cipher.encrypt("s3cret", plaintext)
        val body = out.copyOfRange(ArtifactCipher.MAGIC_HEADER.size, out.size)
        assertFalse(String(body, StandardCharsets.ISO_8859_1).contains("hello workflow"))
    }

    @Test
    fun `a blank passphrase is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            cipher.encrypt("  ", plaintext)
        }
    }
}
