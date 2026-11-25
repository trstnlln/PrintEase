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

private val nameSuffix = Regex("(jr|sr|ii|iii|iv|v)\\.?", RegexOption.IGNORE_CASE)

fun greetingName(displayName: String): String {
    val parts = displayName.split(',', limit = 3)
    val afterComma = parts.getOrNull(1)?.trim().orEmpty()
    val name =
        if (afterComma.isNotEmpty() && !nameSuffix.matches(afterComma)) afterComma
        else parts.first().trim()
    return name.takeWhile { !it.isWhitespace() }.trim('.', ';', ':')
}

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

data class PrintRates(val bw: Long = 2, val color: Long = 10, val legalExtra: Long = 1) {
    init {
        require(bw in 1..1000 && color in 1..1000 && legalExtra in 0..1000)
    }

    fun toMap() = mapOf("bw" to bw, "color" to color, "legalExtra" to legalExtra)

    companion object {
        fun fromMap(m: Map<*, *>?) =
            if (m == null) PrintRates()
            else
                PrintRates(
                    (m["bw"] as Number).toLong(),
                    (m["color"] as Number).toLong(),
                    (m["legalExtra"] as Number).toLong(),
                )
    }
}

data class ShopSettings(
    val name: String = "Nano Print Alangilan",
    val address: String = "Neptune St., Golden Country Homes, Alangilan, Batangas City",
    val pickupHours: String = "6:00 AM–10:00 PM",
    val acceptingOrders: Boolean = true,
    val rates: PrintRates = PrintRates(),
    val retentionDays: Int = 0,
    val version: Long = 0,
    val contactEmail: String =
        if (name == "Nano Print Alangilan") "nanoprint.alangilan@gmail.com" else "",
    val facebookUrl: String = "",
    val ratesConfirmed: Boolean = false,
    val pickupSchedule: PickupSchedule? = null,
    val paperStock: PaperStock = PaperStock(),
) {
    init {
        require(
            name.trim().length in 2..100 &&
                address.trim().length in 2..300 &&
                pickupHours.trim().length in 2..200
        )
        require(retentionDays in listOf(0, 7, 14, 30) && version >= 0)
        require(
            contactEmail.isBlank() ||
                (contactEmail.length <= 254 &&
                    Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(contactEmail.trim()))
        )
        require(validFacebookUrl(facebookUrl))
    }

    fun toMap(): Map<String, Any> =
        mapOf(
            "name" to name.trim(),
            "address" to address.trim(),
            "pickupHours" to pickupHours.trim(),
            "acceptingOrders" to acceptingOrders,
            "rates" to rates.toMap(),
            "retentionDays" to retentionDays,
            "version" to version,
            "contactEmail" to contactEmail.trim(),
            "facebookUrl" to facebookUrl.trim(),
            "ratesConfirmed" to ratesConfirmed,
        ) +
            (pickupSchedule?.let { mapOf("pickupSchedule" to it.toMap()) } ?: emptyMap()) +
            (if (paperStock == PaperStock()) emptyMap()
            else mapOf("paperStock" to paperStock.toMap()))

    companion object {
        fun fromMap(m: Map<String, Any?>?) =
            if (m == null) ShopSettings()
            else
                ShopSettings(
                    (m["name"] as String).let {
                        if (it == "Nano Alangilan") "Nano Print Alangilan" else it
                    },
                    if (
                        m["name"] in listOf("Nano Alangilan", "Nano Print Alangilan") &&
                            m["address"] == "Near BSU Alangilan campus"
                    )
                        "Neptune St., Golden Country Homes, Alangilan, Batangas City"
                    else m["address"] as String,
                    if (
                        m["name"] in listOf("Nano Alangilan", "Nano Print Alangilan") &&
                            m["pickupHours"] == "Confirm pickup hours with the shop"
                    )
                        "6:00 AM–10:00 PM"
                    else m["pickupHours"] as String,
                    m["acceptingOrders"] as Boolean,
                    PrintRates.fromMap(m["rates"] as? Map<*, *>),
                    (m["retentionDays"] as Number).toInt(),
                    (m["version"] as Number).toLong(),
                    m["contactEmail"] as? String
                        ?: if (m["name"] in listOf("Nano Alangilan", "Nano Print Alangilan"))
                            "nanoprint.alangilan@gmail.com"
                        else "",
                    m["facebookUrl"] as? String ?: "",
                    m["ratesConfirmed"] as? Boolean ?: false,
                    (m["pickupSchedule"] as? Map<*, *>)?.let(PickupSchedule::fromMap),
                    PaperStock.fromMap(m["paperStock"] as? Map<*, *>),
                )
    }
}

