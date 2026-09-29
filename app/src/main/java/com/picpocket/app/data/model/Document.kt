package com.picpocket.app.data.model

typealias DocumentId = String

data class Document(
    val id: DocumentId,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pageCount: Int = 0,
    val totalFileSize: Long = 0,
    val qualityTier: Int = 0,
    val ocrComplete: Boolean = false,
    val pageSize: String? = null,
    /** Local-only last-opened time; falls back to [updatedAt] when never opened. */
    val lastAccessedAt: Long = 0,
)
