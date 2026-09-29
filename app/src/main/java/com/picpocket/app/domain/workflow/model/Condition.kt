package com.picpocket.app.domain.workflow.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A precondition; all conditions on a workflow must hold (AND). */
@Serializable
sealed interface Condition {
    @Serializable
    @SerialName("has_tag")
    data class HasTag(val tagId: Long) : Condition

    @Serializable
    @SerialName("page_count")
    data class PageCount(val op: CompareOp, val n: Int) : Condition

    @Serializable
    @SerialName("name_matches")
    data class NameMatches(val pattern: String) : Condition

    @Serializable
    @SerialName("ocr_text_matches")
    data class OcrTextMatches(val pattern: String) : Condition
}
