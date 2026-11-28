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
import java.io.File
import java.time.LocalDateTime
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
    shopName: String,
    enhanced: Boolean,
    alerts: List<DeskAlert>,
    selectedRequest: String,
) {
    var requests by remember(session.uid) { mutableStateOf<List<ServiceRequest>?>(null) }
    var loadError by remember(session.uid) { mutableStateOf<String?>(null) }
    var page by rememberSaveable(session.uid, selectedRequest) { mutableStateOf(selectedRequest) }
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
    var savingDesignId by rememberSaveable(session.uid) { mutableStateOf<String?>(null) }
    var savingBack by rememberSaveable(session.uid) { mutableStateOf(false) }
    var previewDesign by
        remember(session.uid, page) { mutableStateOf<Pair<ServiceRequest, Boolean>?>(null) }
    val context = LocalContext.current
    val fileSaver =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { uri ->
            val id = savingDesignId
            savingDesignId = null
            val back = savingBack
            if (uri != null && id != null) action { desk.saveDesign(id, uri, context, back) }
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
                PressTextButton(
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
                    ServiceForm(
                        documents,
                        desk,
                        session.uid,
                        enhanced,
                        accepting,
                        busy,
                        action = ::action,
                    ) { draft ->
                        desk.submit(
                            session,
                            draft.id,
                            draft.service,
                            draft.quantity.toIntOrNull() ?: 0,
                            draft.details,
                            if (draft.service == "tarpaulin") draft.size else "",
                            draft.sides,
                            draft.front,
                            if (enhanced) draft.back else null,
                        )
                        desk.clearDraft(session.uid)
                        page = draft.id
                    }
                page.isNotEmpty() -> {
                    val request = requests?.find { it.id == page }
                    if (request == null) Text("Loading request…")
                    else {
                        ServiceDetail(
                            request,
                            session,
                            busy,
                            enhanced = enhanced,
                            onPreviewDesign = { back -> previewDesign = request to back },
                            onSaveDesign = { name, back ->
                                savingDesignId = request.id
                                savingBack = back
                                fileSaver.launch(name.replace('/', '_').replace('\\', '_'))
                            },
                            onChange = { change, quote, pickup, breakdown ->
                                action {
                                    desk.update(
                                        session,
                                        request,
                                        change,
                                        quote,
                                        pickup,
                                        breakdown,
                                        enhanced,
                                    )
                                }
                            },
                        )
                        MessageThread(
                            desk,
                            session,
                            "serviceRequests",
                            request.id,
                            request.customerName,
                            shopName,
                            request.ownerId,
                            enhanced,
                            alerts.find { it.kind == "serviceRequests" && it.id == request.id },
                        )
                    }
                }
                else -> {
                    if (!session.admin)
                        PressButton(
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
                            Text(
                                if (
                                    alerts.any {
                                        it.kind == "serviceRequests" && it.id == request.id
                                    }
                                )
                                    "Unread · ${request.status}"
                                else request.status,
                                color = MaterialTheme.colorScheme.primary,
                            )
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
    previewDesign?.let { (request, back) ->
        PreviewDialog(
            name = (if (back) request.backName else request.designName).orEmpty(),
            documents = documents,
            load = { desk.loadDesign(request.id, back) },
            deleteLoadedFile = !BuildConfig.DEMO,
            onClose = { previewDesign = null },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServiceForm(
    documents: Documents,
    desk: ServiceDesk,
    uid: String,
    enhanced: Boolean,
    accepting: Boolean,
    busy: Boolean,
    action: (suspend () -> Unit) -> Unit,
    submit: suspend (ServiceDraft) -> Unit,
) {
    var draft by remember(uid) { mutableStateOf(desk.restoreDraft(uid)) }
    fun change(value: ServiceDraft) {
        draft = value
        desk.saveDraft(uid, value)
    }
    var discard by rememberSaveable { mutableStateOf(false) }
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Discard this draft?") },
            text = { Text("This clears the details and attachments saved on this device.") },
            confirmButton = {
                PressTextButton({
                    discard = false
                    desk.clearDraft(uid)
                    draft.front?.let { File(it.path).delete() }
                    draft.back?.let { File(it.path).delete() }
                    draft = ServiceDraft()
                }) {
                    Text("Discard draft")
                }
            },
            dismissButton = { PressTextButton({ discard = false }) { Text("Keep draft") } },
        )
    val service = draft.service
    val quantity = draft.quantity
    val size = draft.size
    val sides = draft.sides
    val design = draft.front
    val double = enhanced && isIdService(service) && sides == "Double-sided"
    var preview by remember { mutableStateOf<PdfFile?>(null) }
    var menu by remember { mutableStateOf(false) }
    var pickBack by rememberSaveable { mutableStateOf(false) }
    val pick =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            val back = pickBack
            if (uri != null)
                action {
                    val file = documents.import(uri)
                    val previous = if (back) draft.back else draft.front
                    change(if (back) draft.copy(back = file) else draft.copy(front = file))
                    previous?.let { File(it.path).delete() }
                }
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
                            change(draft.copy(service = value))
                            menu = false
                        },
                    )
                }
            }
        }
        OutlinedTextField(
            quantity,
            { change(draft.copy(quantity = it.filter(Char::isDigit).take(4))) },
            label = { Text("Quantity") },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            enabled = !busy,
        )
        if (service == "tarpaulin")
            OutlinedTextField(
                size,
                { change(draft.copy(size = it.take(120))) },
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
                RadioButton(
                    selected = sides == side,
                    onClick = { change(draft.copy(sides = side)) },
                    enabled = !busy,
                )
                Text(side)
            }
        }
        OutlinedTextField(
            draft.details,
            { change(draft.copy(details = it.take(1000))) },
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
        val files =
            if (double) listOf(false to design, true to draft.back) else listOf(false to design)
        for ((back, file) in files) {
            if (double)
                Text(
                    if (back) "Back design" else "Front design",
                    style = MaterialTheme.typography.labelLarge,
                )
            Text(file?.name ?: "PDF, JPG, or PNG up to $MAX_FILE_MB MB.")
            PressOutlinedButton(
                onClick = {
                    pickBack = back
                    pick.launch(arrayOf("application/pdf", "image/jpeg", "image/png"))
                },
                enabled = !busy,
            ) {
                Text(if (file == null) "Attach design (optional)" else "Replace design")
            }
            if (file != null) {
                PressOutlinedButton(onClick = { preview = file }, enabled = !busy) {
                    Text("Preview design")
                }
                PressTextButton(
                    onClick = {
                        change(if (back) draft.copy(back = null) else draft.copy(front = null))
                        File(file.path).delete()
                    },
                    enabled = !busy,
                ) {
                    Text("Remove attachment")
                }
            }
        }
        Text("Draft saved on this device.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        PressTextButton(onClick = { discard = true }, enabled = !busy) { Text("Discard draft") }
        PressButton(
            onClick = {
                action {
                    submit(draft)
                    draft.front?.let { File(it.path).delete() }
                    draft.back?.let { File(it.path).delete() }
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
    preview?.let { file ->
        PreviewDialog(
            name = file.name,
            documents = documents,
            load = { File(file.path) },
            onClose = { preview = null },
        )
    }
}

@Composable
private fun ServiceDetail(
    request: ServiceRequest,
    session: Session,
    busy: Boolean,
    enhanced: Boolean,
    onPreviewDesign: (Boolean) -> Unit,
    onSaveDesign: (String, Boolean) -> Unit,
    onChange: (String, Long?, String, ServiceQuote?) -> Unit,
) {
    var confirmCancel by rememberSaveable(request.id) { mutableStateOf(false) }
    if (confirmCancel)
        AlertDialog(
            onDismissRequest = { confirmCancel = false },
            title = { Text("Cancel this service request?") },
            text = { Text("The shop will see that you cancelled.") },
            confirmButton = {
                PressTextButton({
                    confirmCancel = false
                    onChange("Cancelled", null, "", null)
                }) {
                    Text("Cancel request")
                }
            },
            dismissButton = { PressTextButton({ confirmCancel = false }) { Text("Keep request") } },
        )
    Panel(Modifier.fillMaxWidth()) {
        Text(shopServices[request.service].orEmpty(), style = MaterialTheme.typography.titleLarge)
        Text(request.status, color = MaterialTheme.colorScheme.primary)
        if (session.admin) DetailLine("Customer", request.customerName)
        DetailLine("Quantity", request.quantity.toString())
        if (request.sides.isNotBlank()) DetailLine("Print sides", request.sides)
        if (request.size.isNotBlank()) DetailLine("Dimensions", request.size)
        if (request.details.isNotBlank()) DetailLine("Instructions", request.details)
        if (request.removed)
            Text("The design files were removed under the shop’s retention policy.")
        else
            for ((back, name) in listOf(false to request.designName, true to request.backName)) {
                if (name == null) continue
                DetailLine(
                    if (back) "Back design"
                    else if (request.backName != null) "Front design" else "Design file",
                    name,
                )
                PressOutlinedButton(onClick = { onPreviewDesign(back) }, enabled = !busy) {
                    Text("Preview design")
                }
                PressTextButton(onClick = { onSaveDesign(name, back) }, enabled = !busy) {
                    Text("Download PDF")
                }
            }
        if (request.quote != null) {
            DetailLine("Shop quote", pesos(request.quote))
            request.quoteDetails?.let {
                DetailLine("Service", pesos(it.serviceFee))
                DetailLine("Extras", pesos(it.extrasFee))
                DetailLine("Pickup (PH time)", formatDate(it.pickupAt))
            }
            DetailLine(
                if (request.quoteDetails == null) "Pickup" else "Instructions",
                request.pickup,
            )
        } else if (request.status != "Cancelled")
            Text("Waiting for the shop to confirm the price and pickup.")
        if (!session.admin && enhanced && request.status in serviceQuoteStates) {
            PressTextButton(onClick = { confirmCancel = true }, enabled = !busy) {
                Text("Cancel request")
            }
        }
        if (!session.admin && request.status == "Quoted") {
            PressButton(onClick = { onChange("Approved", null, "", null) }, enabled = !busy) {
                Text("Approve quote")
            }
            PressOutlinedButton(
                onClick = { onChange("Declined", null, "", null) },
                enabled = !busy,
            ) {
                Text("Decline quote")
            }
        }
        if (session.admin)
            serviceNextStatus[request.status]?.let { next ->
                PressButton(onClick = { onChange("progress", null, "", null) }, enabled = !busy) {
                    Text("Mark ${next.lowercase()}")
                }
            }
    }
    if (
        session.admin &&
            (request.status in serviceQuoteStates || enhanced && request.status == "Approved")
    ) {
        var quote by
            rememberSaveable(request.version) {
                mutableStateOf(
                    (request.quoteDetails?.serviceFee ?: request.quote)?.toString().orEmpty()
                )
            }
        var pickup by rememberSaveable(request.version) { mutableStateOf(request.pickup) }
        var extras by
            rememberSaveable(request.version) {
                mutableStateOf(request.quoteDetails?.extrasFee?.toString().orEmpty())
            }
        var pickupDate by
            rememberSaveable(request.version) {
                mutableStateOf(
                    request.quoteDetails
                        ?.let {
                            java.time.Instant.ofEpochMilli(it.pickupAt)
                                .atZone(campusZone)
                                .toLocalDateTime()
                                .toString()
                                .take(16)
                                .replace('T', ' ')
                        }
                        .orEmpty()
                )
            }
        val whenPickup =
            runCatching {
                    LocalDateTime.parse(pickupDate.trim().replace(' ', 'T'))
                        .atZone(campusZone)
                        .toInstant()
                        .toEpochMilli()
                }
                .getOrNull()
        val price = quote.toLongOrNull() ?: 0L
        val extrasPrice = extras.toLongOrNull() ?: 0L
        val breakdown =
            if (enhanced && whenPickup != null) ServiceQuote(price, extrasPrice, whenPickup)
            else null
        Panel(Modifier.fillMaxWidth()) {
            Text(
                if (request.status == "Quoted") "Update quote" else "Send a quote",
                style = MaterialTheme.typography.titleLarge,
            )
            OutlinedTextField(
                quote,
                { quote = it.filter(Char::isDigit).take(9) },
                label = { Text(if (enhanced) "Service price (₱)" else "Total (₱)") },
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                enabled = !busy,
            )
            if (enhanced) {
                OutlinedTextField(
                    extras,
                    { extras = it.filter(Char::isDigit).take(9) },
                    label = { Text("Extras (₱)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    pickupDate,
                    { pickupDate = it.take(16) },
                    label = { Text("Pickup date and time (PH time)") },
                    placeholder = { Text("YYYY-MM-DD HH:MM") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    enabled = !busy,
                )
                Text(
                    "Total ${pesos(price + extrasPrice)}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            OutlinedTextField(
                pickup,
                { pickup = it.take(250) },
                label = {
                    Text(
                        if (enhanced) "Pickup instructions" else "Pickup schedule and instructions"
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !busy,
            )
            PressButton(
                onClick = {
                    onChange(
                        "quote",
                        price + extrasPrice,
                        pickup.ifBlank { "Collect and pay at the shop." },
                        breakdown,
                    )
                },
                enabled =
                    !busy &&
                        price > 0 &&
                        price + extrasPrice in 1L..200000000L &&
                        (if (enhanced) breakdown != null else pickup.isNotBlank()),
            ) {
                Text("Send quote")
            }
        }
    }
}

@Composable
internal fun MessageThread(
    desk: ServiceDesk,
    session: Session,
    parent: String,
    id: String,
    customerName: String,
    shopName: String,
    ownerId: String,
    enhanced: Boolean,
    alert: DeskAlert?,
) {
    val customer =
        customerName.trim().ifBlank {
            if (session.admin) "Customer" else session.name.trim().ifBlank { "Customer" }
        }
    val shop = shopName.trim().ifBlank { "Nano Print Alangilan" }
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
    LaunchedEffect(alert?.time, messages != null) {
        if (enhanced && alert != null && messages != null && error == null) {
            runCatching { desk.markRead(session, parent, id) }
        }
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
                            Text(
                                if (message.senderRole == "Shop") shop else customer,
                                style = MaterialTheme.typography.labelLarge,
                            )
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
                PressButton(
                    onClick = {
                        sending = true
                        sendError = null
                        scope.launch {
                            try {
                                desk.send(session, parent, id, text, ownerId, enhanced)
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
