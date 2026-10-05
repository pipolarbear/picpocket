package com.picpocket.app.data.model

data class Page(
    val id: Long,
    val documentId: DocumentId,
    val pageNumber: Int,
    val filename: String,
    val imageUri: String,
    val ocrText: String? = null,
    val filterTypeOrdinal: Int = 0,
    val createdAt: Long,
    val kind: PageKind = PageKind.IMAGE,
    val pdfPageIndex: Int = 0,
)
