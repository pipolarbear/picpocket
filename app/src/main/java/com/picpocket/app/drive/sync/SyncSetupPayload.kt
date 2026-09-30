package com.picpocket.app.drive.sync

import android.net.Uri
import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A SAF folder's portable locator: provider authority + tree document id + a display name. */
@Serializable
data class FolderLocator(
    val authority: String,
    val documentId: String,
    val displayName: String = "",
) {
    /** The tree URI the SAF picker can be pre-navigated to on a device with the same provider. */
    fun treeUri(): Uri = Uri.Builder()
        .scheme("content")
        .authority(authority)
        .appendPath("tree")
        .appendPath(documentId)
        .build()

    companion object {
        /** Builds a locator from a persisted tree URI and its display name, or null if it isn't a tree URI. */
        fun fromTreeUri(treeUri: String, displayName: String): FolderLocator? {
            if (treeUri.isBlank()) return null
            val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
            val authority = uri.authority ?: return null
            val segments = uri.pathSegments
            if (segments.size < 2 || segments.first() != "tree") return null
            val documentId = segments[1]
            if (documentId.isBlank()) return null
            return FolderLocator(authority, documentId, displayName)
        }
    }
}

/**
 * The sync configuration a device shares so another device can join: the folder
 * locator, and (when encryption is on) the passphrase and its generation.
 */
@Serializable
data class SyncSetupPayload(
    val version: Int = SyncSetupCodec.CURRENT_VERSION,
    val deviceId: String = "",
    val deviceName: String = "",
    val folder: FolderLocator = FolderLocator("", "", ""),
    val passphrase: String? = null,
    val passphraseCount: Int = 0,
)

/**
 * Encodes/decodes a [SyncSetupPayload] as a copy-pasteable text code. The code is
 * safe to render in a QR and to paste; it is not encrypted (the same user moves it
 * between their own devices).
 */
object SyncSetupCodec {
    const val PREFIX = "picpocket-sync:"
    const val CURRENT_VERSION = 1

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun encode(payload: SyncSetupPayload): String {
        val body = json.encodeToString(payload).toByteArray(Charsets.UTF_8)
        val encoded = Base64.encodeToString(body, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
        return PREFIX + encoded
    }

    /** Decodes and validates a setup code; returns a failure for anything unusable. */
    fun decode(code: String): Result<SyncSetupPayload> {
        val trimmed = code.trim()
        if (!trimmed.startsWith(PREFIX)) {
            return Result.failure(IllegalArgumentException("Not a PicPocket setup code"))
        }
        val payload = runCatching {
            val decoded = Base64.decode(trimmed.removePrefix(PREFIX), Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
            json.decodeFromString<SyncSetupPayload>(String(decoded, Charsets.UTF_8))
        }.getOrElse { return Result.failure(IllegalArgumentException("Malformed setup code")) }

        if (payload.version != CURRENT_VERSION) {
            return Result.failure(IllegalArgumentException("Unsupported setup code version ${payload.version}"))
        }
        if (payload.folder.authority.isBlank() || payload.folder.documentId.isBlank()) {
            return Result.failure(IllegalArgumentException("Setup code has no sync folder"))
        }
        return Result.success(payload)
    }
}
