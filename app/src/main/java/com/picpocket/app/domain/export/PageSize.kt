package com.picpocket.app.domain.export

enum class PageSize(
    val label: String,
    val shortLabel: String,
    val widthPt: Int,
    val heightPt: Int,
) {
    IMAGE("Match image", "Image", 0, 0),
    A0("A0 (841×1189mm)", "A0", 2384, 3370),
    A1("A1 (594×841mm)", "A1", 1684, 2384),
    A2("A2 (420×594mm)", "A2", 1191, 1684),
    A3("A3 (297×420mm)", "A3", 842, 1191),
    A4("A4 (210×297mm)", "A4", 595, 842),
    A5("A5 (148×210mm)", "A5", 420, 595),
    A6("A6 (105×148mm)", "A6", 298, 420),
    LETTER("Letter (8.5×11in)", "Letter", 612, 792),
    LEGAL("Legal (8.5×14in)", "Legal", 612, 1008),
    TABLOID("Tabloid (11×17in)", "Tabloid", 792, 1224),
    ID_CARD("ID card (85.6×54mm)", "ID", 243, 153),
}

/**
 * Page dimensions in PDF points. For [PageSize.IMAGE] the page matches the
 * image's own aspect and pixel size (capped to the PDF 14400pt limit), so the
 * image is drawn edge-to-edge with no letterboxing. Otherwise the chosen paper
 * size is used, rotated for landscape images.
 */
fun pageDimensionsFor(pageSize: PageSize, bitmapW: Int, bitmapH: Int): Pair<Int, Int> {
    if (pageSize == PageSize.IMAGE) {
        val scale = minOf(1f, 14400f / maxOf(bitmapW, bitmapH))
        return maxOf(1, (bitmapW * scale).toInt()) to maxOf(1, (bitmapH * scale).toInt())
    }
    val isLandscape = bitmapW > bitmapH
    return if (isLandscape) pageSize.heightPt to pageSize.widthPt else pageSize.widthPt to pageSize.heightPt
}
