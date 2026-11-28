package com.printease.app

enum class PrintAddon(val key: String, val label: String) {
    Bookbinding("bookbinding", "Bookbinding"),
    Lamination("lamination", "Lamination"),
}

enum class ReviewStage(val key: String, val title: String) {
    Submitted("submitted", "Shop review"),
    Revised("revised", "Correction received"),
    Correction("correction", "Correction requested"),
    Quoted("quoted", "Final quote"),
    Approved("approved", "Approved price"),
    Declined("declined", "Quote declined"),
}

enum class ReviewAction {
    Quote,
    RequestCorrection,
    Approve,
    Decline,
    Revise,
}

const val MAX_FINAL_QUOTE = 200_000_000L

data class OrderReview(
    val stage: ReviewStage = ReviewStage.Submitted,
    val version: Long = 0,
    val addons: List<String> = emptyList(),
    val note: String = "",
    val quote: Long? = null,
) {
    init {
        require(
            addons.distinct().size == addons.size &&
                addons.all { id -> PrintAddon.entries.any { it.key == id } }
        )
    }

    fun toMap() =
        mapOf(
            "stage" to stage.key,
            "version" to version,
            "addons" to addons,
            "note" to note,
            "quote" to quote,
        )

    companion object {
        fun fromMap(map: Map<*, *>): OrderReview =
            OrderReview(
                ReviewStage.entries.first { it.key == map["stage"] },
                (map["version"] as Number).toLong(),
                (map["addons"] as List<*>).map { it as String },
                map["note"] as String,
                (map["quote"] as? Number)?.toLong(),
            )
    }
}

data class RequestRevision(val id: String, val version: Long, val note: String)

data class CorrectionDraft(
    val pdf: PdfFile,
    val options: PrintOptions,
    val attachments: List<AttachmentDraft> = emptyList(),
)

val PrintOrder.canAccept: Boolean
    get() = review == null || review.stage == ReviewStage.Approved

val PrintOrder.reviewStatus: String
    get() =
        if (status == OrderStatus.Pending && review?.stage != ReviewStage.Submitted)
            review?.stage?.title ?: status.label
        else status.label

fun PrintOrder.updateReview(
    session: Session,
    action: ReviewAction,
    version: Long,
    note: String = "",
    quote: Long? = null,
): OrderReview {
    check(status == OrderStatus.Pending && version == (review?.version ?: -1L)) {
        "This request changed. Refresh it before continuing."
    }
    val current = review ?: OrderReview()
    val next = version + 1
    val text = note.trim()
    check(text.length <= 500) { "Keep the note within 500 characters." }
    if (session.admin) {
        when (action) {
            ReviewAction.Quote -> {
                check(current.stage !in listOf(ReviewStage.Approved, ReviewStage.Correction))
                check(quote != null && quote in 1..MAX_FINAL_QUOTE) {
                    "Enter a valid final price in whole pesos."
                }
                return current.copy(
                    stage = ReviewStage.Quoted,
                    version = next,
                    note = text,
                    quote = quote,
                )
            }
            ReviewAction.RequestCorrection -> {
                check(current.stage != ReviewStage.Correction && text.isNotBlank()) {
                    "Describe what the customer should correct."
                }
                return current.copy(
                    stage = ReviewStage.Correction,
                    version = next,
                    note = text,
                    quote = null,
                )
            }
            else -> Unit
        }
    } else if (session.uid == ownerId) {
        when {
            current.stage == ReviewStage.Quoted && action == ReviewAction.Approve ->
                return current.copy(stage = ReviewStage.Approved, version = next)
            current.stage == ReviewStage.Quoted && action == ReviewAction.Decline ->
                return current.copy(
                    stage = ReviewStage.Declined,
                    version = next,
                    quote = null,
                    note = "",
                )
            current.stage == ReviewStage.Correction && action == ReviewAction.Revise ->
                return current.copy(
                    stage = ReviewStage.Revised,
                    version = next,
                    note = "",
                    quote = null,
                )
        }
    }
    error("This action is unavailable for the current request.")
}

fun canRemovePdf(
    order: PrintOrder,
    settings: ShopSettings,
    now: Long = System.currentTimeMillis(),
): Boolean =
    order.status == OrderStatus.Completed &&
        order.documentDeletedAt == null &&
        settings.retentionDays in listOf(7, 14, 30) &&
        order.completedAt?.let { it > 0 && it + settings.retentionDays * 86400000L <= now } ==
            true &&
        order.documentStore == "hosted" &&
        order.storagePath == "hosted/${order.ownerId}/${order.id}/${order.fileHash}.pdf"

enum class ShopQueueGroup(val title: String) {
    Review("Needs review"),
    Waiting("Waiting for response"),
    Ready("Ready to print"),
    Printing("Printing"),
    Pickup("Ready for pickup"),
    History("History"),
}

val PrintOrder.queueGroup: ShopQueueGroup
    get() =
        when (status) {
            OrderStatus.Pending ->
                if (review?.stage in listOf(ReviewStage.Quoted, ReviewStage.Correction))
                    ShopQueueGroup.Waiting
                else ShopQueueGroup.Review
            OrderStatus.Accepted -> ShopQueueGroup.Ready
            OrderStatus.Printing -> ShopQueueGroup.Printing
            OrderStatus.Ready -> ShopQueueGroup.Pickup
            else -> ShopQueueGroup.History
        }
