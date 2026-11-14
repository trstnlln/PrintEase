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
}
