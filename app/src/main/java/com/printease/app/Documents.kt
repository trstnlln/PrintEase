package com.printease.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Documents(private val context: Context) {
    private val directory
        get() = File(context.filesDir, "documents").apply { mkdirs() }

    fun file(id: String) = File(directory, "$id.pdf")

    suspend fun import(uri: Uri): PdfFile =
        withContext(Dispatchers.IO) {
            val name =
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.take(255) ?: "Document.pdf"
            val target = file("draft-${UUID.randomUUID()}")
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= MAX_PDF_BYTES) { "Choose a PDF no larger than 5 MB." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                    ?: error(
                        "This document could not be opened. Download it to your phone and try again."
                    )
                inspect(target, name)
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }

    fun inspect(file: File, name: String): PdfFile {
        require(file.length() in 5..MAX_PDF_BYTES.toLong()) { "Choose a PDF no larger than 5 MB." }
        require(
            file.inputStream().use {
                String(
                    ByteArray(5).also { bytes -> java.io.DataInputStream(it).readFully(bytes) },
                    Charsets.US_ASCII,
                )
            } == "%PDF-"
        ) {
            "This is not a valid PDF document."
        }
        val pages =
            try {
                PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
                    .use { it.pageCount }
            } catch (_: Exception) {
                error("This PDF cannot be read. Use an unlocked, undamaged PDF.")
            }
        require(pages in 1..1000) { "Choose a PDF with 1–1,000 pages." }
        val digest =
            MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                "%02x".format(it)
            }
        return PdfFile(file.absolutePath, name, pages, file.length(), digest)
    }

    suspend fun render(file: File, page: Int): Bitmap =
        withContext(Dispatchers.IO) {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                renderer ->
                renderer.openPage(page).use { p ->
                    val width = 1000
                    val height = (p.height.toFloat() / p.width * width).toInt().coerceIn(1, 2000)
                    Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
                        bitmap.eraseColor(Color.WHITE)
                        p.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    }
                }
            }
        }

    fun sample(id: String, pages: Int): File {
        val target = file(id)
        if (target.exists()) return target
        val doc = PdfDocument()
        try {
            repeat(pages) { i ->
                val page = doc.startPage(PdfDocument.PageInfo.Builder(595, 842, i + 1).create())
                val paint =
                    Paint().apply {
                        color = Color.rgb(23, 107, 81)
                        textSize = 28f
                        isAntiAlias = true
                    }
                page.canvas.drawText("PrintEase · Sample document", 40f, 80f, paint)
                paint.textSize = 15f
                paint.color = Color.DKGRAY
                page.canvas.drawText("Demo order • Page ${i + 1} of $pages", 40f, 120f, paint)
                page.canvas.drawText(
                    "Upload your own PDF to try the complete flow.",
                    40f,
                    160f,
                    paint,
                )
                doc.finishPage(page)
            }
            target.outputStream().use(doc::writeTo)
        } finally {
            doc.close()
        }
        return target
    }
}
