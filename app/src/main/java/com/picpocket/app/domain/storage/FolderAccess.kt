package com.picpocket.app.domain.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Access to user-picked SAF folders (tree URIs) and their persisted grants. */
interface FolderAccess {
    /** Takes a persistable read+write grant; returns true when the grant is held afterwards. */
    fun takePersistable(uri: Uri): Boolean

    /** True when a persisted read+write grant for [uri] is held. */
    fun isPersisted(uri: Uri): Boolean

    /** A human-readable folder name for a tree URI, or "Folder" when it can't be resolved. */
    fun displayName(uri: Uri): String
}

@Singleton
class SafFolderAccess @Inject constructor(
    @ApplicationContext private val context: Context,
) : FolderAccess {

    override fun takePersistable(uri: Uri): Boolean {
        return try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            isPersisted(uri)
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    override fun isPersisted(uri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }

    override fun displayName(uri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri).substringAfterLast(':') }
                .getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: "Folder"
}
