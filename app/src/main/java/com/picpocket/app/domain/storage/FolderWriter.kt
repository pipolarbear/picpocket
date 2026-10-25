package com.picpocket.app.domain.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Copies a local file into a user-picked SAF folder. A picked folder is a
 * *tree* URI, not a file: the destination file must be created inside it first.
 * Every write is verified and partial files are removed on failure.
 */
interface FolderWriter {
    suspend fun write(treeUri: Uri, name: String, mime: String, source: Uri): Result<Uri>

    /** Creates or reuses a subdirectory inside [treeUri]. */
    suspend fun createDirectory(treeUri: Uri, name: String): Result<Uri>

    /** A user-facing name for the picked folder. */
    fun displayName(uri: Uri): String
}

@Singleton
class SafFolderWriter @Inject constructor(
    @ApplicationContext private val context: Context,
) : FolderWriter {

    override suspend fun write(
        treeUri: Uri,
        name: String,
        mime: String,
        source: Uri,
    ): Result<Uri> = withContext(Dispatchers.IO) {
        val folder = resolve(treeUri).getOrElse { return@withContext Result.failure(it) }
        runCatching {
            folder.findFile(name)?.delete()
            val created = folder.createFile(mime, name)
                ?: throw IOException("could not create $name in ${displayName(treeUri)}")
            try {
                writeInto(source, created)
                created.uri
            } catch (e: Exception) {
                runCatching { created.delete() }
                throw e
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { Result.failure(it) },
        )
    }

    override suspend fun createDirectory(treeUri: Uri, name: String): Result<Uri> =
        withContext(Dispatchers.IO) {
            val folder = resolve(treeUri).getOrElse { return@withContext Result.failure(it) }
            runCatching {
                folder.findFile(name)?.takeIf { it.isDirectory }?.uri
                    ?: folder.createDirectory(name)?.uri
                    ?: throw IOException("could not create the folder $name")
            }.fold(
                onSuccess = { Result.success(it) },
                onFailure = { Result.failure(it) },
            )
        }

    override fun displayName(uri: Uri): String =
        runCatching { DocumentFile.fromTreeUri(context, uri)?.name }.getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: runCatching { DocumentFile.fromSingleUri(context, uri)?.name }.getOrNull()
                ?.takeIf { it.isNotBlank() }
            ?: "the selected folder"

    private fun resolve(uri: Uri): Result<DocumentFile> {
        val folder = DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: return Result.failure(IOException("could not open the picked folder"))
        if (!folder.exists()) return Result.failure(IOException("the picked folder no longer exists"))
        if (!folder.isDirectory) return Result.failure(IOException("the picked location is not a folder"))
        return Result.success(folder)
    }

    private fun writeInto(source: Uri, created: DocumentFile) {
        val expected = sourceSize(source)
        val copied = copyArtifact(
            input = context.contentResolver.openInputStream(source),
            output = context.contentResolver.openOutputStream(created.uri),
        )
        val stored = runCatching { created.length() }.getOrDefault(-1L)
        writeProblem(expected, copied, stored)?.let { throw IOException(it) }
    }

    private fun sourceSize(uri: Uri): Long? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).length() }?.takeIf { it >= 0 }
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0 }
    }
}

/**
 * Copies [input] to [output], failing when either stream is missing. A null
 * output stream must never be treated as success: nothing was written.
 */
internal fun copyArtifact(input: InputStream?, output: OutputStream?): Long {
    val source = input ?: throw IOException("could not read the source file")
    if (output == null) {
        source.close()
        throw IOException("could not open the destination for writing")
    }
    return source.use { from -> output.use { to -> from.copyTo(to) } }
}

/** Returns a reason when the write did not produce the expected number of bytes. */
internal fun writeProblem(expected: Long?, copied: Long, stored: Long): String? {
    if (expected != null && expected >= 0 && copied != expected) {
        return "wrote $copied of $expected bytes"
    }
    // Some providers report an unknown length as 0 or -1; only distrust a
    // positive length that disagrees with what we wrote.
    if (expected != null && expected >= 0 && stored > 0 && stored != expected) {
        return "the folder reports $stored bytes after writing $expected"
    }
    return null
}
