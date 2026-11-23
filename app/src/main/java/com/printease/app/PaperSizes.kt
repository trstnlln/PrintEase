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
