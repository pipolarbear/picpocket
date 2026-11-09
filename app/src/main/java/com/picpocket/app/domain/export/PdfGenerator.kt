package com.picpocket.app.domain.export

import android.content.Context
import android.net.Uri
import com.picpocket.app.data.model.Page

interface PdfGenerator {
    /**
     * @param redact when true, every page's [Page.redactions] areas are hidden in
     *   the output (mosaic, with the matching text-layer words dropped).
     */
    suspend fun generate(
        context: Context,
        pages: List<Page>,
        outputUri: Uri,
        pageSize: PageSize = PageSize.A4,
        redact: Boolean = false,
    ): PdfResult
}