fun validFacebookUrl(value: String): Boolean {
    if (value.isBlank()) return true
    if (value.length > 2048) return false
    return runCatching {
            val url = java.net.URI(value.trim())
            url.scheme == "https" &&
                url.userInfo == null &&
                url.port in listOf(-1, 443) &&
                url.host?.lowercase(Locale.ROOT) in
                    listOf(
                        "facebook.com",
                        "www.facebook.com",
                        "m.facebook.com",
                        "fb.com",
                        "www.fb.com",
                    )
        }
        .getOrDefault(false)
}

fun collectionCode(orderId: String): String =
    orderId.replace(Regex("[^A-Za-z0-9]"), "").takeLast(6).uppercase(Locale.ROOT).padStart(6, '0')

fun validPickupTime(value: Long?, now: Long = System.currentTimeMillis()): Boolean =
    value == null || value in (now - 60000)..(now + 7L * 86400000)

data class PageRange(val start: Int, val end: Int) {
    val count
        get() = end - start + 1

    fun toMap() = mapOf("start" to start, "end" to end)
}

fun parsePageRanges(text: String, pages: Int): List<PageRange> {
    if (text.isBlank()) return emptyList()
    val raw =
        text
            .split(',')
            .map { part ->
                val match =
                    Regex("""\s*(\d+)\s*(?:-\s*(\d+)\s*)?""").matchEntire(part)
                        ?: error(
                            "Use page numbers like 1-3, 5, 8-10, or leave blank for all pages."
                        )
                val start = match.groupValues[1].toIntOrNull() ?: error("Invalid page number.")
                val end =
                    if (match.groupValues[2].isEmpty()) start
                    else match.groupValues[2].toIntOrNull() ?: error("Invalid page number.")
                require(start in 1..pages && end in start..pages) {
                    "Page numbers must be between 1 and $pages."
                }
                PageRange(start, end)
            }
            .sortedBy { it.start }
    val merged = mutableListOf<PageRange>()
    raw.forEach { r ->
        val previous = merged.lastOrNull()
        if (previous != null && r.start <= previous.end + 1)
            merged[merged.lastIndex] = PageRange(previous.start, maxOf(r.end, previous.end))
        else merged += r
    }
    require(merged.size <= 5) {
        "Choose up to 5 separate page ranges, or leave blank for all pages."
    }
    return if (merged == listOf(PageRange(1, pages))) emptyList() else merged
}

fun pageRangeLabel(ranges: List<PageRange>) =
    ranges.joinToString(", ") { if (it.start == it.end) "${it.start}" else "${it.start}-${it.end}" }

data class PrintOptions(
    val paper: String = "A4",
    val pages: Int = 1,
    val copies: Int = 1,
    val color: Boolean = false,
    val duplex: Boolean = false,
    val pageRanges: List<PageRange> = emptyList(),
    val actualSize: Boolean = false,
) {
    init {
        require(validPaper(paper))
        require(pages in 1..1000)
        require(copies in 1..100)
        require(pageRanges.size <= 5)
        pageRanges.forEachIndexed { i, r ->
            require(
                r.start in 1..pages &&
                    r.end in r.start..pages &&
                    (i == 0 || r.start > pageRanges[i - 1].end)
            )
        }
    }

    val selectedPages: Int
        get() = if (pageRanges.isEmpty()) pages else pageRanges.sumOf { it.count }

    fun rate(rates: PrintRates): Long =
        (if (color) rates.color else rates.bw) + (if (paper == "Legal") rates.legalExtra else 0)

    fun total(rates: PrintRates) = selectedPages * copies * rate(rates)

    val rate: Long
        get() = rate(PrintRates())

    val total: Long
        get() = total(PrintRates())

    val sheets: Int
        get() = (if (duplex) (selectedPages + 1) / 2 else selectedPages) * copies

    val pageLabel: String
        get() =
            if (pageRanges.isEmpty()) "All $pages pages" else "Pages ${pageRangeLabel(pageRanges)}"

    fun toMap(): Map<String, Any> =
        mapOf(
            "paper" to paper,
            "pages" to pages,
            "copies" to copies,
            "color" to color,
            "duplex" to duplex,
            "pageRanges" to (pageRanges.ifEmpty { listOf(PageRange(1, pages)) }).map { it.toMap() },
            "selectedPages" to selectedPages,
        ) + (if (actualSize) mapOf("actualSize" to true) else emptyMap())
}

data class Session(
    val uid: String,
    val name: String,
    val email: String,
    val admin: Boolean,
    val photoUrl: String? = null,
    val googleAccount: Boolean = false,
)

