package com.picpocket.app.domain.workflow.model

import kotlinx.serialization.Serializable

/** Events that can trigger a workflow. */
enum class TriggerEvent(val value: String) {
    DOC_CREATED("doc.created"),
    PAGES_ADDED("doc.pages_added"),
    PAGE_RESCANNED("doc.page_rescanned"),
    PAGE_REMOVED("doc.page_removed"),
    PAGES_REORDERED("doc.pages_reordered"),
    RENAMED("doc.renamed"),
    TAGGED("doc.tagged"),
    UNTAGGED("doc.untagged"),
    META_CHANGED("doc.meta_changed"),
    DELETED("doc.deleted"),
    MANUAL("manual"),
    ;

    companion object {
        fun from(value: String): TriggerEvent? = entries.firstOrNull { it.value == value }

        /** Triggers the user can pick in the editor; manual runs bypass the trigger check. */
        val selectable: List<TriggerEvent> get() = entries.filter { it != MANUAL }
    }
}

enum class ActionType(val value: String) {
    ENCRYPT("encrypt"),
    ZIP("zip"),
    SAVE_TO_FOLDER("save-to-folder"),
    SEND_TO_APP("send-to-app"),
    NOTIFY("notify"),
    DELETE("delete"),
    ;

    /** Terminal actions never have dependents (e.g. deleting the document). */
    val terminal: Boolean get() = this == DELETE

    companion object {
        fun from(value: String): ActionType? = entries.firstOrNull { it.value == value }
    }
}

enum class CompareOp { GE, LE, EQ }

/**
 * A node in the action dependency forest: the action plus its dependents.
 * Children run after this node succeeds; siblings run in parallel. A node has
 * at most one parent (no joins).
 */
@Serializable
data class ActionNode(
    val id: String,
    val type: ActionType,
    val params: ActionParams = ActionParams.None,
    val children: List<ActionNode> = emptyList(),
)

/**
 * A generic workflow rule: triggers (any-of), conditions (all-of) and a forest
 * of actions (independent roots).
 */
@Serializable
data class Workflow(
    val id: Long = 0,
    val name: String,
    val enabled: Boolean = true,
    val triggers: List<TriggerEvent> = emptyList(),
    val conditions: List<Condition> = emptyList(),
    val roots: List<ActionNode> = emptyList(),
) {
    /** True when any condition refers to the given tag. */
    fun referencesTag(tagId: Long): Boolean =
        conditions.any { it is Condition.HasTag && it.tagId == tagId }
}
