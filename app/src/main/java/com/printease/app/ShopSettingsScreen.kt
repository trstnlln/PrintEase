package com.printease.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val nanoPrintServices =
    listOf(
        "Print & edit",
        "Photocopy",
        "Bookbind",
        "Scan",
        "Tarpapel",
        "School supplies",
        "Laminate",
        "Rush ID",
        "Vellum / Magazine",
        "Tarpaulin layout & CAD",
        "Brochure & Booklet",
    )

@Composable
fun ShopCard(settings: ShopSettings, modifier: Modifier = Modifier, onEdit: (() -> Unit)? = null) {
    val context = LocalContext.current
    val isNanoPrint = settings.name in listOf("Nano Print Alangilan", "Nano Alangilan")
    var showServices by rememberSaveable(settings.name) { mutableStateOf(false) }
    Panel(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(
                    Icons.Default.Storefront,
                    null,
                    Modifier.padding(12.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(settings.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (settings.acceptingOrders) "Accepting requests" else "New requests paused",
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (settings.acceptingOrders) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.error,
                )
            }
            if (onEdit != null) IconButton(onEdit) { Icon(Icons.Default.Edit, "Edit shop details") }
        }
        Text(settings.address, style = MaterialTheme.typography.bodyMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Default.Schedule, null, Modifier.size(18.dp))
            Text(settings.pickupHours, style = MaterialTheme.typography.bodySmall)
        }
        Text(
            "Collect and pay at the shop near campus.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            {
                val query =
                    if (
                        settings.name == "Nearby print shop" &&
                            settings.address == "Near BSU Alangilan campus"
                    )
                        "printing shops near BSU Alangilan"
                    else "${settings.name} ${settings.address}"
                val mapsUrl =
                    if (isNanoPrint) "https://maps.app.goo.gl/gBCZetYJyz46RoeQ9"
                    else "https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}"
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(mapsUrl)))
                }
            },
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Icon(Icons.Default.NearMe, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                if (settings.name == "Nearby print shop") "Find shops near campus"
                else "View location"
            )
        }
        if (settings.contactEmail.isNotBlank() || settings.facebookUrl.isNotBlank()) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (settings.contactEmail.isNotBlank())
                    OutlinedButton(
                        {
                            runCatching {
                                    context.startActivity(
                                        Intent(
                                            Intent.ACTION_SENDTO,
                                            Uri.fromParts("mailto", settings.contactEmail, null),
                                        )
                                    )
                                }
                                .onFailure {
                                    android.widget.Toast.makeText(
                                            context,
                                            "No email app found. Email ${settings.contactEmail}",
                                            android.widget.Toast.LENGTH_LONG,
                                        )
                                        .show()
                                }
                        },
                        Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text("Email shop")
                    }
                if (settings.facebookUrl.isNotBlank())
                    OutlinedButton(
                        {
                            runCatching {
                                    context.startActivity(
                                        Intent(Intent.ACTION_VIEW, Uri.parse(settings.facebookUrl))
                                    )
                                }
                                .onFailure {
                                    android.widget.Toast.makeText(
                                            context,
                                            "No browser app is available.",
                                            android.widget.Toast.LENGTH_LONG,
                                        )
                                        .show()
                                }
                        },
                        Modifier.weight(1f).heightIn(min = 48.dp),
                    ) {
                        Text("Facebook")
                    }
            }
        }
        if (isNanoPrint) {
            HorizontalDivider()
            TextButton(
                onClick = { showServices = !showServices },
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            ) {
                Text(if (showServices) "Hide shop services" else "View shop services")
                Spacer(Modifier.width(8.dp))
                Icon(if (showServices) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (showServices) {
                Text(
                    "Send PDF print requests in PrintEase. For other services and prices, contact the shop.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    nanoPrintServices.forEach { service ->
                        Text("•  $service", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
fun ShopSettingsContent(
    settings: ShopSettings,
    busy: Boolean,
    retentionAvailable: Boolean = false,
    scheduleAvailable: Boolean = false,
    stockAvailable: Boolean = false,
    onSave: (ShopSettings) -> Unit,
) {
    // Keep an editing snapshot. A concurrent save is rejected by the repository version check.
    var available by rememberSaveable { mutableStateOf(ArrayList(settings.paperStock.available)) }
    var out by rememberSaveable { mutableStateOf(ArrayList(settings.paperStock.out)) }
    val paperStock = PaperStock(available, out)
    var name by rememberSaveable { mutableStateOf(settings.name) }
    var address by rememberSaveable { mutableStateOf(settings.address) }
    var hours by rememberSaveable { mutableStateOf(settings.pickupHours) }
    var email by rememberSaveable { mutableStateOf(settings.contactEmail) }
    var facebook by rememberSaveable { mutableStateOf(settings.facebookUrl) }
    var ratesConfirmed by rememberSaveable { mutableStateOf(settings.ratesConfirmed) }
    var accepting by rememberSaveable { mutableStateOf(settings.acceptingOrders) }
    var bw by rememberSaveable { mutableStateOf(settings.rates.bw.toString()) }
    var color by rememberSaveable { mutableStateOf(settings.rates.color.toString()) }
    var legal by rememberSaveable { mutableStateOf(settings.rates.legalExtra.toString()) }
    var retention by rememberSaveable { mutableIntStateOf(settings.retentionDays) }
    val existing = settings.pickupSchedule
    var scheduleOn by rememberSaveable { mutableStateOf(existing != null) }
    var openingDays by rememberSaveable {
        mutableIntStateOf(existing?.days?.sumOf { 1 shl it } ?: 0)
    }
    var opens by rememberSaveable { mutableStateOf(inputTime(existing?.open ?: 360)) }
    var closes by rememberSaveable { mutableStateOf(inputTime(existing?.close ?: 1320)) }
    var cutoff by rememberSaveable { mutableStateOf(inputTime(existing?.cutoff)) }
    var closures by rememberSaveable { mutableStateOf(existing?.closed.orEmpty()) }
    val schedule =
        if (!scheduleAvailable) existing
        else if (!scheduleOn) null
        else
            runCatching {
                    PickupSchedule(
                        (1..7).filter { day -> openingDays and (1 shl day) != 0 },
                        requireNotNull(clockMinutes(opens)),
                        requireNotNull(clockMinutes(closes)),
                        if (cutoff.isBlank()) null else requireNotNull(clockMinutes(cutoff)),
                        closures.split(',').joinToString(",") { it.trim() },
                    )
                }
                .getOrNull()
    val version = rememberSaveable { settings.version }
    val valid =
        name.trim().length in 2..100 &&
            address.trim().length in 2..300 &&
            hours.trim().length in 2..200 &&
            bw.toLongOrNull() in 1L..1000L &&
            color.toLongOrNull() in 1L..1000L &&
            legal.toLongOrNull() in 0L..1000L &&
            (email.isBlank() ||
                (email.length <= 254 &&
                    android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches())) &&
            validFacebookUrl(facebook.trim()) &&
            (!scheduleAvailable || !scheduleOn || schedule != null)
    Column(
        Modifier.widthIn(max = 700.dp)
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Eyebrow("YOUR SHOP, YOUR WAY")
        Text("Shop settings", style = MaterialTheme.typography.headlineLarge)
        Text(
            "Help students know where to collect, when to visit, and what their prints will cost.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Panel(Modifier.fillMaxWidth()) {
            Text("Pickup details", style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                name,
                { name = it.take(100) },
                label = { Text("Shop name") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            OutlinedTextField(
                address,
                { address = it.take(300) },
                label = { Text("Address or landmark near campus") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
            )
            OutlinedTextField(
                hours,
                { hours = it.take(200) },
                label = { Text("Pickup hours") },
                placeholder = { Text("e.g. Mon–Sat, 8 AM–6 PM") },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 3,
            )
            OutlinedTextField(
                email,
                { email = it.take(254) },
                Modifier.fillMaxWidth(),
                label = { Text("Email contact") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                enabled = !busy,
                singleLine = true,
            )
            OutlinedTextField(
                facebook,
                { facebook = it.take(2048) },
                Modifier.fillMaxWidth(),
                label = { Text("Facebook page") },
                placeholder = { Text("https://www.facebook.com/…") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                enabled = !busy,
                singleLine = true,
            )
            PreferenceSwitch(
                "Accept new requests",
                "Pause submissions while your shop is unavailable.",
                accepting,
                !busy,
            ) {
                accepting = it
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("Print rates", style = MaterialTheme.typography.titleLarge)
            PreferenceSwitch(
                "Rates confirmed with the shop",
                "Leave off until the shop verifies the prices below.",
                ratesConfirmed,
                !busy,
            ) {
                ratesConfirmed = it
            }
            Text(
                "Whole Philippine pesos per printed side. New rates apply to new requests; existing quotes stay the same.",
                style = MaterialTheme.typography.bodySmall,
            )
            listOf(
                    Triple("Black & white · A4 / Letter", bw, { v: String -> bw = v }),
                    Triple("Color · A4 / Letter", color, { v: String -> color = v }),
                    Triple("Legal paper surcharge", legal, { v: String -> legal = v }),
                )
                .forEach { (label, value, update) ->
                    OutlinedTextField(
                        value,
                        { update(it.filter(Char::isDigit).take(4)) },
                        label = { Text(label) },
                        prefix = { Text("₱") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        enabled = !busy,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            Text(
                "Base rates: ₱1–₱1,000. Legal surcharge: ₱0–₱1,000.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (scheduleAvailable)
            Panel(Modifier.fillMaxWidth()) {
                Text("Pickup schedule", style = MaterialTheme.typography.titleLarge)
                PreferenceSwitch(
                    "Show pickup windows",
                    "Times use Philippine time",
                    scheduleOn,
                    !busy,
                ) {
                    scheduleOn = it
                }
                if (scheduleOn) {
                    Text("Opening days", style = MaterialTheme.typography.titleMedium)
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").forEachIndexed {
                            index,
                            label ->
                            val bit = 1 shl (index + 1)
                            FilterChip(
                                openingDays and bit != 0,
                                { openingDays = openingDays xor bit },
                                { Text(label) },
                                enabled = !busy,
                            )
                        }
                    }
                    OutlinedTextField(
                        opens,
                        { opens = it.take(5) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Opens (HH:mm)") },
                        enabled = !busy,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        closes,
                        { closes = it.take(5) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Closes (HH:mm)") },
                        enabled = !busy,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        cutoff,
                        { cutoff = it.take(5) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Same-day cutoff (optional)") },
                        supportingText = { Text("Use 24-hour time, such as 16:30.") },
                        enabled = !busy,
                        singleLine = true,
                    )
                    OutlinedTextField(
                        closures,
                        { closures = it.take(180) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Closure dates") },
                        supportingText = { Text("Separate dates with commas. Use YYYY-MM-DD.") },
                        enabled = !busy,
                        maxLines = 3,
                    )
                    if (schedule == null)
                        Text(
                            "Choose opening days and check the hours and dates.",
                            color = MaterialTheme.colorScheme.error,
                        )
                }
            }
        Panel(Modifier.fillMaxWidth()) {
            Text("PDF retention", style = MaterialTheme.typography.titleLarge)
            if (retentionAvailable) {
                Text(
                    "Allow staff to remove PDFs from completed orders after the selected period. The order history stays saved.",
                    style = MaterialTheme.typography.bodySmall,
                )
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val periods = listOf(0, 7, 14, 30)
                    periods.forEachIndexed { index, days ->
                        SegmentedButton(
                            retention == days,
                            { retention = days },
                            SegmentedButtonDefaults.itemShape(index, periods.size),
                            enabled = !busy,
                        ) {
                            Text(if (days == 0) "Keep" else "${days}d")
                        }
                    }
                }
            } else {
                Text(
                    "PDF cleanup is unavailable in this workspace.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (stockAvailable)
            Panel(Modifier.fillMaxWidth()) {
                Text("Paper availability", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Students see these statuses in the paper menu.",
                    style = MaterialTheme.typography.bodySmall,
                )
                stockPapers.forEach { paper ->
                    var expanded by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(
                            { expanded = true },
                            Modifier.fillMaxWidth(),
                            enabled = !busy,
                        ) {
                            Text(
                                "${if (paper == "Custom") "Custom sizes" else paper} · ${stockLabels.getValue(paperStock.status(paper))}",
                                Modifier.weight(1f),
                            )
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(expanded, { expanded = false }) {
                            stockLabels.forEach { (status, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        available =
                                            ArrayList(
                                                available.filter { it != paper } +
                                                    if (status == "available") listOf(paper)
                                                    else emptyList()
                                            )
                                        out =
                                            ArrayList(
                                                out.filter { it != paper } +
                                                    if (status == "out") listOf(paper)
                                                    else emptyList()
                                            )
                                        expanded = false
                                    },
                                )
                            }
                        }
                    }
                }
            }
        if (!valid)
            Text(
                "Complete the shop details and enter valid rates to save.",
                color = MaterialTheme.colorScheme.error,
            )
        PrimaryButton(
            if (busy) "Saving…" else "Save shop details",
            {
                onSave(
                    ShopSettings(
                        name.trim(),
                        address.trim(),
                        hours.trim(),
                        accepting,
                        PrintRates(bw.toLong(), color.toLong(), legal.toLong()),
                        retention,
                        version,
                        email.trim(),
                        facebook.trim(),
                        ratesConfirmed,
                        schedule,
                        paperStock,
                    )
                )
            },
            Modifier.fillMaxWidth(),
            valid && !busy,
            Icons.Default.Check,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
fun PickupAlertsCard() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember {
        mutableStateOf(PickupAlerts.enabled(context) && PickupAlerts.allowed(context))
    }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    fun update(value: Boolean) {
        saving = true
        scope.launch {
            try {
                PickupAlerts.setEnabled(context, value)
                enabled = value
                error = null
            } catch (_: Exception) {
                enabled = false
                error = "Could not update pickup alerts. Check your connection and try again."
            } finally {
                saving = false
            }
        }
    }
    val permission =
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
        ) { granted ->
            if (granted) update(true)
            else error = "Allow notifications in Android Settings to receive pickup alerts."
        }
    Panel(Modifier.fillMaxWidth()) {
        Text("A heads-up when it’s ready", style = MaterialTheme.typography.titleLarge)
        Text(
            "Receive private pickup reminders. Android checks periodically (about every 15 minutes) and may delay checks to save battery. Open Orders for the latest status.",
            style = MaterialTheme.typography.bodySmall,
        )
        PreferenceSwitch(
            "Pickup alerts",
            if (saving) "Updating…"
            else if (enabled) "Enabled on this device" else "Off on this device",
            enabled,
            !saving,
        ) { value ->
            if (value && !PickupAlerts.allowed(context))
                permission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else update(value)
        }
        error?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
