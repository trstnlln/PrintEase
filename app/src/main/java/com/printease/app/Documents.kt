package com.printease.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.media.ExifInterface
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class Documents(private val context: Context) {
    private val directory
        get() = File(context.filesDir, "documents").apply { mkdirs() }

    fun file(id: String) = File(directory, "$id.pdf")

    suspend fun copyDraft(source: File, name: String): PdfFile =
        withContext(Dispatchers.IO) {
            val target = file("draft-${UUID.randomUUID()}")
            try {
                source.inputStream().use { input ->
                    target.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_PDF_BYTES) { "Choose a PDF no larger than 5 MB." }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                inspect(target, name)
            } catch (error: Exception) {
                target.delete()
                throw error
            }
        }

    suspend fun import(uri: Uri): PdfFile =
        withContext(Dispatchers.IO) {
            val name =
                context.contentResolver
                    .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?.take(255) ?: uri.lastPathSegment?.take(255) ?: "Document"
            val target = file("draft-${UUID.randomUUID()}")
            val source = File.createTempFile("printease-import-", ".tmp", context.cacheDir)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    source.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count == -1) break
                            total += count
                            require(total <= MAX_PDF_BYTES) {
                                "Choose a PDF, JPG, or PNG file up to 5 MB."
                            }
                            output.write(buffer, 0, count)
                        }
                    }
                }
                    ?: error(
                        "This file could not be opened. Download it to your phone and try again."
                    )
                val prefix =
                    source.inputStream().use { input ->
                        val bytes = ByteArray(8)
                        val length = input.read(bytes).coerceAtLeast(0)
                        bytes.copyOf(length)
                    }
                when {
                    prefix.copyOfRange(0, minOf(5, prefix.size)).toString(Charsets.US_ASCII) ==
                        "%PDF-" -> {
                        source.copyTo(target)
                        inspect(target, name)
                    }
                    prefix.take(3).map { it.toInt() and 255 } == listOf(255, 216, 255) ||
                        prefix.map { it.toInt() and 255 } ==
                            listOf(137, 80, 78, 71, 13, 10, 26, 10) -> {
                        imageAsPdf(source, target)
                        metadata(
                            target,
                            (name.substringBeforeLast('.', name).take(250).ifBlank { "Image" }) +
                                ".pdf",
                            1,
                        )
                    }
                    else ->
                        error(
                            "Choose a valid PDF, JPG, or PNG. Export Word, PowerPoint, or Excel files as PDF first."
                        )
                }
            } catch (error: Exception) {
                target.delete()
                throw error
            } finally {
                source.delete()
            }
        }

    private fun imageAsPdf(source: File, target: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.path, bounds)
        require(
            bounds.outWidth > 0 &&
                bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 40_000_000
        ) {
            "Choose a readable image under 40 megapixels."
        }
        val orientation =
            runCatching {
                    ExifInterface(source.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
                }
                .getOrDefault(1)
        val transform =
            Matrix().apply {
                when (orientation) {
                    2 -> setScale(-1f, 1f)
                    3 -> setRotate(180f)
                    4 -> setScale(1f, -1f)
                    5 -> {
                        setRotate(90f)
                        postScale(-1f, 1f)
                    }
                    6 -> setRotate(90f)
                    7 -> {
                        setRotate(-90f)
                        postScale(-1f, 1f)
                    }
                    8 -> setRotate(-90f)
                }
            }
        val longest = maxOf(bounds.outWidth, bounds.outHeight)
        val swap = orientation in 5..8
        val scale = minOf(1f, 2400f / longest) * 72 / 150
        val pageWidth =
            maxOf(1, ((if (swap) bounds.outHeight else bounds.outWidth) * scale).roundToInt())
        val pageHeight =
            maxOf(1, ((if (swap) bounds.outWidth else bounds.outHeight) * scale).roundToInt())
        for (limit in listOf(2400, 1600, 1100)) {
            var sample = 1
            while (longest / (sample * 2) >= limit) sample *= 2
            val decoded =
                BitmapFactory.decodeFile(
                    source.path,
                    BitmapFactory.Options().apply { inSampleSize = sample },
                ) ?: error("This image could not be opened. Try exporting it as JPG or PNG.")
            val ratio = minOf(1f, limit.toFloat() / maxOf(decoded.width, decoded.height))
            val sized =
                Bitmap.createScaledBitmap(
                    decoded,
                    maxOf(1, (decoded.width * ratio).roundToInt()),
                    maxOf(1, (decoded.height * ratio).roundToInt()),
                    true,
                )
            if (sized !== decoded) decoded.recycle()
            val image = Bitmap.createBitmap(sized, 0, 0, sized.width, sized.height, transform, true)
            if (image !== sized) sized.recycle()
            val jpeg = ByteArrayOutputStream()
            try {
                val opaque = Bitmap.createBitmap(image.width, image.height, Bitmap.Config.ARGB_8888)
                try {
                    opaque.eraseColor(Color.WHITE)
                    android.graphics.Canvas(opaque).drawBitmap(image, 0f, 0f, null)
                    check(opaque.compress(Bitmap.CompressFormat.JPEG, 92, jpeg)) {
                        "This image could not be converted to PDF."
                    }
                    writeImagePdf(
                        target,
                        jpeg.toByteArray(),
                        opaque.width,
                        opaque.height,
                        pageWidth.toString(),
                        pageHeight.toString(),
                    )
                } finally {
                    opaque.recycle()
                }
            } finally {
                image.recycle()
            }
            if (target.length() in 5..MAX_PDF_BYTES.toLong()) return
        }
        error("This image produces a PDF larger than 5 MB. Choose a smaller image.")
    }

    // A single JPEG XObject on a single page. The cross-reference records byte offsets.
    private fun writeImagePdf(
        target: File,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        pageWidth: String,
        pageHeight: String,
        percent: Int = 100,
    ) {
        target.outputStream().use { stream ->
            val offsets = IntArray(6)
            var position = 0
            fun append(text: String) {
                val bytes = text.toByteArray(Charsets.US_ASCII)
                stream.write(bytes)
                position += bytes.size
            }
            fun startObject(id: Int, dictionary: String) {
                offsets[id] = position
                append("$id 0 obj\n$dictionary")
            }
            fun endObject() = append("\nendobj\n")
            append("%PDF-1.4\n%PrintEaseImage $percent\n")
            startObject(1, "<< /Type /Catalog /Pages 2 0 R >>")
            endObject()
            startObject(2, "<< /Type /Pages /Kids [3 0 R] /Count 1 >>")
            endObject()
            startObject(
                3,
                "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 $pageWidth $pageHeight] " +
                    "/Resources << /XObject << /Photo 4 0 R >> >> /Contents 5 0 R >>",
            )
            endObject()
            val imageLength = jpeg.size
            startObject(
                4,
                "<< /Type /XObject /Subtype /Image /Width $width /Height $height " +
                    "/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length $imageLength >>\nstream\n",
            )
            stream.write(jpeg)
            position += imageLength
            append("\nendstream")
            endObject()
            val fraction = percent / 100.0
            val w = pageWidth.toDouble()
            val h = pageHeight.toDouble()
            val content =
                String.format(
                    Locale.ROOT,
                    "q %.3f 0 0 %.3f %.3f %.3f cm /Photo Do Q\n",
                    w * fraction,
                    h * fraction,
                    w * (1 - fraction) / 2,
                    h * (1 - fraction) / 2,
                )
            val contentLength = content.length
            startObject(5, "<< /Length $contentLength >>\nstream\n" + content + "endstream")
            endObject()
            val xref = position
            append("xref\n0 6\n0000000000 65535 f \n")
            for (id in 1..5) append(offsets[id].toString().padStart(10, '0') + " 00000 n \n")
            append("trailer\n<< /Size 6 /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        }
    }

    private data class PhotoPage(
        val width: Int,
        val height: Int,
        val pageWidth: String,
        val pageHeight: String,
        val offset: Int,
        val length: Int,
        val percent: Int,
    )

    private fun readPhotoPage(bytes: ByteArray): PhotoPage? {
        val header = bytes.copyOf(minOf(1024, bytes.size)).toString(Charsets.ISO_8859_1)
        if (!header.startsWith("%PDF-1.4\n")) return null
        val part = photoHeader.find(header) ?: return null
        val (pageWidth, pageHeight, width, height, jpegLength) = part.destructured
        val offset = part.range.last + 1
        val length = jpegLength.toIntOrNull() ?: return null
        val percent = imagePercent.find(header)?.groupValues?.get(1)?.toIntOrNull() ?: 100
        if (percent !in 1..100 || length < 4 || length > bytes.size - offset) return null
        return PhotoPage(
            width.toIntOrNull() ?: return null,
            height.toIntOrNull() ?: return null,
            pageWidth,
            pageHeight,
            offset,
            length,
            percent,
        )
    }

    suspend fun resizeImage(pdf: PdfFile, percent: Int): PdfFile =
        withContext(Dispatchers.IO) {
            require(percent in 1..100) { "Enter a whole image size from 1 to 100%." }
            val bytes = File(pdf.path).readBytes()
            val photo =
                readPhotoPage(bytes)
                    ?: error("Choose the original JPG or PNG again to resize this image.")
            val target = file("draft-" + UUID.randomUUID())
            try {
                writeImagePdf(
                    target,
                    bytes.copyOfRange(photo.offset, photo.offset + photo.length),
                    photo.width,
                    photo.height,
                    photo.pageWidth,
                    photo.pageHeight,
                    percent,
                )
                require(target.length() <= MAX_PDF_BYTES) {
                    "The resized image is larger than 5 MB."
                }
                metadata(target, pdf.name, 1)
            } catch (error: Exception) {
                target.delete()
                throw error
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
        return metadata(file, name, pages)
    }

    private fun metadata(file: File, name: String, pages: Int): PdfFile {
        val bytes = file.readBytes()
        val digest =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                "%02x".format(it)
            }
        return PdfFile(
            file.absolutePath,
            name,
            pages,
            file.length(),
            digest,
            readPhotoPage(bytes)?.percent,
        )
    }

    private companion object {
        val photoHeader =
            Regex(
                """/MediaBox \[0 0 (\d+(?:\.\d+)?) (\d+(?:\.\d+)?)\] /Resources << /XObject << /Photo 4 0 R >> >> /Contents 5 0 R >>\nendobj\n4 0 obj\n<< /Type /XObject /Subtype /Image /Width (\d+) /Height (\d+) /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length (\d+) >>\nstream\n"""
            )
        val imagePercent = Regex("""^%PDF-1.4\n%PrintEaseImage (\d+)\n""")
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

    suspend fun renderPrint(file: File, page: Int, options: PrintOptions): Pair<Bitmap, Boolean> =
        withContext(Dispatchers.IO) {
            PdfRenderer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)).use {
                renderer ->
                renderer.openPage(page).use { p ->
                    val layout = printGeometry(options.paper, p.width, p.height, options.actualSize)
                    val scale = minOf(2f, 1000f / maxOf(layout.width, layout.height))
                    val bitmap =
                        Bitmap.createBitmap(
                            (layout.width * scale).roundToInt(),
                            (layout.height * scale).roundToInt(),
                            Bitmap.Config.ARGB_8888,
                        )
                    bitmap.eraseColor(Color.WHITE)
                    val margin = layout.margin * scale
                    val clip =
                        Rect(
                            margin.roundToInt(),
                            margin.roundToInt(),
                            bitmap.width - margin.roundToInt(),
                            bitmap.height - margin.roundToInt(),
                        )
                    val matrix =
                        Matrix().apply {
                            setScale(layout.scale * scale, layout.scale * scale)
                            postTranslate(layout.left * scale, layout.top * scale)
                        }
                    p.render(bitmap, clip, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    android.graphics
                        .Canvas(bitmap)
                        .drawRect(
                            RectF(clip),
                            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                                color = Color.rgb(115, 135, 114)
                                style = Paint.Style.STROKE
                                pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
                            },
                        )
                    bitmap to layout.clipped
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
