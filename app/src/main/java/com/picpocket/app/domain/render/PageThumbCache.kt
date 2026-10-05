package com.picpocket.app.domain.render

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.picpocket.app.data.model.Page
import com.picpocket.app.data.model.PageKind
import com.picpocket.app.data.store.PageNaming
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Derived, regenerable thumbnails for pages that can't be loaded directly (PDF
 * pages). Cached under the app cache keyed by source filename + page index, so a
 * changed source naturally misses. Never authoritative: missing thumbnails are
 * regenerated on demand.
 */
@Singleton
class PageThumbCache @Inject constructor(
    private val app: Application,
    private val pageRenderer: PageRenderer,
) {

    suspend fun thumbnail(page: Page, targetWidth: Int = 400): Bitmap? =
        withContext(Dispatchers.IO) {
            val source = File(URI(page.imageUri))
            if (page.kind == PageKind.IMAGE) {
                return@withContext pageRenderer.render(source, PageKind.IMAGE, 0, targetWidth)
            }
            val key = "${page.filename}:${page.pdfPageIndex}:$targetWidth"
            val dir = File(app.cacheDir, "page-thumbs").apply { mkdirs() }
            val cached = File(dir, PageNaming.filenameFor(key.toByteArray(), "jpg"))
            if (cached.exists()) {
                BitmapFactory.decodeFile(cached.absolutePath)?.let { return@withContext it }
            }
            val bitmap = pageRenderer.render(source, PageKind.PDF, page.pdfPageIndex, targetWidth)
                ?: return@withContext null
            runCatching {
                cached.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            }
            bitmap
        }
}
