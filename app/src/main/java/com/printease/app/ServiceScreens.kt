package com.printease.app

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestoreException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private fun deskError(error: Throwable): String =
    if (
        (error as? FirebaseFirestoreException)?.code ==
            FirebaseFirestoreException.Code.PERMISSION_DENIED
    )
        "This request is unavailable, or the shop still needs to activate service requests and messages."
    else error.message ?: "Couldn’t sync. Check your connection and try again."

@Composable
internal fun ServiceRequestsContent(
    desk: ServiceDesk,
    documents: Documents,
    session: Session,
    accepting: Boolean,
) {
    var requests by remember(session.uid) { mutableStateOf<List<ServiceRequest>?>(null) }
    var loadError by remember(session.uid) { mutableStateOf<String?>(null) }
    var page by rememberSaveable(session.uid) { mutableStateOf("") }
    var saveError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(session.uid, session.admin) {
        desk.requests(session).catch { loadError = deskError(it) }.collect { requests = it }
    }
    fun action(block: suspend () -> Unit) {
        if (busy) return
        busy = true
        saveError = null
        scope.launch {
            try {
                block()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                saveError = deskError(error)
            } finally {
                busy = false
            }
        }
    }
    BackHandler(enabled = page.isNotEmpty()) {
        if (!busy) {
            page = ""
            saveError = null
        }
    }
    val context = LocalContext.current
    val fileSaver =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { uri ->
            if (uri != null && page.isNotEmpty()) action { desk.saveDesign(page, uri, context) }
        }
    key(page) {
        Column(
            Modifier.widthIn(max = 700.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (page.isNotEmpty())
                TextButton(
                    onClick = {
                        page = ""
                        saveError = null
                    },
                    enabled = !busy,
                ) {
                    Text("Back to service requests")
                }
            loadError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            saveError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            when {
                loadError != null -> Unit
                requests == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
                page == "new" ->
                    ServiceForm(documents, accepting, busy, action = ::action) {
                        id,
                        service,
                        quantity,
                        details,
                        size,
                        sides,
                        design ->
                        desk.submit(session, id, service, quantity, details, size, sides, design)
                        page = id
                    }
                page.isNotEmpty() -> {
                    val request = requests?.find { it.id == page }
                    if (request == null) Text("Loading request…")
                    else {
                        ServiceDetail(
                            request,
                            session,
                            busy,
                            onSaveDesign = { fileSaver.launch(it) },
                            onChange = { change, quote, pickup ->
                                action { desk.update(session, request, change, quote, pickup) }
                            },
                        )
                        MessageThread(desk, session, "serviceRequests", request.id)
                    }
                }
                else -> {
                    if (!session.admin)
                        Button(
                            onClick = { page = "new" },
                            enabled = accepting,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (accepting) "Request a service" else "Online requests paused")
                        }
                    if (requests?.isEmpty() == true)
                        Panel(Modifier.fillMaxWidth()) {
                            Text(
                                "No service requests yet",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            Text("Requests and shop quotes appear here.")
                        }
                    for (request in requests.orEmpty()) OutlinedCard(
                        onClick = { page = request.id },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                shopServices[request.service].orEmpty(),
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Text(request.status, color = MaterialTheme.colorScheme.primary)
                            Text(
                                if (session.admin) request.customerName
                                else formatDate(request.createdAt)
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceForm(
    documents: Documents,
    accepting: Boolean,
    busy: Boolean,
    action: (suspend () -> Unit) -> Unit,
    submit: suspend (String, String, Int, String, String, String, PdfFile?) -> Unit,
) {
    val id = rememberSaveable { "service-${UUID.randomUUID()}" }
    var service by rememberSaveable { mutableStateOf("pvc-id") }
    var quantity by rememberSaveable { mutableStateOf("1") }
    var details by rememberSaveable { mutableStateOf("") }
    var size by rememberSaveable { mutableStateOf("") }
    var sides by rememberSaveable { mutableStateOf("Single-sided") }
    var design by remember { mutableStateOf<PdfFile?>(null) }
    var menu by remember { mutableStateOf(false) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) action { design = documents.import(uri) }
        }
    Panel(Modifier.fillMaxWidth()) {
        Text("Request a service", style = MaterialTheme.typography.titleLarge)
        Text("The shop will confirm availability, total price, and pickup before you approve.")
        ExposedDropdownMenuBox(expanded = menu, onExpandedChange = { if (!busy) menu = it }) {
            OutlinedTextField(
                value = shopServices.getValue(service),
                onValueChange = {},
                readOnly = true,
                enabled = !busy,
                label = { Text("Service") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = menu) },
                modifier = Modifier.menuAnchor(MenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
            )
            ExposedDropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                for ((value, label) in shopServices) {
                    DropdownMenuItem(
                        text = { Text(label) },
                        onClick = {
                            service = value
                            menu = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            quantity,
            { quantity = it.filter(Char::isDigit).take(4) },
            label = { Text("Quantity") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !busy,
        )
        if (service == "tarpaulin")
            OutlinedTextField(
                size,
                { size = it.take(120) },
                label = { Text("Dimensions and unit") },
                placeholder = { Text("e.g. 3 × 4 ft") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
        if (isIdService(service)) {
            Text("Print sides")
            for (side in listOf("Single-sided", "Double-sided")) Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = sides == side, onClick = { sides = side }, enabled = !busy)
                Text(side)
            }
        }
        OutlinedTextField(
            details,
            { details = it.take(1000) },
            label = { Text("Instructions (optional)") },
            placeholder = {
                Text(
                    if (service in listOf("scan", "photocopy"))
                        "Tell the shop when you can bring the original."
                    else "Materials, color, layout, or other preferences"
                )
            },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
            enabled = !busy,
        )
        OutlinedButton(
            onClick = { pick.launch(arrayOf("application/pdf", "image/jpeg", "image/png")) },
            enabled = !busy,
        ) {
            Text(if (design == null) "Attach design (optional)" else "Replace design")
        }
        Text(design?.name ?: "One PDF, JPG, or PNG up to 5 MB.")
        if (design != null)
            TextButton(onClick = { design = null }, enabled = !busy) { Text("Remove attachment") }
        Button(
            onClick = {
                action {
                    submit(
                        id,
                        service,
                        quantity.toIntOrNull() ?: 0,
                        details,
                        if (service == "tarpaulin") size else "",
                        sides,
                        design,
                    )
                }
            },
            enabled =
                accepting &&
                    !busy &&
                    (quantity.toIntOrNull() ?: 0) in 1..1000 &&
                    (service != "tarpaulin" || size.isNotBlank()),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (busy) "Please wait…" else "Send request")
        }
    }
}

@Composable
private fun ServiceDetail(
    request: ServiceRequest,
    session: Session,
    busy: Boolean,
    onSaveDesign: (String) -> Unit,
    onChange: (String, Long?, String) -> Unit,
) {
    Panel(Modifier.fillMaxWidth()) {
        Text(shopServices[request.service].orEmpty(), style = MaterialTheme.typography.titleLarge)
        Text(request.status, color = MaterialTheme.colorScheme.primary)
        if (session.admin) DetailLine("Customer", request.customerName)
        DetailLine("Quantity", request.quantity.toString())
        if (request.sides.isNotBlank()) DetailLine("Print sides", request.sides)
        if (request.size.isNotBlank()) DetailLine("Dimensions", request.size)
        if (request.details.isNotBlank()) DetailLine("Instructions", request.details)
        request.designName?.let { name ->
            OutlinedButton(onClick = { onSaveDesign(name) }, enabled = !busy) {
                Text("Download $name")
            }
        }
        if (request.quote != null) {
            DetailLine("Shop quote", pesos(request.quote))
            DetailLine("Pickup", request.pickup)
        } else Text("Waiting for the shop to confirm the price and pickup.")
        if (!session.admin && request.status == "Quoted") {
            Button(onClick = { onChange("Approved", null, "") }, enabled = !busy) {
                Text("Approve quote")
            }
            OutlinedButton(onClick = { onChange("Declined", null, "") }, enabled = !busy) {
                Text("Decline quote")
            }
        }
        if (session.admin)
            serviceNextStatus[request.status]?.let { next ->
                Button(onClick = { onChange("progress", null, "") }, enabled = !busy) {
                    Text("Mark ${next.lowercase()}")
                }
            }
    }
    if (session.admin && request.status in serviceQuoteStates) {
        var quote by
            rememberSaveable(request.version) {
                mutableStateOf(request.quote?.toString().orEmpty())
            }
        var pickup by rememberSaveable(request.version) { mutableStateOf(request.pickup) }
        Panel(Modifier.fillMaxWidth()) {
            Text(
                if (request.status == "Quoted") "Update quote" else "Send a quote",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                quote,
                { quote = it.filter(Char::isDigit).take(9) },
                label = { Text("Total (₱)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
            OutlinedTextField(
                pickup,
                { pickup = it.take(250) },
                label = { Text("Pickup schedule and instructions") },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            Button(
                onClick = { onChange("quote", quote.toLongOrNull(), pickup) },
                enabled =
                    !busy && (quote.toLongOrNull() ?: 0L) in 1L..200000000L && pickup.isNotBlank(),
            ) {
                Text("Send quote")
            }
        }
    }
}

@Composable
internal fun MessageThread(desk: ServiceDesk, session: Session, parent: String, id: String) {
    var messages by remember(id, session.uid) { mutableStateOf<List<DeskMessage>?>(null) }
    var error by remember(id, session.uid) { mutableStateOf<String?>(null) }
    var sendError by remember { mutableStateOf<String?>(null) }
    var text by rememberSaveable(id, session.uid) { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val scroll = rememberScrollState()
    LaunchedEffect(messages?.lastOrNull()?.id) {
        if (!messages.isNullOrEmpty()) scroll.scrollTo(scroll.maxValue)
    }
    LaunchedEffect(id, session.uid, parent) {
        desk.messages(parent, id).catch { error = deskError(it) }.collect { messages = it }
    }
    Panel(Modifier.fillMaxWidth()) {
        Text(
            if (session.admin) "Message customer" else "Message shop",
            style = MaterialTheme.typography.titleLarge,
        )
        Text(
            "Visible to the customer and authorized shop staff.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when {
            error != null -> Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
            messages == null -> LinearProgressIndicator(Modifier.fillMaxWidth())
            else -> {
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(scroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    for (message in messages.orEmpty()) Surface(
                        modifier =
                            Modifier.align(
                                    if (message.senderId == session.uid) Alignment.End
                                    else Alignment.Start
                                )
                                .widthIn(max = 460.dp),
                        shape = MaterialTheme.shapes.medium,
                        color =
                            if (message.senderId == session.uid)
                                MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Column(
                            Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(message.senderRole, style = MaterialTheme.typography.labelLarge)
                            Text(message.text)
                            if (message.createdAt > 0)
                                Text(
                                    formatDate(message.createdAt),
                                    style = MaterialTheme.typography.labelSmall,
                                )
                        }
                    }
                }
                if (messages?.isEmpty() == true) Text("No messages yet.")
                OutlinedTextField(
                    text,
                    { text = it.take(1000) },
                    label = { Text("Message") },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !sending,
                )
                sendError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = {
                        sending = true
                        sendError = null
                        scope.launch {
                            try {
                                desk.send(session, parent, id, text)
                                text = ""
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                sendError = deskError(e)
                            } finally {
                                sending = false
                            }
                        }
                    },
                    enabled = !sending && text.isNotBlank(),
                ) {
                    Text(if (sending) "Sending…" else "Send message")
                }
            }
        }
    }
}
