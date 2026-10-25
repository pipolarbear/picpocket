package com.picpocket.app.domain.workflow.engine

import android.app.Application
import com.picpocket.app.data.model.Document
import com.picpocket.app.data.repository.DocumentRepository
import com.picpocket.app.data.repository.WorkflowRepository
import com.picpocket.app.di.SearchablePdf
import com.picpocket.app.domain.export.PageSize
import com.picpocket.app.domain.export.PdfGenerator
import com.picpocket.app.domain.network.NetworkMonitor
import com.picpocket.app.domain.storage.FolderAccess
import com.picpocket.app.domain.workflow.action.ActionRegistry
import com.picpocket.app.domain.workflow.crypto.ArtifactCipher
import com.picpocket.app.domain.workflow.model.ActionNode
import com.picpocket.app.domain.workflow.model.ActionResult
import com.picpocket.app.domain.workflow.model.ActionType
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.Collections
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Executes a workflow's action forest: independent roots run concurrently, a
 * child runs only after its parent succeeds, and a failure skips only that
 * node's subtree. A parent's artifact is forked to each child.
 *
 * A run whose actions need a network connection is queued (via
 * [WorkflowRepository.enqueueDeferred]) and reported as [RunStatus.DEFERRED]
 * when the device is offline, so nothing — including a `delete` — runs.
 * Terminal `delete` nodes are executed last and are skipped whenever an earlier
 * action failed or was deferred, so a document is never removed when its copy
 * never made it out.
 */
@Singleton
class WorkflowEngine @Inject constructor(
    private val app: Application,
    private val repository: DocumentRepository,
    private val workflowRepository: WorkflowRepository,
    @SearchablePdf private val pdfGenerator: PdfGenerator,
    private val cipher: ArtifactCipher,
    private val foreground: ForegroundExecutor,
    private val registry: ActionRegistry,
    private val folderAccess: FolderAccess,
    private val network: NetworkMonitor,
) {

    suspend fun run(
        workflow: Workflow,
        event: TriggerEvent,
        document: Document,
        ignoreTriggerCheck: Boolean = false,
        checkConditions: Boolean = true,
        enqueueWhenDeferred: Boolean = true,
    ): RunResult {
        if (!ignoreTriggerCheck && event !in workflow.triggers) return RunResult(RunStatus.SUCCESS, emptyList())
        val ctx = WorkflowContext(
            document = document,
            app = app,
            repository = repository,
            pdfGenerator = pdfGenerator,
            cipher = cipher,
            foreground = foreground,
            folderAccess = folderAccess,
            pageSize = resolvePageSize(document),
        )
        if (checkConditions && !conditionsHold(workflow, ctx)) return RunResult(RunStatus.SUCCESS, emptyList())

        // Nothing runs while the device is offline: the whole run is queued so a
        // later `delete` can't outrun the network action.
        if (requiresNetwork(workflow, ctx) && !network.isOnline()) {
            // An unsaved workflow (id 0) has nothing to reload on replay, so it
            // is reported as deferred without being queued.
            if (enqueueWhenDeferred && workflow.id != 0L) {
                workflowRepository.enqueueDeferred(workflow, event, document.id, document.name)
            }
            return RunResult(RunStatus.DEFERRED, deferredNodes(workflow))
        }

        // Rendering the document PDF is the implicit first step of every run;
        // each root receives it as its artifact.
        val rootArtifact = try {
            ctx.renderPdf()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RunResult(RunStatus.FAILURE, emptyList())
        }

        val results = Collections.synchronizedList(mutableListOf<NodeResult>())
        val deletes = Collections.synchronizedList(mutableListOf<Pair<ActionNode, Artifact?>>())
        coroutineScope {
            workflow.roots.forEach { root ->
                launch {
                    if (root.type == ActionType.DELETE) deletes.add(root to rootArtifact)
                    else runNode(root, ctx, rootArtifact, results, deletes)
                }
            }
        }

        // Deletes run only after every branch has settled, and only when nothing
        // failed or was deferred. Otherwise the document is kept.
        if (results.none { it.status == NodeStatus.FAILED || it.status == NodeStatus.DEFERRED }) {
            deletes.forEach { (node, input) -> executeNode(node, ctx, input, results) }
        } else {
            deletes.forEach { (node, _) ->
                results.add(
                    NodeResult(
                        node.id,
                        node.type,
                        NodeStatus.SKIPPED,
                        "Kept the document: an earlier step failed or is waiting for network",
                    ),
                )
            }
        }
        return RunResult(overallStatus(results), results.toList())
    }

    /** Runs a non-delete node and recurses into its children (deletes are collected). */
    private suspend fun runNode(
        node: ActionNode,
        ctx: WorkflowContext,
        input: Artifact?,
        results: MutableList<NodeResult>,
        deletes: MutableList<Pair<ActionNode, Artifact?>>,
    ) {
        val outcome = executeNode(node, ctx, input, results)
        if (outcome is ActionResult.Success && node.children.isNotEmpty()) {
            coroutineScope {
                node.children.forEach { child ->
                    launch {
                        if (child.type == ActionType.DELETE) deletes.add(child to outcome.artifact)
                        else runNode(child, ctx, outcome.artifact, results, deletes)
                    }
                }
            }
        }
    }

    /** Executes a single action and appends its outcome to [results]. */
    private suspend fun executeNode(
        node: ActionNode,
        ctx: WorkflowContext,
        input: Artifact?,
        results: MutableList<NodeResult>,
    ): ActionResult {
        val action = registry.action(node.type)
        val start = System.currentTimeMillis()
        val outcome = try {
            action.execute(ctx, input, node.params)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ActionResult.Failure(e.message ?: e::class.java.simpleName)
        }
        val duration = System.currentTimeMillis() - start
        results.add(
            when (outcome) {
                is ActionResult.Success ->
                    NodeResult(node.id, node.type, NodeStatus.SUCCESS, null, duration, outcome.note)
                ActionResult.Skip -> NodeResult(node.id, node.type, NodeStatus.SKIPPED, null, duration)
                is ActionResult.Failure -> NodeResult(node.id, node.type, NodeStatus.FAILED, outcome.reason, duration)
            },
        )
        return outcome
    }

    private fun requiresNetwork(workflow: Workflow, ctx: WorkflowContext): Boolean =
        workflow.roots.any { subtreeRequiresNetwork(it, ctx) }

    private fun subtreeRequiresNetwork(node: ActionNode, ctx: WorkflowContext): Boolean =
        registry.action(node.type).requiresNetwork(ctx, node.params) ||
            node.children.any { subtreeRequiresNetwork(it, ctx) }

    private fun deferredNodes(workflow: Workflow): List<NodeResult> =
        workflow.roots.flatMap { deferredNodes(it) }

    private fun deferredNodes(node: ActionNode): List<NodeResult> =
        listOf(NodeResult(node.id, node.type, NodeStatus.DEFERRED, "Waiting for network")) +
            node.children.flatMap { deferredNodes(it) }

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
