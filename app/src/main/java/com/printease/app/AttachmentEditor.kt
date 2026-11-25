package com.printease.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun AttachmentEditor(
    item: AttachmentDraft,
    busy: Boolean,
    onChange: (AttachmentDraft) -> Unit,
    onRemove: () -> Unit,
    stock: PaperStock,
    printToolsAvailable: Boolean,
    onPreview: () -> Unit,
    onResize: (Int) -> Unit,
) {
    var choosePaper by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(item.pdf.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(onRemove, enabled = !busy) { Text("Remove") }
        }
        Text("${item.pdf.pages} pages · All pages", style = MaterialTheme.typography.bodySmall)
        Box {
            OutlinedButton(
                { choosePaper = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            ) {
                Text("Paper · ${paperLabel(item.options.paper)}", Modifier.weight(1f))
                Icon(Icons.Default.ExpandMore, null)
            }
            DropdownMenu(expanded = choosePaper, onDismissRequest = { choosePaper = false }) {
                (standardPaperSizes + quotedPaperSizes).forEach { size ->
                    DropdownMenuItem(
                        text = { Text(stock.menuLabel(size, printToolsAvailable)) },
                        enabled = stock.status(size) != "out",
                        onClick = {
                            choosePaper = false
                            onChange(item.copy(options = item.options.copy(paper = size)))
                        },
                    )
                }
            }
        }
        if (stock.status(item.options.paper) == "out")
            Text(
                "Out of stock. Choose another paper size.",
                color = MaterialTheme.colorScheme.error,
            )
        OutlinedTextField(
            item.copiesText,
            { text ->
                val input = text.filter(Char::isDigit).take(3)
                val copies = input.toIntOrNull()?.takeIf { it in 1..100 } ?: item.options.copies
                onChange(
                    item.copy(options = item.options.copy(copies = copies), copiesText = input)
                )
            },
            Modifier.fillMaxWidth(),
            enabled = !busy,
            label = { Text("Copies") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            isError = !item.validCopies,
            supportingText = { Text("1–100 copies") },
        )
        if (printToolsAvailable)
            PreferenceSwitch(
                "Actual size",
                "Off for Fit to paper",
                item.options.actualSize,
                !busy,
            ) {
                onChange(item.copy(options = item.options.copy(actualSize = it)))
            }
        ImageSizeEditor(item.pdf, busy, onResize)
        OutlinedButton(onPreview, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
            Text("Preview print layout")
        }
        PreferenceSwitch("Print in color", "Off for black & white", item.options.color, !busy) {
            onChange(item.copy(options = item.options.copy(color = it)))
        }
        PreferenceSwitch("Double-sided", "Print on both sides", item.options.duplex, !busy) {
            onChange(item.copy(options = item.options.copy(duplex = it)))
        }
    }
}

@Composable
fun ImageSizeEditor(pdf: PdfFile, busy: Boolean, onResize: (Int) -> Unit) {
    val applied = pdf.imagePercent ?: return
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
