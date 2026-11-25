package com.printease.app

val standardPaperSizes = listOf("A4", "Letter", "Legal")
val quotedPaperSizes = listOf("A3", "A5", "B5", "Folio", "Tabloid")
private const val dimension = "([5-9][0-9]|[1-9][0-9]{2}|1000)"
private val customPaperPattern = Regex("Custom ${dimension}x${dimension} mm")

fun customPaperDimensions(paper: String): Pair<Int, Int>? =
    customPaperPattern.matchEntire(paper)?.let {
        it.groupValues[1].toInt() to it.groupValues[2].toInt()
    }

fun customPaper(width: String, height: String): String? =
    "Custom ${width}x${height} mm".takeIf(customPaperPattern::matches)

fun validPaper(paper: String): Boolean =
    paper in standardPaperSizes || paper in quotedPaperSizes || customPaperDimensions(paper) != null

fun needsPaperQuote(paper: String): Boolean = paper !in standardPaperSizes

fun paperLabel(paper: String): String =
    customPaperDimensions(paper)?.let { (width, height) -> "$width × $height mm (custom)" } ?: paper

val stockPapers = standardPaperSizes + quotedPaperSizes + "Custom"
val stockLabels =
    mapOf("available" to "Available", "out" to "Out of stock", "ask" to "Stock unconfirmed")

fun PaperStock.menuLabel(paper: String, showStock: Boolean): String {
    val label = if (paper == "Custom") "Custom size" else paper
    val state = status(paper)
    return if (showStock && state != "ask") "$label · ${stockLabels.getValue(state)}" else label
}

data class PaperStock(
    val available: List<String> = emptyList(),
    val out: List<String> = emptyList(),
) {
    init {
        require((available + out).all { it in stockPapers })
        require((available + out).distinct().size == available.size + out.size)
    }

    fun status(paper: String): String =
        when (if (customPaperDimensions(paper) != null) "Custom" else paper) {
            in out -> "out"
            in available -> "available"
            else -> "ask"
        }

    fun toMap() = mapOf("available" to available, "out" to out)

    companion object {
        fun fromMap(map: Map<*, *>?): PaperStock =
            PaperStock(
                (map?.get("available") as? List<*>)?.filterIsInstance<String>().orEmpty(),
                (map?.get("out") as? List<*>)?.filterIsInstance<String>().orEmpty(),
            )
    }
}

val PrintOptions.scaleLabel: String
    get() = if (actualSize) "Actual size" else "Fit to paper"

data class PrintGeometry(
    val width: Float,
    val height: Float,
    val margin: Float,
    val scale: Float,
    val left: Float,
    val top: Float,
    val clipped: Boolean,
)

fun printGeometry(paper: String, width: Int, height: Int, actualSize: Boolean): PrintGeometry {
    val size =
        customPaperDimensions(paper)?.let { it.first.toFloat() to it.second.toFloat() }
            ?: when (paper) {
                "Letter" -> 215.9f to 279.4f
                "Legal" -> 215.9f to 355.6f
                "A3" -> 297f to 420f
                "A5" -> 148f to 210f
                "B5" -> 176f to 250f
                "Folio" -> 215.9f to 330.2f
                "Tabloid" -> 279.4f to 431.8f
                else -> 210f to 297f
            }
    val short = minOf(size.first, size.second) * 72f / 25.4f
    val long = maxOf(size.first, size.second) * 72f / 25.4f
    val (sheetW, sheetH) = if (width > height) long to short else short to long
    val margin = minOf(5 * 72f / 25.4f, sheetW / 8, sheetH / 8)
    val fit = minOf(1f, (sheetW - 2 * margin) / width, (sheetH - 2 * margin) / height)
    val scale = if (actualSize) 1f else fit
    return PrintGeometry(
        sheetW,
        sheetH,
        margin,
        scale,
        (sheetW - width * scale) / 2,
        (sheetH - height * scale) / 2,
        actualSize && fit < .999f,
    )
}