data class PdfFile(
    val path: String,
    val name: String,
    val pages: Int,
    val bytes: Long,
    val sha256: String,
    val imagePercent: Int? = null,
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
    val pricing: PrintRates = PrintRates(),
    val updatedAt: Long = createdAt,
    val completedAt: Long? = null,
    val documentDeletedAt: Long? = null,
    val documentStore: String = "firebase",
    val fileHash: String? = null,
    val expectedReadyAt: Long? = null,
    val review: OrderReview? = null,
    val attachments: List<OrderAttachment> = emptyList(),
    val printedFiles: List<Int> = emptyList(),
) {
    init {
        require(documentStore in listOf("firebase", "hosted"))
        require(attachments.size <= MAX_ATTACHMENTS)
        if (documentStore == "hosted") {
            require(fileHash != null && Regex("[a-f0-9]{64}").matches(fileHash))
            require(
                storagePath == "hosted/$ownerId/$fileHash.pdf" ||
                    storagePath == "hosted/$ownerId/$id/$fileHash.pdf"
            )
        }
    }

    val total: Long
        get() = options.total(pricing)

    fun toMap(): Map<String, Any?> =
        mapOf(
            "ownerId" to ownerId,
            "studentName" to studentName,
            "fileName" to fileName,
            "storagePath" to storagePath,
            "options" to options.toMap(),
            "estimatedCost" to
                if (needsPaperQuote(options.paper) || attachments.isNotEmpty()) 0L else total,
            "pricing" to pricing.toMap(),
            "updatedAt" to updatedAt,
            "completedAt" to completedAt,
            "documentDeletedAt" to documentDeletedAt,
            "documentStore" to documentStore,
            "fileHash" to fileHash,
            "createdAt" to createdAt,
            "status" to status.label,
            "queue" to queue,
            "queueDay" to queueDay,
            "note" to note,
        ) +
            (expectedReadyAt?.let { mapOf("expectedReadyAt" to it) } ?: emptyMap()) +
            (review?.let { mapOf("review" to it.toMap()) } ?: emptyMap()) +
            (if (attachments.isEmpty()) emptyMap()
            else mapOf("attachments" to attachments.map { it.toMap() })) +
            (if (printedFiles.isEmpty()) emptyMap() else mapOf("printedFiles" to printedFiles))

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
                    (o["pageRanges"] as? List<*>)
                        ?.map { v ->
                            val r = v as Map<*, *>
                            PageRange((r["start"] as Number).toInt(), (r["end"] as Number).toInt())
                        }
                        ?.let {
                            if (it == listOf(PageRange(1, (o["pages"] as Number).toInt())))
                                emptyList()
                            else it
                        } ?: emptyList(),
                    o["actualSize"] == true,
                ),
                (d["createdAt"] as Number).toLong(),
                OrderStatus.parse(d["status"] as String),
                (d["queue"] as? Number)?.toInt(),
                d["queueDay"] as? String,
                d["note"] as? String ?: "",
                PrintRates.fromMap(d["pricing"] as? Map<*, *>),
                (d["updatedAt"] as? Number)?.toLong() ?: (d["createdAt"] as Number).toLong(),
                (d["completedAt"] as? Number)?.toLong(),
                (d["documentDeletedAt"] as? Number)?.toLong(),
                d["documentStore"] as? String ?: "firebase",
                d["fileHash"] as? String,
                (d["expectedReadyAt"] as? Number)?.toLong(),
                (d["review"] as? Map<*, *>)?.let(OrderReview::fromMap),
                (d["attachments"] as? List<*>)
                    ?.map { OrderAttachment.fromMap(it as Map<*, *>) }
                    .orEmpty(),
                (d["printedFiles"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }.orEmpty(),
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
    val settings: ShopSettings = ShopSettings(),
    val settingsError: String? = null,
    val settingsCached: Boolean = false,
    val reviewAvailable: Boolean = false,
    val paperSizesAvailable: Boolean = false,
    val printToolsAvailable: Boolean = false,
    val queueStatus: QueueStatus? = null,
)

data class QueueStatus(val waiting: Int, val printing: Int, val checkedAt: Long) {
    fun toMap() = mapOf("waiting" to waiting, "printing" to printing, "checkedAt" to checkedAt)

    companion object {
        fun fromStatuses(statuses: Iterable<String?>, checkedAt: Long) =
            QueueStatus(
                statuses.count { it == OrderStatus.Accepted.label },
                statuses.count { it == OrderStatus.Printing.label },
                checkedAt,
            )
    }
}

fun sortedQueue(orders: List<PrintOrder>): List<PrintOrder> =
    orders.sortedWith(
        compareBy<PrintOrder> { if (it.status.active) 0 else 1 }
            .thenBy { it.queueDay ?: "9999" }
            .thenBy { it.queue ?: Int.MAX_VALUE }
            .thenBy { it.createdAt }
    )
