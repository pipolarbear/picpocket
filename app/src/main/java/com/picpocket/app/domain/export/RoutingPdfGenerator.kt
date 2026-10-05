package com.picpocket.app.domain.export

import android.content.Context
import android.net.Uri
import com.picpocket.app.data.model.Page

/**
 * Chooses how to export: documents with native PDF pages are copied or merged
 * (no rasterizing); everything else uses the image-based searchable generator.
 */
class RoutingPdfGenerator(
    private val searchable: PdfGenerator,
    private val native: NativePdfExporter,
) : PdfGenerator {

    override suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
        pageSize: PageSize,
    ): PdfResult {
        if (native.hasNativePages(pages)) {
            val ok = native.export(context, pages, outputUri, copyWhenUnchanged = true)
            if (ok) return PdfResult.Success(outputUri.toString())
        }
        return searchable.generate(context, pages, outputUri, pageSize)
    }
}
