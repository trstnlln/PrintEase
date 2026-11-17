package com.printease.app

import android.content.Intent
import android.net.Uri
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

@Composable
fun ShopCard(settings: ShopSettings, modifier: Modifier = Modifier, onEdit: (() -> Unit)? = null) {
    val context = LocalContext.current
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
                runCatching {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(
                                "https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}"
                            ),
                        )
                    )
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
    }
}

@Composable
fun ShopSettingsContent(settings: ShopSettings, busy: Boolean, onSave: (ShopSettings) -> Unit) {
    // Keep an editing snapshot. A concurrent save is rejected by the repository version check.
    var name by rememberSaveable { mutableStateOf(settings.name) }
    var address by rememberSaveable { mutableStateOf(settings.address) }
    var hours by rememberSaveable { mutableStateOf(settings.pickupHours) }
    var accepting by rememberSaveable { mutableStateOf(settings.acceptingOrders) }
    var bw by rememberSaveable { mutableStateOf(settings.rates.bw.toString()) }
    var color by rememberSaveable { mutableStateOf(settings.rates.color.toString()) }
    var legal by rememberSaveable { mutableStateOf(settings.rates.legalExtra.toString()) }
    var retention by rememberSaveable { mutableIntStateOf(settings.retentionDays) }
    val version = rememberSaveable { settings.version }
    val valid =
        name.trim().length in 2..100 &&
            address.trim().length in 2..300 &&
            hours.trim().length in 2..200 &&
            bw.toLongOrNull() in 1L..1000L &&
            color.toLongOrNull() in 1L..1000L &&
            legal.toLongOrNull() in 0L..1000L
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
        Panel(Modifier.fillMaxWidth()) {
            Text("Completed PDF retention", style = MaterialTheme.typography.titleLarge)
            Text(
                "Choose how long to keep PDFs after pickup is marked completed. Order history stays saved. Automatic removal is off by default.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                listOf(0, 7, 14, 30).forEach { days ->
                    FilterChip(
                        retention == days,
                        { retention = days },
                        label = { Text(if (days == 0) "Off" else "${days}d") },
                        enabled = !busy,
                    )
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
            "Get a pickup notification on this device. Your document name stays private on the lock screen.",
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
