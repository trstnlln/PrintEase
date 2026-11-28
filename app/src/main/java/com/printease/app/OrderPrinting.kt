package com.printease.app

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.print.PageRange as PrinterRange
import android.print.PrintAttributes
import android.print.PrintDocumentAdapter
import android.print.PrintDocumentInfo
import android.print.PrintManager
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun printAttributes(options: PrintOptions, landscape: Boolean): PrintAttributes {
    val size =
        when (options.paper) {
            "A4" -> PrintAttributes.MediaSize.ISO_A4
            "Letter" -> PrintAttributes.MediaSize.NA_LETTER
            "Legal" -> PrintAttributes.MediaSize.NA_LEGAL
            "A3" -> PrintAttributes.MediaSize.ISO_A3
            "A5" -> PrintAttributes.MediaSize.ISO_A5
            else -> {
                val (width, height) = paperSizeMm(options.paper)
                PrintAttributes.MediaSize(
                    "printease-${options.paper}",
                    paperLabel(options.paper),
                    (width * 1000 / 25.4f).roundToInt(),
                    (height * 1000 / 25.4f).roundToInt(),
                )
            }
        }
    return PrintAttributes.Builder()
        .setMediaSize(if (landscape) size.asLandscape() else size.asPortrait())
        .setColorMode(
            if (options.color) PrintAttributes.COLOR_MODE_COLOR
            else PrintAttributes.COLOR_MODE_MONOCHROME
        )
        .setDuplexMode(
            if (!options.duplex) PrintAttributes.DUPLEX_MODE_NONE
            else if (landscape) PrintAttributes.DUPLEX_MODE_SHORT_EDGE
            else PrintAttributes.DUPLEX_MODE_LONG_EDGE
        )
        .build()
}

internal suspend fun printOrderFile(
    activity: Activity,
    filename: String,
    file: File,
    options: PrintOptions,
) {
    val manager =
        activity.getSystemService(Context.PRINT_SERVICE) as? PrintManager
            ?: error("Printing is unavailable on this device.")
    val landscape =
        withContext(Dispatchers.IO) {
            PDFBoxResourceLoader.init(activity.applicationContext)
            PDDocument.load(file).use { source ->
                require(source.numberOfPages == options.pages) {
                    "The PDF no longer matches the order."
                }
                val first = PdfPageBounds(source.getPage(options.sourcePages().first()))
                first.width > first.height
            }
        }
    val attributes = printAttributes(options, landscape)
    manager.print(filename, OrderPrintAdapter(filename, file, options, attributes), attributes)
}

private class OrderPrintAdapter(
    private val filename: String,
    private val file: File,
    private val options: PrintOptions,
    initial: PrintAttributes,
) : PrintDocumentAdapter() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val pages = options.sourcePages()
    private val defaultMedia = requireNotNull(initial.mediaSize)
    private var attributes = initial

    override fun onLayout(
        oldAttributes: PrintAttributes?,
        newAttributes: PrintAttributes,
        cancellation: CancellationSignal,
        callback: LayoutResultCallback,
        extras: Bundle?,
    ) {
        if (cancellation.isCanceled) {
            callback.onLayoutCancelled()
            return
        }
        attributes = newAttributes
        val info =
            PrintDocumentInfo.Builder(filename)
                .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                .setPageCount(pages.size)
                .build()
        callback.onLayoutFinished(info, newAttributes != oldAttributes)
    }

    override fun onWrite(
        requested: Array<out PrinterRange>,
        destination: ParcelFileDescriptor,
        cancellation: CancellationSignal,
        callback: WriteResultCallback,
    ) {
        val positions =
            pages.indices.filter { index -> requested.any { index in it.start..it.end } }
        val media = attributes.mediaSize ?: defaultMedia
        val margins = attributes.minMargins
        scope.launch {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(destination).use { output ->
                    withContext(Dispatchers.IO) {
                        require(positions.isNotEmpty()) { "Choose a page to print." }
                        writeOrderPrintPdf(
                            file,
                            options,
                            positions.map(pages::get),
                            media,
                            margins,
                            output,
                        ) {
                            coroutineContext.ensureActive()
                            cancellation.throwIfCanceled()
                        }
                    }
                }
                if (cancellation.isCanceled) callback.onWriteCancelled()
                else callback.onWriteFinished(writtenRanges(positions))
            } catch (_: CancellationException) {
                callback.onWriteCancelled()
            } catch (_: android.os.OperationCanceledException) {
                callback.onWriteCancelled()
            } catch (_: Exception) {
                callback.onWriteFailed(
                    "The PDF could not be prepared. Reopen the file and try again."
                )
            }
        }
    }

    override fun onFinish() {
        scope.cancel()
    }
}

private fun writtenRanges(positions: List<Int>): Array<PrinterRange> {
    val ranges = mutableListOf<PrinterRange>()
    for (position in positions) {
        val previous = ranges.lastOrNull()
        if (previous != null && position == previous.end + 1) {
            ranges[ranges.lastIndex] = PrinterRange(previous.start, position)
        } else ranges.add(PrinterRange(position, position))
    }
    return ranges.toTypedArray()
}

internal fun writeOrderPrintPdf(
    file: File,
    options: PrintOptions,
    sourcePages: List<Int>,
    media: PrintAttributes.MediaSize,
    margins: PrintAttributes.Margins?,
    output: OutputStream,
    checkCancelled: () -> Unit = {},
) {
    val width = media.widthMils * .072f
    val height = media.heightMils * .072f
    val minimum = minOf(5 * 72f / 25.4f, width / 8, height / 8)
    val left = maxOf(minimum, (margins?.leftMils ?: 0) * .072f)
    val right = maxOf(minimum, (margins?.rightMils ?: 0) * .072f)
    val top = maxOf(minimum, (margins?.topMils ?: 0) * .072f)
    val bottom = maxOf(minimum, (margins?.bottomMils ?: 0) * .072f)
    val availableWidth = width - left - right
    val availableHeight = height - top - bottom
    require(availableWidth > 0 && availableHeight > 0)
    checkCancelled()
    PDDocument.load(file).use { source ->
        require(source.numberOfPages == options.pages)
        source.documentCatalog.acroForm?.flatten()
        PDDocument().use { print ->
            val layers = LayerUtility(print)
            for (index in sourcePages) {
                checkCancelled()
                val page = source.getPage(index)
                val bounds = PdfPageBounds(page)
                val scale =
                    if (options.actualSize) 1f
                    else minOf(1f, availableWidth / bounds.width, availableHeight / bounds.height)
                val x = left + (availableWidth - bounds.width * scale) / 2
                val y = bottom + (availableHeight - bounds.height * scale) / 2
                val sheet = PDPage(PDRectangle(width, height))
                print.addPage(sheet)
                PDPageContentStream(print, sheet).use { stream ->
                    stream.saveGraphicsState()
                    stream.addRect(left, bottom, availableWidth, availableHeight)
                    stream.clip()
                    stream.drawPdfPage(layers, source, page, bounds.placement(x, y, scale))
                    stream.restoreGraphicsState()
                }
            }
            checkCancelled()
            print.save(output)
        }
    }
}
