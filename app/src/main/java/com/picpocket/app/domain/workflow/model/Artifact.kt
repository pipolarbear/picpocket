package com.picpocket.app.domain.workflow.model

/** The value flowing down the action tree: a file plus its MIME type. */
data class Artifact(val uri: String, val mime: String)

/** Outcome of a single action. */
sealed interface ActionResult {
    data class Success(val artifact: Artifact, val note: String? = null) : ActionResult
    data object Skip : ActionResult
    data class Failure(val reason: String) : ActionResult
}

enum class NodeStatus { SUCCESS, SKIPPED, FAILED, DEFERRED }

/** Outcome of one action within a run. */
@kotlinx.serialization.Serializable
data class NodeResult(
    val nodeId: String,
    val type: ActionType,
    val status: NodeStatus,
    val reason: String? = null,
    val durationMs: Long = 0,
    /** Extra success detail, e.g. the name/size of the saved file. */
    val note: String? = null,
)

enum class RunStatus { SUCCESS, PARTIAL_FAILURE, FAILURE, DEFERRED }

/** Outcome of a whole workflow run. */
data class RunResult(
    val status: RunStatus,
    val nodes: List<NodeResult>,
)
