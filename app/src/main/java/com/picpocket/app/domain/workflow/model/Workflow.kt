package com.picpocket.app.domain.workflow.model

import kotlinx.serialization.Serializable

/** Events that can trigger a workflow. [label] is what the UI shows. */
enum class TriggerEvent(val value: String, val label: String) {
    DOC_CREATED("doc.created", "Created"),
    PAGES_ADDED("doc.pages_added", "Pages added"),
    PAGE_RESCANNED("doc.page_rescanned", "Page rescanned"),
    PAGE_REMOVED("doc.page_removed", "Page removed"),
    PAGES_REORDERED("doc.pages_reordered", "Pages reordered"),
    RENAMED("doc.renamed", "Renamed"),
    TAGGED("doc.tagged", "Tagged"),
    UNTAGGED("doc.untagged", "Untagged"),

    /** Never emitted. Kept only so stored workflows that selected it still decode. */
    META_CHANGED("doc.meta_changed", "Meta changed"),
    DELETED("doc.deleted", "Deleted"),
    MANUAL("manual", "Manual"),
    ;

    companion object {
        fun from(value: String): TriggerEvent? = entries.firstOrNull { it.value == value }

        /** Triggers the user can pick in the editor; manual runs bypass the trigger check. */
        val selectable: List<TriggerEvent> get() = entries.filter { it != MANUAL && it != META_CHANGED }
    }
}

/** What an action does. [label] is what the UI shows; [value] is the stored wire name. */
enum class ActionType(val value: String, val label: String) {
    ENCRYPT("encrypt", "Encrypt"),
    ZIP("zip", "Zip"),
    SAVE_TO_FOLDER("save-to-folder", "Save to folder"),
    SEND_TO_APP("send-to-app", "Send to app"),
    NOTIFY("notify", "Notify"),
    DELETE("delete", "Delete document"),
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
