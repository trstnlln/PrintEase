package com.printease.app

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
