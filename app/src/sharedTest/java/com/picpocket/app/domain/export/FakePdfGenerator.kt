package com.picpocket.app.domain.export

import android.content.Context
import android.net.Uri
import com.picpocket.app.data.model.Page

class FakePdfGenerator : PdfGenerator {

    var shouldFail = false
    val generatedPages = mutableListOf<List<Page>>()
    val redactedFlags = mutableListOf<Boolean>()

    override suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
        pageSize: PageSize,
        redact: Boolean,
    ): PdfResult {
        generatedPages.add(pages)
        redactedFlags.add(redact)
        return if (shouldFail) {
            PdfResult.Error(Exception("Fake PDF generation failed"))
        } else {
            PdfResult.Success(outputUri.toString())
        }
    }
}
