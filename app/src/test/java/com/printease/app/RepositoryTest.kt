package com.printease.app

import android.content.Context
import android.print.PrintAttributes
import androidx.test.core.app.ApplicationProvider
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepositoryTest {
    private lateinit var context: Context
    private lateinit var documents: Documents

    @Before
    fun clear() {
        context = ApplicationProvider.getApplicationContext()
        context
            .getSharedPreferences("printease-demo-v2", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        documents = Documents(context)
    }

    @Test
    fun androidPrintUsesTheOrderDefaultsAndOnlyTheRequestedPdfPages() {
        val source = File(context.cacheDir, "order-print.pdf")
        PDDocument().use { document ->
            repeat(5) { index ->
                val page = PDPage(PDRectangle(550f + index, 750f))
                document.addPage(page)
                PDPageContentStream(document, page).use {
                    it.addRect(40f, 40f, 50f, 50f)
                    it.fill()
                }
            }
            document.save(source)
        }
        val options =
            PrintOptions(
                paper = "Letter",
                pages = 5,
                duplex = true,
                pageRanges = listOf(PageRange(2, 2), PageRange(5, 5)),
            )
        val attributes = printAttributes(options, landscape = false)
        assertEquals(PrintAttributes.MediaSize.NA_LETTER, attributes.mediaSize)
        assertEquals(PrintAttributes.COLOR_MODE_MONOCHROME, attributes.colorMode)
        assertEquals(PrintAttributes.DUPLEX_MODE_LONG_EDGE, attributes.duplexMode)
        val output = ByteArrayOutputStream()
        writeOrderPrintPdf(
            source,
            options,
            options.sourcePages(),
            requireNotNull(attributes.mediaSize),
            null,
            output,
        )
        PDDocument.load(output.toByteArray()).use { document ->
            assertEquals(2, document.numberOfPages)
            assertEquals(612f, document.getPage(0).mediaBox.width, .1f)
            assertEquals(792f, document.getPage(0).mediaBox.height, .1f)
            val sourceWidths =
                document.pages.map { page ->
                    val resources = page.resources
                    val form =
                        resources.getXObject(resources.xObjectNames.single())
                            as com.tom_roush.pdfbox.pdmodel.graphics.form.PDFormXObject
                    form.bBox.width
                }
            assertEquals(listOf(551f, 554f), sourceWidths)
        }
    }

    @Test
    fun handoutSavesSelectedSlidesAndKeepsRetriesStable() {
        val input = File(context.cacheDir, "slides.pdf")
        val output = File(context.cacheDir, "handout.pdf")
        val retry = File(context.cacheDir, "handout-retry.pdf")
        PDDocument().use { document ->
            repeat(12) { index ->
                val page = PDPage(PDRectangle(594f, 334f))
                if (index == 2) page.rotation = 90
                document.addPage(page)
                PDPageContentStream(document, page).use { stream ->
                    stream.addRect(30f, 40f, 100f, 90f)
                    stream.fill()
                }
            }
            document.save(input)
        }
        val source = PdfFile(input.path, "Slides.pdf", 12, input.length(), "d".repeat(64))
        val options =
            PrintOptions(
                pages = 12,
                copies = 2,
                duplex = true,
                slidesPerSide = 4,
                pageRanges = listOf(PageRange(1, 3), PageRange(5, 10), PageRange(12, 12)),
            )
        writeHandout(source, options, output)
        writeHandout(source, options, retry)
        assertArrayEquals(output.readBytes(), retry.readBytes())
        assertEquals(3, options.handoutOptions().pages)
        assertEquals(options.total, options.handoutOptions().total)
        assertEquals(4, options.sheets)
        PDDocument.load(output).use { document ->
            assertEquals(3, document.numberOfPages)
            assertEquals(listOf(4, 4, 2), document.pages.map { it.resources.xObjectNames.count() })
        }
    }

    @Test
    fun demoOrdersAndRoleSurviveRestart() = runBlocking {
        val repo = DemoRepository(context, documents)
        repo.demoSignIn(true)
        val order = repo.state.value.orders.first { it.status == OrderStatus.Pending }
        repo.transition(order.id, OrderStatus.Accepted)
        val restored = DemoRepository(context, documents)
        assertTrue(restored.state.value.session!!.admin)
        assertEquals(2, restored.state.value.orders.first { it.id == order.id }.queue)
        restored.demoSignIn(false)
        assertEquals(
            OrderStatus.Accepted,
            restored.state.value.orders.first { it.id == order.id }.status,
        )
    }

    @Test
    fun retryUsesSameIdAndKeepsDocumentBytes() = runBlocking {
        val repo = DemoRepository(context, documents)
        repo.demoSignIn(false)
        val source =
            File(context.cacheDir, "upload-fixture.pdf").apply {
                writeText("%PDF-fixture-for-byte-preservation")
            }
        val pdf = PdfFile(source.path, "two-pages.pdf", 2, source.length(), "test-digest")
        repeat(2) { repo.submit("stable-id", pdf, PrintOptions(pages = 2)) {} }
        assertEquals(1, repo.state.value.orders.count { it.id == "stable-id" })
        val saved = repo.document(repo.state.value.orders.first { it.id == "stable-id" })
        assertArrayEquals(source.readBytes(), saved.readBytes())
    }

    @Test
    fun pendingCancellationAndTerminalProtection() = runBlocking {
        val repo = DemoRepository(context, documents)
        repo.demoSignIn(false)
        val pending = repo.state.value.orders.first { it.status == OrderStatus.Pending }
        repo.transition(pending.id, OrderStatus.Cancelled)
        assertEquals(
            OrderStatus.Cancelled,
            repo.state.value.orders.first { it.id == pending.id }.status,
        )
        assertTrue(runCatching { repo.transition(pending.id, OrderStatus.Accepted) }.isFailure)
    }

    @Test
    fun studentCannotAcceptAndRejectNeedsReason() = runBlocking {
        val repo = DemoRepository(context, documents)
        repo.demoSignIn(false)
        val order = repo.state.value.orders.first { it.status == OrderStatus.Pending }
        assertTrue(runCatching { repo.transition(order.id, OrderStatus.Accepted) }.isFailure)
        repo.demoSignIn(true)
        assertTrue(runCatching { repo.transition(order.id, OrderStatus.Rejected) }.isFailure)
        repo.transition(order.id, OrderStatus.Rejected, "Please resend an unlocked PDF.")
        assertTrue(repo.state.value.orders.first { it.id == order.id }.note.isNotBlank())
    }

    @Test
    fun invalidAndOversizedDocumentsAreRejected() {
        val invalid = File(context.cacheDir, "invalid.pdf").apply { writeText("Not a real PDF") }
        assertTrue(runCatching { documents.inspect(invalid, "invalid.pdf") }.isFailure)
        val huge =
            File(context.cacheDir, "huge.pdf").apply { writeBytes(ByteArray(MAX_PDF_BYTES + 1)) }
        assertTrue(runCatching { documents.inspect(huge, "huge.pdf") }.isFailure)
    }

    @Test
    fun imageAtTheNewLimitCanBeImportedAsPdf() = runBlocking {
        val source = File(context.cacheDir, "large-design.png")
        val image =
            android.graphics.Bitmap.createBitmap(160, 96, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            image.eraseColor(android.graphics.Color.GREEN)
            source.outputStream().use {
                assertTrue(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally {
            image.recycle()
        }
        java.io.RandomAccessFile(source, "rw").use { it.setLength(15L * 1024 * 1024) }
        val imported = documents.import(android.net.Uri.fromFile(source))
        assertEquals("large-design.pdf", imported.name)
        assertEquals(1, imported.pages)
    }

    @Test
    fun jpegAndPngImportsBecomeOnePagePdfs() = runBlocking {
        val image =
            android.graphics.Bitmap.createBitmap(160, 96, android.graphics.Bitmap.Config.ARGB_8888)
        image.eraseColor(android.graphics.Color.GREEN)
        for ((extension, format) in
            listOf(
                "jpg" to android.graphics.Bitmap.CompressFormat.JPEG,
                "png" to android.graphics.Bitmap.CompressFormat.PNG,
            )) {
            val photo = File(context.cacheDir, "Sample.$extension")
            photo.outputStream().use { assertTrue(image.compress(format, 92, it)) }
            val pdf = documents.import(android.net.Uri.fromFile(photo))
            assertEquals("Sample.pdf", pdf.name)
            assertEquals(1, pdf.pages)
            assertEquals(100, pdf.imagePercent)
            val bytes = File(pdf.path).readBytes()
            val content = bytes.toString(Charsets.ISO_8859_1)
            assertTrue(content.startsWith("%PDF-1.4"))
            assertTrue("/MediaBox [0 0 77 46]" in content)
            assertTrue("/Count 1" in content)
            val xref = Regex("startxref\n(\\d+)").find(content)!!.groupValues[1].toInt()
            assertEquals("xref", content.substring(xref, xref + 4))
            val imageStream = Regex("/DCTDecode /Length (\\d+) >>\nstream\n").find(content)!!
            val embedded =
                android.graphics.BitmapFactory.decodeByteArray(
                    bytes,
                    imageStream.range.last + 1,
                    imageStream.groupValues[1].toInt(),
                )
            assertEquals(160, embedded.width)
            assertEquals(96, embedded.height)
            assertTrue(android.graphics.Color.green(embedded.getPixel(80, 48)) > 230)
            embedded.recycle()
            val smaller = documents.resizeImage(pdf, 37)
            assertEquals(37, smaller.imagePercent)
            val resizedText = File(smaller.path).readBytes().toString(Charsets.ISO_8859_1)
            assertTrue("/MediaBox [0 0 77 46]" in resizedText)
            assertTrue("q 28.490 0 0 17.020 24.255 14.490 cm /Photo Do Q" in resizedText)
            assertEquals(pdf.sha256, documents.resizeImage(smaller, 100).sha256)
        }
        image.recycle()
    }

    @Test
    fun settingsAndQuoteSurviveRestartAndStudentsCannotEditRates() = runBlocking {
        val repo = DemoRepository(context, documents)
        repo.demoSignIn(true)
        repo.saveSettings(repo.state.value.settings.copy(rates = PrintRates(4, 12, 2)))
        repo.demoSignIn(false)
        assertEquals(48L, repo.state.value.orders.first { it.id == "sample-1042" }.total)
        assertTrue(runCatching { repo.saveSettings(repo.state.value.settings) }.isFailure)
        val source = File(context.cacheDir, "snapshot.pdf").apply { writeText("%PDF-preserve") }
        val options =
            PrintOptions(pages = 10, pageRanges = listOf(PageRange(1, 3), PageRange(5, 5)))
        repo.submit(
            "snapshot",
            PdfFile(source.path, "Snapshot.pdf", 10, source.length(), "digest"),
            options,
        ) {}
        repo.demoSignIn(true)
        repo.saveSettings(repo.state.value.settings.copy(rates = PrintRates(5, 15, 3)))
        val restored = DemoRepository(context, documents)
        assertEquals(16L, restored.state.value.orders.first { it.id == "snapshot" }.total)
        assertEquals(options, restored.state.value.orders.first { it.id == "snapshot" }.options)
        assertEquals(5L, restored.state.value.settings.rates.bw)
        restored.saveSettings(restored.state.value.settings.copy(acceptingOrders = false))
        restored.demoSignIn(false)
        assertTrue(
            runCatching {
                    restored.submit(
                        "paused",
                        PdfFile(source.path, "Snapshot.pdf", 10, source.length(), "digest"),
                        options,
                    ) {}
                }
                .isFailure
        )
    }

    @Test
    fun pickupEstimateIsAdminOnlyAndDoesNotChangePrice() = runBlocking {
        val repo = DemoRepository(context, documents)
        repo.demoSignIn(false)
        val order = repo.state.value.orders.first { it.status == OrderStatus.Printing }
        assertTrue(
            runCatching { repo.setPickupTime(order.id, System.currentTimeMillis() + 60000) }
                .isFailure
        )
        repo.demoSignIn(true)
        val readyAt = System.currentTimeMillis() + 30 * 60000
        repo.setPickupTime(order.id, readyAt)
        val restored =
            DemoRepository(context, documents).state.value.orders.first { it.id == order.id }
        assertEquals(readyAt, restored.expectedReadyAt)
        assertEquals(order.total, restored.total)
        repo.transition(order.id, OrderStatus.Ready)
        assertTrue(runCatching { repo.setPickupTime(order.id, readyAt) }.isFailure)
    }
}
