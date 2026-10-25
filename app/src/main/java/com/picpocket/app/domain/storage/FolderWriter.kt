package com.picpocket.app.domain.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton

/** A file that was created inside a picked SAF folder. */
data class SavedFile(val uri: Uri, val name: String, val size: Long)

/**
 * Copies a local file into a user-picked SAF folder. A picked folder is a
 * *tree* URI, not a file: the destination file must be created inside it first.
 * Every write is verified and partial files are removed on failure. An existing
 * file with the same name is only replaced after the new copy is verified.
 */
interface FolderWriter {
    suspend fun write(treeUri: Uri, name: String, mime: String, source: Uri): Result<SavedFile>

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
    ): Result<SavedFile> = withContext(Dispatchers.IO) {
        val folder = resolve(treeUri).getOrElse { return@withContext Result.failure(it) }
        runCatching { save(folder, treeUri, name, mime, source) }
            .fold(
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

    private suspend fun save(
        folder: DocumentFile,
        treeUri: Uri,
        name: String,
        mime: String,
        source: Uri,
    ): SavedFile {
        val existing = folder.findFile(name)
        // Write under a temporary name while an old copy still exists. The old
        // file is only removed after the new copy has settled.
        val targetName = if (existing != null) temporaryName(name) else name
        val created = folder.createFile(mime, targetName)
            ?: throw IOException("could not create $targetName in ${displayName(treeUri)}")

        val expected = sourceSize(source)
        try {
            writeInto(source, created, expected)
            awaitSettled(folder, targetName, expected)
        } catch (e: Exception) {
            runCatching { created.delete() }
            throw e
        }

        if (existing != null) {
            if (!existing.delete()) {
                runCatching { created.delete() }
                throw IOException("could not replace the existing $name")
            }
            if (!created.renameTo(name)) {
                // The new copy is safe but keeps the temporary name.
                return SavedFile(created.uri, targetName, expected ?: created.length())
            }
        }
        return SavedFile(created.uri, if (existing != null) name else targetName, expected ?: created.length())
    }

    private fun writeInto(source: Uri, created: DocumentFile, expected: Long?) {
        val copied = copyArtifact(
            input = context.contentResolver.openInputStream(source),
            output = context.contentResolver.openOutputStream(created.uri),
        )
        writeProblem(expected, copied, -1L)?.let { throw IOException(it) }
    }

    /**
     * Some providers accept the bytes before their folder listing shows the
     * file. Poll until the file is visible with the expected size; if it never
     * appears, the caller fails the save and keeps the local document.
     */
    private suspend fun awaitSettled(folder: DocumentFile, name: String, expected: Long?) {
        val deadline = System.currentTimeMillis() + SETTLE_WINDOW_MS
        while (true) {
            runCatching { context.contentResolver.refresh(folder.uri, null, null) }
            val file = runCatching { folder.findFile(name) }.getOrNull()
            val length = file?.let { runCatching { it.length() }.getOrDefault(-1L) } ?: -1L
            if (file != null && (expected == null || expected < 0 || length == expected)) return
            if (System.currentTimeMillis() >= deadline) {
                throw IOException("$name did not settle in the folder after writing")
            }
            delay(SETTLE_POLL_MS)
        }
    }

    private fun resolve(uri: Uri): Result<DocumentFile> {
        val folder = DocumentFile.fromTreeUri(context, uri)
            ?: DocumentFile.fromSingleUri(context, uri)
            ?: return Result.failure(IOException("could not open the picked folder"))
        if (!folder.exists()) return Result.failure(IOException("the picked folder no longer exists"))
        if (!folder.isDirectory) return Result.failure(IOException("the picked location is not a folder"))
        return Result.success(folder)
    }

    private fun sourceSize(uri: Uri): Long? {
        if (uri.scheme == "file") {
            return uri.path?.let { File(it).length() }?.takeIf { it >= 0 }
        }
        return runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()?.takeIf { it >= 0 }
    }

    private fun temporaryName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        return "$base.tmp-${System.currentTimeMillis()}$extension"
    }

    private companion object {
        const val SETTLE_WINDOW_MS = 30_000L
        const val SETTLE_POLL_MS = 2_000L
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
