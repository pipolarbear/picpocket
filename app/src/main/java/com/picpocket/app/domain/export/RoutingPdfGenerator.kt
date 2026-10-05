package com.picpocket.app.domain.export

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.PageKind
import com.picpocket.app.domain.render.PageRenderer
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Chooses how to export: documents with native PDF pages are copied or merged
 * (no rasterizing); everything else uses the image-based searchable generator.
 * If native export fails, native pages are rasterized at high DPI so the export
 * can still complete through the image generator.
 */
@Singleton
class RoutingPdfGenerator @Inject constructor(
    private val searchable: SearchablePdfGenerator,
    private val native: NativePdfExporter,
    private val pageRenderer: PageRenderer,
) : PdfGenerator {

    override suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
        pageSize: PageSize,
    ): PdfResult {
        if (native.hasNativePages(pages)) {
            if (native.export(context, pages, outputUri, copyWhenUnchanged = true)) {
                return PdfResult.Success(outputUri.toString())
            }
            val rasterized = rasterize(context, pages)
            if (rasterized != null) {
                return searchable.generate(context, rasterized, outputUri, pageSize)
            }
            return PdfResult.Error(Exception("Could not export the document"))
        }
        return searchable.generate(context, pages, outputUri, pageSize)
    }

    /** Renders native pages to high-DPI images so the image generator can export them. */
    private suspend fun rasterize(context: Context, pages: List<Page>): List<Page>? {
        val dir = File(context.cacheDir, "native-export").apply { mkdirs() }
        val result = ArrayList<Page>(pages.size)
        for (page in pages) {
            if (page.kind == PageKind.IMAGE) {
                result.add(page)
                continue
            }
            val bitmap = pageRenderer.render(File(URI(page.imageUri)), PageKind.PDF, page.pdfPageIndex, 2400)
                ?: return null
            val file = File(dir, "native_${System.nanoTime()}.jpg")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            bitmap.recycle()
            result.add(
                page.copy(
                    kind = PageKind.IMAGE,
                    imageUri = Uri.fromFile(file).toString(),
                ),
            )
        }
        return result
    }
}
