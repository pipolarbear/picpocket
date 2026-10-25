package com.picpocket.app.domain.storage

import android.net.Uri

/** In-memory FolderWriter for tests. */
class FakeFolderWriter : FolderWriter {

    var writeResult: Result<Uri>? = null
    var directoryResult: Result<Uri>? = null
    var displayName: String = "Drive"

    val writes = mutableListOf<Write>()

    data class Write(val treeUri: Uri, val name: String, val mime: String, val source: Uri)

    override suspend fun write(treeUri: Uri, name: String, mime: String, source: Uri): Result<Uri> {
        writes += Write(treeUri, name, mime, source)
        return writeResult ?: Result.success(Uri.parse("content://fake/file.pdf"))
    }

    override suspend fun createDirectory(treeUri: Uri, name: String): Result<Uri> =
        directoryResult ?: Result.success(Uri.parse("content://fake/dir"))

    override fun displayName(uri: Uri): String = displayName
}
