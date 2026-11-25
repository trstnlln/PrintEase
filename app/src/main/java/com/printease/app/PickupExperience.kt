package com.printease.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun PickupSummary(order: PrintOrder, admin: Boolean, busy: Boolean, onEstimate: () -> Unit) {
    if (order.status in listOf(OrderStatus.Cancelled, OrderStatus.Rejected)) return
    Panel(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    when (order.status) {
                        OrderStatus.Ready -> "Ready to collect"
                        OrderStatus.Completed -> "Collected"
                        else ->
                            order.expectedReadyAt?.let { "Estimated ready ${formatDate(it)}" }
                                ?: "Waiting for the shop’s estimate"
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    if (order.status == OrderStatus.Ready) "Collect and pay at the shop."
                    else if (order.status == OrderStatus.Completed) "Thank you for using PrintEase."
                    else if (order.expectedReadyAt == null)
                        "The shop will confirm after reviewing your PDF."
                    else "This is the shop’s estimate and may change.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        if (!admin && order.status.active) {
            HorizontalDivider()
            Text("Your collection code", style = MaterialTheme.typography.titleMedium)
            Text(
                collectionCode(order.id),
                fontFamily = FontFamily.Monospace,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 4.sp,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Show this code when you collect your prints.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (
            admin &&
                order.status in
                    listOf(OrderStatus.Pending, OrderStatus.Accepted, OrderStatus.Printing)
        ) {
            OutlinedButton(
                onEstimate,
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !busy,
            ) {
                Text(
                    if (order.expectedReadyAt == null) "Set pickup estimate"
                    else "Update pickup estimate"
                )
            }
        }
    }
}

@Composable
fun PickupTimeDialog(order: PrintOrder, onSave: (Long?) -> Unit, onDismiss: () -> Unit) {
    var minutes by
        rememberSaveable(order.id) {
            mutableStateOf(
                order.expectedReadyAt?.let {
                    ((it - System.currentTimeMillis()) / 60000).coerceIn(1, 10080).toString()
                } ?: "30"
            )
        }
    val number = minutes.toLongOrNull()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set pickup estimate") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "Give the customer a realistic ready time. You can update it if your workload changes."
                )
                OutlinedTextField(
                    minutes,
                    { minutes = it.take(5) },
                    Modifier.fillMaxWidth(),
                    label = { Text("Minutes from now") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = number == null || number !in 1..10080,
                    supportingText = { Text("Choose 1 minute to 7 days.") },
                    singleLine = true,
                )
                if (order.expectedReadyAt != null)
                    TextButton({ onSave(null) }) { Text("Clear estimate") }
            }
        },
        confirmButton = {
            TextButton(
                { number?.let { onSave(System.currentTimeMillis() + it * 60000) } },
                enabled = number != null && number in 1..10080,
            ) {
                Text("Save estimate")
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Go back") } },
    )
}
