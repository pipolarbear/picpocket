package com.picpocket.app.domain.workflow.action

import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.picpocket.app.domain.storage.FolderAccess
import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Artifact
import com.picpocket.app.util.ZipUtil
import java.io.File
import javax.inject.Inject

/** Password-protects the current artifact (workflow encryption, "PKW1"). */
class EncryptAction @Inject constructor() : WorkflowAction {
    override val type = ActionType.ENCRYPT
    override val execution = Execution.HEADLESS

    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult {
        val passphrase = (params as? ActionParams.Encrypt)?.passphrase
            ?: return ActionResult.Failure("encrypt: passphrase missing")
        if (passphrase.isBlank()) return ActionResult.Failure("encrypt: passphrase is blank")
        val source = ctx.ensurePdf(input)
        val encrypted = ctx.cipher.encrypt(passphrase, source.readBytes(ctx))
        val out = File(ctx.workDir, "${ctx.document.id}.enc")
        out.writeBytes(encrypted)
        return ActionResult.Success(Artifact(Uri.fromFile(out).toString(), "application/octet-stream"))
    }
}

/** Zips the current artifact. */
class ZipAction @Inject constructor() : WorkflowAction {
    override val type = ActionType.ZIP
    override val execution = Execution.HEADLESS

    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult {
        val source = ctx.ensurePdf(input)
        val base = File(Uri.parse(source.uri).path ?: "artifact").name
        val zip = ZipUtil.create(
            ctx.workDir,
            ctx.document.id,
            listOf(base to Uri.parse(source.uri)),
            ctx.app.contentResolver,
        )
        return ActionResult.Success(Artifact(Uri.fromFile(zip).toString(), "application/zip"))
    }
}

/** Copies the current artifact into a chosen SAF folder and passes it on. */
class SaveToFolderAction @Inject constructor(
    private val folderAccess: FolderAccess,
) : WorkflowAction {
    override val type = ActionType.SAVE_TO_FOLDER
    override val execution = Execution.HEADLESS

    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult {
        val config = params as? ActionParams.SaveToFolder
            ?: return ActionResult.Failure("save-to-folder: folder not configured")
        if (config.folderUri.isBlank()) {
            return ActionResult.Failure("save-to-folder: folder not configured")
        }
        val treeUri = Uri.parse(config.folderUri)
        if (!folderAccess.isPersisted(treeUri)) {
            return ActionResult.Failure("save-to-folder: folder access was revoked; pick it again")
        }
        val source = ctx.ensurePdf(input)
        val folder = runCatching { DocumentFile.fromTreeUri(ctx.app, treeUri) }.getOrNull()
            ?: return ActionResult.Failure("save-to-folder: target folder not found")
        if (!runCatching { folder.exists() }.getOrDefault(false)) {
            return ActionResult.Failure("save-to-folder: target folder not found")
        }
        val name = "${ctx.document.name.replace(" ", "_").replace("/", "_")}.${source.extension()}"
        folder.findFile(name)?.delete()
        val created = folder.createFile(source.mime, name)
            ?: return ActionResult.Failure("save-to-folder: could not create $name")
        ctx.app.contentResolver.openInputStream(Uri.parse(source.uri))?.use { inputStream ->
            ctx.app.contentResolver.openOutputStream(created.uri)?.use { output ->
                inputStream.copyTo(output)
            }
        } ?: return ActionResult.Failure("save-to-folder: could not read artifact")
        return ActionResult.Success(source)
    }
}

/** Deletes the triggering document. Terminal: it never has dependents. */
class DeleteAction @Inject constructor() : WorkflowAction {
    override val type = ActionType.DELETE
    override val execution = Execution.HEADLESS

    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult =
        ctx.repository.deleteDocument(ctx.document.id).fold(
            onSuccess = { input?.let { ActionResult.Success(it) } ?: ActionResult.Skip },
            onFailure = { ActionResult.Failure("delete: ${it.message ?: "could not delete the document"}") },
        )
}

internal fun Artifact.readBytes(ctx: WorkflowContext): ByteArray {
    val uri = Uri.parse(uri)
    return if (uri.scheme == "file") {
        File(uri.path!!).readBytes()
    } else {
        ctx.app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("could not read artifact $uri")
    }
}

internal fun Artifact.extension(): String = when (mime) {
    "application/pdf" -> "pdf"
    "application/zip" -> "zip"
    else -> "bin"
}
