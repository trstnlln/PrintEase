package com.printease.app

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class ModelTest {
    @Test
    fun sampleRatesAndDuplexRounding() {
        assertEquals(48L, PrintOptions(pages = 12, copies = 2).total)
        assertEquals(66L, PrintOptions("Legal", 3, 2, true).total)
        assertEquals(6, PrintOptions(pages = 5, copies = 2, duplex = true).sheets)
        assertEquals(20L, PrintOptions(pages = 5, copies = 2, duplex = true).total)
        assertEquals(1_100_000L, PrintOptions("Legal", 1000, 100, true).total)
    }

    @Test
    fun queueResetsAtPhilippineMidnight() {
        assertEquals("2026-11-15", queueDay(Instant.parse("2026-11-14T16:00:00Z").toEpochMilli()))
        assertEquals("2026-11-14", queueDay(Instant.parse("2026-11-14T15:59:59Z").toEpochMilli()))
    }

    @Test
    fun workflowCannotSkipStepsOrReopenFinishedOrders() {
        assertFalse(OrderStatus.Pending.canMoveTo(OrderStatus.Completed))
        assertTrue(OrderStatus.Pending.canMoveTo(OrderStatus.Cancelled))
        assertFalse(OrderStatus.Printing.canMoveTo(OrderStatus.Cancelled))
        assertTrue(OrderStatus.Ready.canMoveTo(OrderStatus.Completed))
        assertFalse(OrderStatus.Completed.canMoveTo(OrderStatus.Accepted))
        assertFalse(OrderStatus.Rejected.canMoveTo(OrderStatus.Pending))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidCopiesRejected() {
        PrintOptions(copies = 0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidPaperRejected() {
        PrintOptions(paper = "Unknown")
    }

    @Test
    fun legacyFirestoreOrdersRoundTrip() {
        val order =
            PrintOrder(
                "id",
                "owner",
                "Alex",
                "Report.pdf",
                "documents/owner/id.pdf",
                PrintOptions(pages = 3),
                1234L,
            )
        assertEquals(order, PrintOrder.fromMap(order.id, order.toMap()))
    }

    @Test
    fun adminQueueOrdersAcceptedRequestsBeforePendingByDayAndNumber() {
        val pending =
            PrintOrder("pending", "owner", "Alex", "Report.pdf", "", PrintOptions(), createdAt = 1L)
        val second =
            pending.copy(
                id = "second",
                queue = 2,
                queueDay = "2026-11-14",
                status = OrderStatus.Accepted,
            )
        val first = second.copy(id = "first", queue = 1)
        assertEquals(
            listOf("first", "second", "pending"),
            sortedQueue(listOf(pending, second, first)).map { it.id },
        )
    }

    @Test
    fun selectedPagesAreMergedBoundedAndPricedWithSavedRates() {
        val ranges = parsePageRanges("5, 1-3, 2, 8-10", 10)
        assertEquals(listOf(PageRange(1, 3), PageRange(5, 5), PageRange(8, 10)), ranges)
        val options = PrintOptions(pages = 10, copies = 3, duplex = true, pageRanges = ranges)
        assertEquals(7, options.selectedPages)
        assertEquals(12, options.sheets)
        assertEquals(84L, options.total(PrintRates(4, 12, 2)))
        assertTrue(parsePageRanges("1-10", 10).isEmpty())
        listOf("0", "11", "3-1", "1,,2", "1.5", "1-9999999999999999999999").forEach {
            assertTrue(it, runCatching { parsePageRanges(it, 10) }.isFailure)
        }
    }

    @Test
    fun ordersRoundTripWithPriceSnapshotAndSelectedRanges() {
        val order =
            PrintOrder(
                "id",
                "a",
                "Alex",
                "Report.pdf",
                "documents/a/id.pdf",
                PrintOptions(pages = 10, pageRanges = listOf(PageRange(1, 3), PageRange(5, 5))),
                pricing = PrintRates(4, 12, 2),
            )
        assertEquals(16L, order.total)
        assertEquals(order, PrintOrder.fromMap(order.id, order.toMap()))
        assertEquals(0, ShopSettings().retentionDays)
        assertTrue(runCatching { ShopSettings(retentionDays = 1) }.isFailure)
    }
}
