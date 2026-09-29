package com.picpocket.app.domain.workflow.engine

import android.app.Application
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.di.SearchablePdf
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.export.PdfGenerator
import com.picpocket.app.domain.workflow.action.ActionRegistry
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.Artifact
import com.picpocket.app.domain.workflow.model.CompareOp
import com.picpocket.app.domain.workflow.model.Condition
import com.picpocket.app.domain.workflow.model.NodeResult
import com.picpocket.app.domain.workflow.model.NodeStatus
import com.picpocket.app.domain.workflow.model.RunResult
import com.picpocket.app.domain.workflow.model.RunStatus
import com.picpocket.app.domain.workflow.model.TriggerEvent
import com.picpocket.app.domain.workflow.model.Workflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes a workflow's action forest: independent roots run concurrently, a
 * child runs only after its parent succeeds, and a failure skips only that
 * node's subtree. A parent's artifact is forked to each child.
 */
@Singleton
class WorkflowEngine @Inject constructor(
    private val app: Application,
    private val repository: DocumentRepository,
    @SearchablePdf private val pdfGenerator: PdfGenerator,
    private val cipher: ArtifactCipher,
    private val foreground: ForegroundExecutor,
    private val registry: ActionRegistry,
) {

    suspend fun run(
        workflow: Workflow,
        event: TriggerEvent,
        document: Document,
        ignoreTriggerCheck: Boolean = false,
    ): RunResult {
        if (!ignoreTriggerCheck && event !in workflow.triggers) return RunResult(RunStatus.SUCCESS, emptyList())
        val ctx = WorkflowContext(
            document = document,
            app = app,
            repository = repository,
            pdfGenerator = pdfGenerator,
            cipher = cipher,
            foreground = foreground,
            pageSize = resolvePageSize(document),
        )
        if (!conditionsHold(workflow, ctx)) return RunResult(RunStatus.SUCCESS, emptyList())

        // Rendering the document PDF is the implicit first step of every run;
        // each root receives it as its artifact.
        val rootArtifact = try {
            ctx.renderPdf()
        } catch (e: Exception) {
            return RunResult(RunStatus.FAILURE, emptyList())
        }

        val results = Collections.synchronizedList(mutableListOf<NodeResult>())
        coroutineScope {
            workflow.roots.forEach { root -> launch { runNode(root, ctx, rootArtifact, results) } }
        }
        return RunResult(overallStatus(results), results.toList())
    }

    private suspend fun runNode(
        node: ActionNode,
        ctx: WorkflowContext,
        input: Artifact?,
        results: MutableList<NodeResult>,
    ) {
        val action = registry.action(node.type)
        val start = System.currentTimeMillis()
        val outcome = try {
            action.execute(ctx, input, node.params)
        } catch (e: Exception) {
            ActionResult.Failure(e.message ?: e::class.java.simpleName)
        }
        val duration = System.currentTimeMillis() - start
        when (outcome) {
            is ActionResult.Success -> {
                results.add(NodeResult(node.id, node.type, NodeStatus.SUCCESS, null, duration))
                if (node.children.isNotEmpty()) {
                    coroutineScope {
                        node.children.forEach { child ->
                            launch { runNode(child, ctx, outcome.artifact, results) }
                        }
                    }
                }
            }
            ActionResult.Skip ->
                results.add(NodeResult(node.id, node.type, NodeStatus.SKIPPED, null, duration))
            is ActionResult.Failure ->
                results.add(NodeResult(node.id, node.type, NodeStatus.FAILED, outcome.reason, duration))
        }
    }

    private suspend fun conditionsHold(workflow: Workflow, ctx: WorkflowContext): Boolean =
        workflow.conditions.all { holds(it, ctx) }

    private suspend fun holds(condition: Condition, ctx: WorkflowContext): Boolean = when (condition) {
        is Condition.HasTag -> ctx.hasTag(condition.tagId)
        is Condition.PageCount -> compare(ctx.document.pageCount, condition.op, condition.n)
        is Condition.NameMatches ->
            runCatching { Regex(condition.pattern).containsMatchIn(ctx.document.name) }.getOrDefault(false)
        is Condition.OcrTextMatches ->
            runCatching { Regex(condition.pattern).containsMatchIn(ctx.ocrText()) }.getOrDefault(false)
    }

    private fun compare(actual: Int, op: CompareOp, expected: Int): Boolean = when (op) {
        CompareOp.GE -> actual >= expected
        CompareOp.LE -> actual <= expected
        CompareOp.EQ -> actual == expected
    }

    private fun overallStatus(results: List<NodeResult>): RunStatus {
        val failed = results.count { it.status == NodeStatus.FAILED }
        val succeeded = results.count { it.status == NodeStatus.SUCCESS }
        return when {
            failed == 0 -> RunStatus.SUCCESS
            succeeded > 0 -> RunStatus.PARTIAL_FAILURE
            else -> RunStatus.FAILURE
        }
    }

    private fun resolvePageSize(document: Document): PageSize {
        val prefs = app.getSharedPreferences("settings", 0)
        return document.pageSize?.let { runCatching { PageSize.valueOf(it) }.getOrNull() }
            ?: prefs.getString("page_size", PageSize.A4.name)
                ?.let { runCatching { PageSize.valueOf(it) }.getOrNull() }
            ?: PageSize.A4
    }
}
