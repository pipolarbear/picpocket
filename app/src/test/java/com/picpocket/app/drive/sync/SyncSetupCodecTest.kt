package com.picpocket.app.drive.sync

import android.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlinx.serialization.json.Json

@RunWith(RobolectricTestRunner::class)
class SyncSetupCodecTest {

    private val folder = FolderLocator(
        authority = "com.google.android.apps.docs.storage",
        documentId = "ACC123",
        displayName = "PicPocketTest",
    )

    @Test
    fun `payload with a passphrase round-trips`() {
        val payload = SyncSetupPayload(
            deviceId = "dev-a",
            deviceName = "Pixel A",
            folder = folder,
            passphrase = "s3cret",
            passphraseCount = 2,
        )

        val decoded = SyncSetupCodec.decode(SyncSetupCodec.encode(payload)).getOrThrow()

        assertEquals(payload, decoded)
    }

    @Test
    fun `folder-only payload round-trips without a passphrase`() {
        val payload = SyncSetupPayload(deviceId = "d", deviceName = "n", folder = folder)
        val decoded = SyncSetupCodec.decode(SyncSetupCodec.encode(payload)).getOrThrow()
        assertNull(decoded.passphrase)
        assertEquals(folder, decoded.folder)
    }

    @Test
    fun `unknown fields are tolerated`() {
        val jsonBody = """
            {"version":1,"deviceId":"d","folder":{"authority":"a","documentId":"doc","displayName":"x"},"future":"ignored"}
        """.trimIndent()
        val code = SyncSetupCodec.PREFIX +
            Base64.encodeToString(jsonBody.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        val decoded = SyncSetupCodec.decode(code).getOrThrow()

        assertEquals("a", decoded.folder.authority)
        assertEquals("doc", decoded.folder.documentId)
    }

    @Test
    fun `unsupported version is rejected`() {
        val jsonBody = """{"version":99,"folder":{"authority":"a","documentId":"doc"}}"""
        val code = SyncSetupCodec.PREFIX +
            Base64.encodeToString(jsonBody.toByteArray(), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

        assertTrue(SyncSetupCodec.decode(code).isFailure)
    }

    @Test
    fun `malformed and foreign codes are rejected`() {
        assertTrue(SyncSetupCodec.decode("not a picpocket code").isFailure)
        assertTrue(SyncSetupCodec.decode("picpocket-sync:!!!not-base64!!!").isFailure)
        assertTrue(SyncSetupCodec.decode("").isFailure)
    }

    @Test
    fun `a payload with an empty folder is rejected`() {
        val code = SyncSetupCodec.encode(SyncSetupPayload(folder = FolderLocator("", "", "")))
        assertTrue(SyncSetupCodec.decode(code).isFailure)
    }

    @Test
    fun `tree uri round-trips through a locator`() {
        val treeUri = "content://com.example.documents/tree/primary%3APictures"

        val locator = FolderLocator.fromTreeUri(treeUri, "Pictures")

        assertEquals("com.example.documents", locator?.authority)
        assertEquals("primary:Pictures", locator?.documentId)
        assertEquals(treeUri, locator?.treeUri()?.toString())
    }

    @Test
    fun `non tree uris are rejected`() {
        assertNull(FolderLocator.fromTreeUri("", "x"))
        assertNull(FolderLocator.fromTreeUri("content://com.example.documents/document/primary%3APictures", "x"))
    }
}
