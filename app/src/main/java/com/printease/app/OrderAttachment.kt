package com.printease.app

const val MAX_ATTACHMENTS = 2

data class OrderAttachment(val fileName: String, val fileHash: String, val options: PrintOptions) {
    init {
        require(fileName.isNotBlank() && fileName.length <= 255)
        require(Regex("[a-f0-9]{64}").matches(fileHash))
        require(options.pageRanges.isEmpty())
    }

    fun toMap(): Map<String, Any> =
        mapOf(
            "fileName" to fileName,
            "fileHash" to fileHash,
            "options" to options.toMap().filterKeys { it != "pageRanges" && it != "selectedPages" },
        )

    companion object {
        fun fromMap(map: Map<*, *>): OrderAttachment {
            val options = map["options"] as Map<*, *>
            return OrderAttachment(
                map["fileName"] as String,
                map["fileHash"] as String,
                PrintOptions(
                    options["paper"] as String,
                    (options["pages"] as Number).toInt(),
                    (options["copies"] as Number).toInt(),
                    options["color"] as Boolean,
                    options["duplex"] as Boolean,
                ),
            )
        }
    }
}

data class AttachmentDraft(
    val pdf: PdfFile,
    val options: PrintOptions = PrintOptions(pages = pdf.pages),
    val copiesText: String = options.copies.toString(),
) {
    val validCopies: Boolean
        get() = copiesText.toIntOrNull() == options.copies

    fun metadata(): OrderAttachment {
        require(validCopies) { "Enter 1–100 copies for each PDF." }
        require(options.pages == pdf.pages) { "The additional PDF page count changed." }
        return OrderAttachment(pdf.name.take(255), pdf.sha256, options)
    }
}

fun List<AttachmentDraft>.orderAttachments(): List<OrderAttachment> {
    require(size <= MAX_ATTACHMENTS) { "Choose up to three PDFs per request." }
    return map(AttachmentDraft::metadata)
}

fun PrintOptions.batchRangesValid(attachments: List<AttachmentDraft>): Boolean =
    attachments.isEmpty() || pageRanges.isEmpty() || pageRanges == listOf(PageRange(1, pages))

fun PrintOptions.requireBatchRanges(attachments: List<AttachmentDraft>) {
    require(batchRangesValid(attachments)) {
        "Multi-PDF requests print all pages. Clear the page ranges or submit this PDF separately."
    }
}
