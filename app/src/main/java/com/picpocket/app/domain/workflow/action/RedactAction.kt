package com.picpocket.app.domain.workflow.action

import com.picpocket.app.domain.workflow.engine.WorkflowContext
import com.picpocket.app.domain.workflow.model.ActionParams
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
import com.picpocket.app.domain.workflow.model.Artifact
import javax.inject.Inject

/**
 * Turns the current artifact into the document's redacted PDF, so every action
 * after it (save, share, encrypt, zip) carries the version with the marked
 * areas hidden. Fails when the document has nothing marked, instead of quietly
 * exporting the original.
 */
class RedactAction @Inject constructor() : WorkflowAction {
    override val type = ActionType.REDACT
    override val execution = Execution.HEADLESS

    override suspend fun execute(ctx: WorkflowContext, input: Artifact?, params: ActionParams): ActionResult {
        if (!ctx.document.hasRedactions) {
            return ActionResult.Failure("redact: this document has no redacted areas")
        }
        val source = ctx.ensurePdf(input, redact = true)
        return ActionResult.Success(source, note = "Redacted areas removed")
    }
}
