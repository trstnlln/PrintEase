package com.printease.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

@Composable
fun ReviewPanel(
    order: PrintOrder,
    admin: Boolean,
    available: Boolean,
    enabled: Boolean,
    onReview: (ReviewAction, Long, String, Long?) -> Unit,
    onEdit: () -> Unit,
) {
    val review = order.review
    val pending = order.status == OrderStatus.Pending
    if (review == null && (!admin || !available || !pending)) return
    val stage = review?.stage ?: ReviewStage.Submitted
    val version = review?.version ?: -1L
    var action by rememberSaveable(order.id, version) { mutableStateOf<ReviewAction?>(null) }
    var note by rememberSaveable(order.id, version) { mutableStateOf("") }
    var amount by
        rememberSaveable(order.id, version) {
            mutableStateOf(
                review?.quote?.toString()
                    ?: if (needsPaperQuote(order.options.paper) || order.attachments.isNotEmpty())
                        ""
                    else order.total.toString()
            )
        }
    val waitingForStudent =
        pending && !admin && stage in listOf(ReviewStage.Quoted, ReviewStage.Correction)
    val requested =
        PrintAddon.entries
            .filter { it.key in review?.addons.orEmpty() }
            .joinToString(", ") { it.label }
    val message =
        when (stage) {
            ReviewStage.Submitted ->
                if (admin) "Review the PDF and add-ons, then send a final price."
                else "The shop will review your request and send a final price."
            ReviewStage.Revised ->
                if (admin) "Review the updated PDF and settings before quoting."
                else "The shop will review your updated request."
            ReviewStage.Quoted ->
                if (admin) "Waiting for the customer to approve this price."
                else "Approve this price so the shop can accept your request."
            ReviewStage.Approved ->
                if (admin) "The customer approved this price. You can accept the request."
                else "You’ll pay this amount when you collect your prints."
            ReviewStage.Declined ->
                if (admin) "The customer declined this quote. You can send another price."
                else "The shop can send another quote."
            ReviewStage.Correction ->
                if (admin) "Waiting for the customer to send an update."
                else "Update the PDF or print options on this request."
        }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (waitingForStudent) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surface,
                contentColor =
                    if (waitingForStudent) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurface,
            ),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(stage.title, style = MaterialTheme.typography.titleLarge)
            review?.quote?.let { Text(pesos(it), style = MaterialTheme.typography.headlineMedium) }
            if (requested.isNotEmpty()) Text("Requested add-ons · $requested")
            if (review?.note?.isNotBlank() == true) Text(review.note)
            if (pending) Text(message, style = MaterialTheme.typography.bodyMedium)
            if (pending && admin && available) {
                when (action) {
                    ReviewAction.Quote,
                    ReviewAction.RequestCorrection -> {
                        val quoting = action == ReviewAction.Quote
                        if (quoting)
                            OutlinedTextField(
                                amount,
                                { amount = it.filter(Char::isDigit).take(9) },
                                Modifier.fillMaxWidth(),
                                label = { Text("Final price (₱)") },
                                singleLine = true,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                                enabled = enabled,
                            )
                        OutlinedTextField(
                            note,
                            { note = it.take(500) },
                            Modifier.fillMaxWidth(),
                            label = {
                                Text(
                                    if (quoting) "Note for the customer (optional)"
                                    else "What needs to change?"
                                )
                            },
                            minLines = 2,
                            maxLines = 4,
                            enabled = enabled,
                        )
                        val valid =
                            if (quoting) amount.toLongOrNull() in 1..MAX_FINAL_QUOTE
                            else note.isNotBlank()
                        PrimaryButton(
                            if (quoting) "Send price" else "Send correction request",
                            {
                                onReview(
                                    requireNotNull(action),
                                    version,
                                    note,
                                    amount.toLongOrNull(),
                                )
                            },
                            Modifier.fillMaxWidth(),
                            enabled && valid,
                        )
                        PressTextButton({ action = null }, enabled = enabled) { Text("Back") }
                    }
                    else -> {
                        if (stage !in listOf(ReviewStage.Correction, ReviewStage.Approved))
                            PrimaryButton(
                                if (stage == ReviewStage.Quoted) "Update quote"
                                else "Send final price",
                                {
                                    action = ReviewAction.Quote
                                    note = review?.note.orEmpty()
                                },
                                Modifier.fillMaxWidth(),
                                enabled,
                            )
                        if (stage != ReviewStage.Correction)
                            PressOutlinedButton(
                                {
                                    action = ReviewAction.RequestCorrection
                                    note = ""
                                },
                                enabled = enabled,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Request a correction")
                            }
                    }
                }
            }
            if (pending && !admin && stage == ReviewStage.Correction)
                PrimaryButton("Edit this request", onEdit, Modifier.fillMaxWidth(), enabled)
            if (pending && !admin && stage == ReviewStage.Quoted) {
                PrimaryButton(
                    "Approve price",
                    { action = ReviewAction.Approve },
                    Modifier.fillMaxWidth(),
                    enabled,
                )
                PressOutlinedButton(
                    { action = ReviewAction.Decline },
                    enabled = enabled,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Decline quote")
                }
            }
        }
    }
    if (action == ReviewAction.Approve || action == ReviewAction.Decline) {
        val approve = action == ReviewAction.Approve
        AlertDialog(
            onDismissRequest = { action = null },
            title = { Text(if (approve) "Approve this price?" else "Decline this quote?") },
            text = {
                Text(
                    if (approve)
                        "Pay ${pesos(requireNotNull(review?.quote))} at pickup. The shop can accept your request after approval."
                    else "The shop can send another price. Printing stays on hold."
                )
            },
            confirmButton = {
                PressTextButton(
                    {
                        onReview(requireNotNull(action), version, "", null)
                        action = null
                    },
                    enabled = enabled,
                ) {
                    Text(if (approve) "Approve price" else "Decline price")
                }
            },
            dismissButton = { PressTextButton({ action = null }) { Text("Back") } },
        )
    }
}
