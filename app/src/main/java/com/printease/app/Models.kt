package com.printease.app

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

const val MAX_PDF_BYTES = 5 * 1024 * 1024
val campusZone: ZoneId = ZoneId.of("Asia/Manila")

fun queueDay(epoch: Long = System.currentTimeMillis()): String =
    Instant.ofEpochMilli(epoch).atZone(campusZone).toLocalDate().toString()

fun formatDate(epoch: Long): String =
    DateTimeFormatter.ofPattern("MMM d, h:mm a", Locale.ENGLISH)
        .withZone(campusZone)
        .format(Instant.ofEpochMilli(epoch))

fun pesos(value: Long): String = "₱%,d.00".format(Locale.US, value)

enum class OrderStatus(val label: String) {
    Pending("Pending"),
    Accepted("Accepted"),
    Printing("Printing"),
    Ready("Ready for Pickup"),
    Completed("Completed"),
    Rejected("Rejected"),
    Cancelled("Cancelled");

    val active
        get() = this in listOf(Pending, Accepted, Printing, Ready)

    fun canMoveTo(next: OrderStatus) =
        when (this) {
            Pending -> next in listOf(Accepted, Rejected, Cancelled)
            Accepted -> next == Printing
            Printing -> next == Ready
            Ready -> next == Completed
            else -> false
        }

    companion object {
        fun parse(value: String) =
            entries.firstOrNull { it.label == value } ?: error("Unknown order status")
    }
}

data class PrintOptions(
    val paper: String = "A4",
    val pages: Int = 1,
    val copies: Int = 1,
    val color: Boolean = false,
    val duplex: Boolean = false,
) {
    init {
        require(paper in listOf("A4", "Letter", "Legal"))
        require(pages in 1..1000)
        require(copies in 1..100)
    }

    val rate: Long
        get() = (if (color) 10L else 2L) + (if (paper == "Legal") 1L else 0L)

    val total: Long
        get() = pages * copies * rate

    val sheets: Int
        get() = (if (duplex) (pages + 1) / 2 else pages) * copies

    fun toMap(): Map<String, Any> =
        mapOf(
            "paper" to paper,
            "pages" to pages,
            "copies" to copies,
            "color" to color,
            "duplex" to duplex,
        )
}

data class Session(val uid: String, val name: String, val email: String, val admin: Boolean)

data class PdfFile(
    val path: String,
    val name: String,
    val pages: Int,
    val bytes: Long,
    val sha256: String,
)

data class PrintOrder(
    val id: String,
    val ownerId: String,
    val studentName: String,
    val fileName: String,
    val storagePath: String,
    val options: PrintOptions,
    val createdAt: Long = System.currentTimeMillis(),
    val status: OrderStatus = OrderStatus.Pending,
    val queue: Int? = null,
    val queueDay: String? = null,
    val note: String = "",
) {
    fun toMap(): Map<String, Any?> =
        mapOf(
            "ownerId" to ownerId,
            "studentName" to studentName,
            "fileName" to fileName,
            "storagePath" to storagePath,
            "options" to options.toMap(),
            "estimatedCost" to options.total,
            "createdAt" to createdAt,
            "status" to status.label,
            "queue" to queue,
            "queueDay" to queueDay,
            "note" to note,
        )

    companion object {
        fun fromMap(id: String, d: Map<String, Any?>): PrintOrder {
            val o = d["options"] as? Map<*, *> ?: error("Missing print options")
            return PrintOrder(
                id,
                d["ownerId"] as String,
                d["studentName"] as String,
                d["fileName"] as String,
                d["storagePath"] as String,
                PrintOptions(
                    o["paper"] as String,
                    (o["pages"] as Number).toInt(),
                    (o["copies"] as Number).toInt(),
                    o["color"] as Boolean,
                    o["duplex"] as Boolean,
                ),
                (d["createdAt"] as Number).toLong(),
                OrderStatus.parse(d["status"] as String),
                (d["queue"] as? Number)?.toInt(),
                d["queueDay"] as? String,
                d["note"] as? String ?: "",
            )
        }
    }
}

data class ShopState(
    val session: Session? = null,
    val orders: List<PrintOrder> = emptyList(),
    val connecting: Boolean = false,
    val cached: Boolean = false,
    val syncError: String? = null,
)

fun sortedQueue(orders: List<PrintOrder>): List<PrintOrder> =
    orders.sortedWith(
        compareBy<PrintOrder> { if (it.status.active) 0 else 1 }
            .thenBy { it.queueDay ?: "9999" }
            .thenBy { it.queue ?: Int.MAX_VALUE }
            .thenBy { it.createdAt }
    )
