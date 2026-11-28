package com.printease.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
internal fun FileOptionsHeading(
    name: String,
    position: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "File $position of $count",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(name, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleMedium)
    }
}

internal val printOptionTextSaver =
    listSaver<PrintOptionText, String>(
        save = { listOf(it.copies, it.ranges, it.width, it.height) },
        restore = { PrintOptionText(it[0], it[1], it[2], it[3]) },
    )

@Composable
internal fun PrintOptionsEditor(
    pdf: PdfFile?,
    options: PrintOptions,
    input: PrintOptionText,
    busy: Boolean,
    stock: PaperStock,
    paperSizesAvailable: Boolean,
    printToolsAvailable: Boolean,
    allPagesRequired: Boolean,
    onChange: (PrintOptions, PrintOptionText) -> Unit,
    onPreview: () -> Unit,
    onResize: (Int) -> Unit,
    position: Int,
    count: Int,
    onRemove: (() -> Unit)? = null,
) {
    var choosePaper by remember { mutableStateOf(false) }
    val custom = customPaperDimensions(options.paper) != null
    val validPaper = input.validPaper(options)
    val ranges = runCatching { parsePageRanges(input.ranges, options.pages) }
    val rangeConflict = allPagesRequired && options.selectedPages != options.pages
    val validCopies = input.validCopies(options)
    if (pdf != null) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FileOptionsHeading(pdf.name, position, count, Modifier.weight(1f))
            if (onRemove != null)
                IconButton(onRemove, enabled = !busy) {
                    Icon(Icons.Default.Close, "Remove ${pdf.name}")
                }
        }
    }
    OutlinedTextField(
        input.ranges,
        { text ->
            val value = text.take(120)
            val parsed = runCatching { parsePageRanges(value, options.pages) }.getOrNull()
            onChange(
                parsed?.let { options.copy(pageRanges = it) } ?: options,
                input.copy(ranges = value),
            )
        },
        label = { Text("Pages to print") },
        placeholder = { Text("All pages") },
        supportingText = {
            Text(
                ranges.exceptionOrNull()?.message
                    ?: if (allPagesRequired) batchRangeMessage
                    else "Leave blank for all pages, or enter 1-3, 5, 8-10"
            )
        },
        isError = ranges.isFailure || rangeConflict,
        enabled = !busy && pdf != null && (!allPagesRequired || rangeConflict || ranges.isFailure),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Text("Paper size", fontWeight = FontWeight.SemiBold)
    Box {
        OutlinedButton(
            { choosePaper = true },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(paperLabel(options.paper), Modifier.weight(1f))
            Icon(Icons.Default.ExpandMore, null)
        }
        DropdownMenu(expanded = choosePaper, onDismissRequest = { choosePaper = false }) {
            val sizes =
                standardPaperSizes +
                    if (paperSizesAvailable) quotedPaperSizes + "Custom" else emptyList()
            sizes.forEach { size ->
                DropdownMenuItem(
                    text = { Text(stock.menuLabel(size, printToolsAvailable)) },
                    enabled = stock.status(size) != "out",
                    onClick = {
                        choosePaper = false
                        if (size == "Custom") {
                            onChange(
                                options.copy(paper = "Custom 210x297 mm"),
                                input.copy(width = "", height = ""),
                            )
                        } else onChange(options.copy(paper = size), input)
                    },
                )
            }
        }
    }
    if (custom) {
        val updateDimensions: (PrintOptionText) -> Unit = { text ->
            val paper = customPaper(text.width, text.height)
            onChange(paper?.let { options.copy(paper = it) } ?: options, text)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                input.width,
                { updateDimensions(input.copy(width = it.filter(Char::isDigit).take(4))) },
                modifier = Modifier.weight(1f),
                label = { Text("Width (mm)") },
                isError = !validPaper,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                input.height,
                { updateDimensions(input.copy(height = it.filter(Char::isDigit).take(4))) },
                modifier = Modifier.weight(1f),
                label = { Text("Height (mm)") },
                isError = !validPaper,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
        }
        Text(
            "Enter whole millimeters from 50 to 1,000.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (needsPaperQuote(options.paper))
        Text(
            "The shop checks stock and confirms the final price.",
            style = MaterialTheme.typography.bodySmall,
        )
    if (paperSizesAvailable && !custom)
        Text(
            "Choose Custom size to set the width and height.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    val availability = stock.status(options.paper)
    if (printToolsAvailable || availability == "out")
        Text(
            if (availability == "out") "Out of stock. Choose another paper size."
            else stockLabels.getValue(availability),
            color =
                if (availability == "out") MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    if (pdf != null && pdf.pages > 1) {
        var chooseSlides by remember { mutableStateOf(false) }
        Text("Slides per side", fontWeight = FontWeight.SemiBold)
        Box {
            OutlinedButton(
                { chooseSlides = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text(options.slidesPerSide.toString(), Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded = chooseSlides, onDismissRequest = { chooseSlides = false }) {
                slideLayouts.forEach { count ->
                    DropdownMenuItem(
                        text = { Text("$count slides per side") },
                        onClick = {
                            chooseSlides = false
                            onChange(options.copy(slidesPerSide = count), input)
                        },
                    )
                }
            }
        }
        Text(
            if (options.slidesPerSide == 1) "Each PDF page counts as one slide."
            else "${options.printedSides} printed sides per copy",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (printToolsAvailable && options.slidesPerSide == 1)
        PreferenceSwitch("Actual size", "Off for Fit to paper", options.actualSize, !busy) {
            onChange(options.copy(actualSize = it), input)
        }
    if (pdf != null) {
        ImageSizeEditor(pdf, busy, onResize)
        OutlinedButton(
            onPreview,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy && validPaper && ranges.isSuccess,
        ) {
            Icon(Icons.Default.Visibility, null)
            Spacer(Modifier.width(8.dp))
            Text("Preview print layout")
        }
    }
    OutlinedTextField(
        input.copies,
        { text ->
            val value = text.filter(Char::isDigit).take(3)
            val copies = value.toIntOrNull()?.takeIf { it in 1..100 } ?: options.copies
            onChange(options.copy(copies = copies), input.copy(copies = value))
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = !busy,
        label = { Text("Copies") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        isError = !validCopies,
        supportingText = {
            Text(if (validCopies) "Choose 1–100 copies" else "Enter a number from 1 to 100")
        },
    )
    HorizontalDivider()
    PreferenceSwitch("Print in color", "Off for black & white", options.color, !busy) {
        onChange(options.copy(color = it), input)
    }
    PreferenceSwitch("Double-sided", "Use both sides to save paper", options.duplex, !busy) {
        onChange(options.copy(duplex = it), input)
    }
}

@Composable
fun ImageSizeEditor(pdf: PdfFile, busy: Boolean, onResize: (Int) -> Unit) {
    val applied = pdf.imagePercent
    if (applied == null) {
        Text("Image size (%)", fontWeight = FontWeight.SemiBold)
        Text(
            "Available for JPG or PNG uploads.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    var value by rememberSaveable(pdf.path) { mutableStateOf(applied.toString()) }
    val percent = value.toIntOrNull()
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value,
            { value = it.filter(Char::isDigit).take(3) },
            modifier = Modifier.weight(1f),
            label = { Text("Image size (%)") },
            singleLine = true,
            enabled = !busy,
            isError = percent !in 1..100,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        TextButton(
            onClick = { percent?.let(onResize) },
            enabled = !busy && percent in 1..100 && percent != applied,
        ) {
            Text("Apply size")
        }
    }
    Text(
        "Enter 1–100%. For example, 40% prints at 40% of the original fitted width and height.",
        style = MaterialTheme.typography.bodySmall,
    )
}
