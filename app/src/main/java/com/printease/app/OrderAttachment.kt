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
                    actualSize = options["actualSize"] == true,
                ),
            )
        }
    }
}

data class PrintOptionText(
    val copies: String,
    val ranges: String,
    val width: String,
    val height: String,
) {
    constructor(
        options: PrintOptions
    ) : this(
        options.copies.toString(),
        pageRangeLabel(options.pageRanges),
        customPaperDimensions(options.paper)?.first?.toString().orEmpty(),
        customPaperDimensions(options.paper)?.second?.toString().orEmpty(),
    )

    fun validCopies(options: PrintOptions): Boolean = copies.toIntOrNull() == options.copies

    fun validPaper(options: PrintOptions): Boolean =
        customPaperDimensions(options.paper) == null || customPaper(width, height) == options.paper

    fun validRanges(options: PrintOptions): Boolean =
        runCatching { parsePageRanges(ranges, options.pages) }.getOrNull() != null

    fun valid(options: PrintOptions): Boolean =
        validCopies(options) && validPaper(options) && validRanges(options)
}

data class AttachmentDraft(
    val pdf: PdfFile,
    val options: PrintOptions = PrintOptions(pages = pdf.pages),
    val input: PrintOptionText = PrintOptionText(options),
) {
    fun metadata(): OrderAttachment {
        require(input.valid(options)) { "Check the print options for each PDF." }
        require(options.pages == pdf.pages) { "The additional PDF page count changed." }
        return OrderAttachment(pdf.name.take(255), pdf.sha256, options)
    }
}

fun List<AttachmentDraft>.orderAttachments(): List<OrderAttachment> {
    require(size <= MAX_ATTACHMENTS) { "Choose up to three PDFs per request." }
    return map(AttachmentDraft::metadata)
}

const val batchRangeMessage =
    "Multi-file requests print all pages. Submit files separately to choose page ranges."

fun PrintOptions.batchRangesValid(attachments: List<AttachmentDraft>): Boolean =
    attachments.isEmpty() || pageRanges.isEmpty() || pageRanges == listOf(PageRange(1, pages))

fun PrintOptions.requireBatchRanges(attachments: List<AttachmentDraft>) {
    require(batchRangesValid(attachments)) { batchRangeMessage }
}
