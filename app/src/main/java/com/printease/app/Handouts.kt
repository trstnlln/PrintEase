package com.printease.app

import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.util.Matrix
import java.io.File

val slideLayouts = listOf(1, 2, 4, 6, 9)
private val grids = mapOf(2 to (2 to 1), 4 to (2 to 2), 6 to (2 to 3), 9 to (3 to 3))

val PrintOptions.printedSides: Int
    get() = (selectedPages + slidesPerSide - 1) / slidesPerSide

fun PrintOptions.handoutOptions(): PrintOptions =
    if (slidesPerSide == 1) this
    else copy(pages = printedSides, pageRanges = emptyList(), actualSize = false, slidesPerSide = 1)

internal data class PdfPageBounds(val box: PDRectangle, val rotation: Int) {
    constructor(page: PDPage) : this(page.cropBox, page.rotation)

    val sideways = rotation == 90 || rotation == 270
    val width: Float
        get() = if (sideways) box.height else box.width

    val height: Float
        get() = if (sideways) box.width else box.height

    fun placement(x: Float, y: Float, scale: Float): Matrix {
        val a =
            when (rotation) {
                180 -> -scale
                90,
                270 -> 0f
                else -> scale
            }
        val b =
            when (rotation) {
                90 -> -scale
                270 -> scale
                else -> 0f
            }
        val c = -b
        val d = a
        val left =
            x +
                when (rotation) {
                    180 -> box.width * scale
                    270 -> box.height * scale
                    else -> 0f
                }
        val bottom =
            y +
                when (rotation) {
                    90 -> box.width * scale
                    180 -> box.height * scale
                    else -> 0f
                }
        return Matrix(
            a,
            b,
            c,
            d,
            left - a * box.lowerLeftX - c * box.lowerLeftY,
            bottom - b * box.lowerLeftX - d * box.lowerLeftY,
        )
    }
}

internal fun PrintOptions.sourcePages(): List<Int> =
    pageRanges.ifEmpty { listOf(PageRange(1, pages)) }.flatMap { it.start - 1 until it.end }

internal fun PDPageContentStream.drawPdfPage(
    layers: LayerUtility,
    source: PDDocument,
    page: PDPage,
    placement: Matrix,
) {
    if (!page.hasContents()) return
    val form = layers.importPageAsForm(source, page)
    form.setMatrix(Matrix().createAffineTransform())
    saveGraphicsState()
    transform(placement)
    drawForm(form)
    restoreGraphicsState()
}

internal fun writeHandout(pdf: PdfFile, options: PrintOptions, target: File) {
    val (columns, rows) = grids.getValue(options.slidesPerSide)
    val selected = options.sourcePages()
    PDDocument.load(File(pdf.path)).use { source ->
        require(source.numberOfPages == options.pages) { "The PDF page count changed." }
        PDDocument().use { output ->
            // A retry must upload the same bytes for the same request and settings.
            output.documentId = pdf.sha256.take(15).toLong(16)
            val first = PdfPageBounds(source.getPage(selected.first()))
            val paper =
                printGeometry(
                    options.paper,
                    (first.width * columns).toInt().coerceAtLeast(1),
                    (first.height * rows).toInt().coerceAtLeast(1),
                    false,
                )
            val gap = minOf(8f, paper.margin / 2)
            val cellWidth = (paper.width - 2 * paper.margin - (columns - 1) * gap) / columns
            val cellHeight = (paper.height - 2 * paper.margin - (rows - 1) * gap) / rows
            val layers = LayerUtility(output)
            selected.chunked(options.slidesPerSide).forEach { pages ->
                val sheet = PDPage(PDRectangle(paper.width, paper.height))
                output.addPage(sheet)
                PDPageContentStream(output, sheet).use { stream ->
                    pages.forEachIndexed { index, pageNumber ->
                        val original = source.getPage(pageNumber)
                        if (original.hasContents()) {
                            val slide = PdfPageBounds(original)
                            val scale = minOf(cellWidth / slide.width, cellHeight / slide.height)
                            val column = index % columns
                            val row = index / columns
                            val x =
                                paper.margin +
                                    column * (cellWidth + gap) +
                                    (cellWidth - slide.width * scale) / 2
                            val y =
                                paper.height - paper.margin - (row + 1) * cellHeight - row * gap +
                                    (cellHeight - slide.height * scale) / 2
                            stream.drawPdfPage(
                                layers,
                                source,
                                original,
                                slide.placement(x, y, scale),
                            )
                        }
                    }
                }
            }
            output.save(target)
        }
    }
}
