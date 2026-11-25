@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.printease.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun PrintEase(
    model: PrintViewModel,
    dark: Boolean = isSystemInDarkTheme(),
    onToggleTheme: (() -> Unit)? = null,
) {
    val authContext = LocalContext.current
    val state by model.shop.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val authError by model.authError.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val route by model.route.collectAsStateWithLifecycle()
    val selected by model.selectedId.collectAsStateWithLifecycle()
    val requestedAddons by model.addons.collectAsStateWithLifecycle()
    val revisionNote by model.revisionNote.collectAsStateWithLifecycle()
    val submittedId by model.submittedId.collectAsStateWithLifecycle()
    val pdf by model.pdf.collectAsStateWithLifecycle()
    val attachments by model.attachments.collectAsStateWithLifecycle()
    val options by model.options.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var signOut by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PrintOrder?>(null) }
    var previewIndex by remember { mutableIntStateOf(0) }
    var draftPreview by remember { mutableIntStateOf(-1) }
    var downloadOrder by rememberSaveable { mutableStateOf<String?>(null) }
    var downloadIndex by rememberSaveable { mutableIntStateOf(0) }
    val fileSaver =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/pdf")
        ) { uri ->
            val id = downloadOrder
            downloadOrder = null
            if (id != null && uri != null) model.savePrintFile(id, downloadIndex, uri)
        }
    LaunchedEffect(model) {
        model.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) }
    }
    val session = state.session
    val mainRoute = route in listOf("home", "orders", "account")
    BackHandler(enabled = session != null && !mainRoute) {
        if (!busy) model.navigate(if (route == "settings") "account" else "orders")
    }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            if (session != null)
                TopAppBar(
                    title = {
                        if (mainRoute) Brand()
                        else
                            Text(
                                when (route) {
                                    "request" -> "New print request"
                                    "settings" -> "Shop settings"
                                    else -> "Order details"
                                },
                                style = MaterialTheme.typography.titleLarge,
                            )
                    },
                    navigationIcon = {
                        if (!mainRoute)
                            IconButton(
                                onClick = {
                                    model.navigate(if (route == "settings") "account" else "orders")
                                },
                                enabled = !busy,
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                    },
                    actions = {
                        onToggleTheme?.let { ThemeModeButton(dark, it) }
                        if (BuildConfig.DEMO) ModeBadge()
                        if (mainRoute)
                            IconButton(onClick = { model.navigate("account") }, enabled = !busy) {
                                UserAvatar(session, size = 36.dp)
                            }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                )
        },
        bottomBar = {
            if (session != null && mainRoute)
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    listOf(
                            Triple("home", "Home", Icons.Default.GridView),
                            Triple(
                                "orders",
                                if (session.admin) "Queue" else "Orders",
                                Icons.AutoMirrored.Filled.ReceiptLong,
                            ),
                            Triple("account", "Account", Icons.Default.PersonOutline),
                        )
                        .forEach { (target, label, icon) ->
                            NavigationBarItem(
                                selected = route == target,
                                onClick = { model.navigate(target) },
                                icon = { Icon(icon, null) },
                                label = { Text(label) },
                            )
                        }
                }
        },
    ) { insets ->
        Box(
            Modifier.fillMaxSize().padding(insets).imePadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                model.setupError != null ->
                    Column(
                        Modifier.padding(28.dp).widthIn(max = 560.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Brand()
                        Text(
                            "Connect your Firebase project",
                            style = MaterialTheme.typography.headlineMedium,
                        )
                        Text(model.setupError!!)
                    }
                state.connecting ->
                    Column(
                        Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp),
                    ) {
                        Brand()
                        CircularProgressIndicator()
                        Text("Restoring your account…")
                    }
                session == null ->
                    AuthContent(
                        BuildConfig.DEMO,
                        busy,
                        authError ?: state.syncError,
                        model::demoLogin,
                        model::authenticate,
                        model::resetPassword,
                        onGoogle = { model.signInWithGoogle(authContext) },
                        dark = dark,
                        onToggleTheme = onToggleTheme,
                    )
                route == "request" ->
                    RequestContent(
                        pdf,
                        options,
                        busy,
                        progress,
                        onPick = { model.pickFiles(listOf(it)) },
                        onPickFiles = model::pickFiles,
                        onOptions = model::setOptions,
                        onSubmit = model::submit,
                        onPreview = { draftPreview = 0 },
                        settings = state.settings,
                        settingsError = state.settingsError,
                        queueStatus = state.queueStatus,
                        reviewAvailable = state.reviewAvailable,
                        paperSizesAvailable = state.paperSizesAvailable,
                        printToolsAvailable = state.printToolsAvailable,
                        onPreviewAttachment = { draftPreview = it + 1 },
                        onResizeImage = model::resizeImage,
                        requestedAddons = requestedAddons,
                        revisionNote = revisionNote,
                        onAddon = model::toggleAddon,
                        attachments = attachments,
                        onAddFiles = model::addFiles,
                        onChangeAttachment = model::changeAttachment,
                        onRemoveAttachment = model::removeAttachment,
                        onDiscard = {
                            model.discardDraft()
                            model.navigate("home")
                        },
                    )
                route == "detail" -> {
                    val order = state.orders.find { it.id == selected }
                    if (order != null)
                        OrderDetail(
                            order,
                            session.admin,
                            busy,
                            state.cached,
                            model::transition,
                            {
                                previewIndex = 0
                                preview = order
                            },
                            settings = state.settings,
                            onEstimate = model::setPickupTime,
                            onReorder = model::reorder,
                            hasDraft = pdf != null,
                            reviewAvailable = state.reviewAvailable,
                            onReview = { action, version, note, price ->
                                model.reviewOrder(order.id, action, version, note, price)
                            },
                            onEdit = { model.editCorrection(order) },
                            onCleanup = { model.cleanupPdf(order.id) },
                            onSaveFile = { index ->
                                val name =
                                    (if (index == 0) order.fileName
                                        else order.attachments[index - 1].fileName)
                                        .replace('/', '_')
                                        .replace('\\', '_')
                                downloadOrder = order.id
                                downloadIndex = index
                                fileSaver.launch(
                                    if (name.endsWith(".pdf", true)) name else "$name.pdf"
                                )
                            },
                            printToolsAvailable = state.printToolsAvailable,
                            onPrinted = { index, checked ->
                                model.markPrinted(order.id, index, checked)
                            },
                            onPreviewAttachment = {
                                previewIndex = it
                                preview = order
                            },
                        )
                    else
                        EmptyPanel(
                            "Order unavailable",
                            "It may still be syncing. Return to Orders to refresh your view.",
                            Icons.Default.CloudOff,
                        )
                }
                route == "settings" && session.admin ->
                    ShopSettingsContent(
                        state.settings,
                        busy,
                        retentionAvailable = state.reviewAvailable && !BuildConfig.DEMO,
                        scheduleAvailable = state.paperSizesAvailable,
                        stockAvailable = state.printToolsAvailable,
                        onSave = model::saveSettings,
                    )
                route == "account" ->
                    AccountContent(
                        session,
                        BuildConfig.DEMO,
                        busy,
                        model::demoLogin,
                        { signOut = true },
                        settings = state.settings,
                        onSettings = { model.navigate("settings") },
                    )
                else ->
                    Dashboard(
                        state,
                        BuildConfig.DEMO,
                        route == "home",
                        onNew = { model.navigate("request") },
                        onOrder = model::openOrder,
                        submittedId = submittedId,
                        hasDraft = pdf != null,
                    )
            }
        }
    }
    if (signOut)
        AlertDialog(
            onDismissRequest = { signOut = false },
            title = { Text("Sign out of PrintEase?") },
            text = {
                Text(
                    "Your submitted orders will stay saved. Any unfinished draft on this device will be removed."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        signOut = false
                        model.signOut()
                    }
                ) {
                    Text("Sign out")
                }
            },
            dismissButton = { TextButton(onClick = { signOut = false }) { Text("Stay signed in") } },
        )
    preview?.let { order ->
        PreviewDialog(
            order.attachments.getOrNull(previewIndex - 1)?.fileName ?: order.fileName,
            model.documents,
            load = { model.loadDocument(order, previewIndex) },
            printOptions = order.attachments.getOrNull(previewIndex - 1)?.options ?: order.options,
            onClose = { preview = null },
        )
    }
    if (draftPreview >= 0) {
        val extra = attachments.getOrNull(draftPreview - 1)
        val file = if (draftPreview == 0) pdf else extra?.pdf
        if (file != null)
            PreviewDialog(
                file.name,
                model.documents,
                load = { File(file.path) },
                onClose = { draftPreview = -1 },
                printOptions = extra?.options ?: options,
            )
    }
}

@Composable
fun Brand() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Surface(color = Cream, shape = RoundedCornerShape(12.dp)) {
            Image(
                androidx.compose.ui.res.painterResource(R.drawable.printease_logo),
                null,
                Modifier.size(44.dp),
            )
        }
        Text("PrintEase", fontSize = 23.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp)
    }
}

@Composable
private fun ThemeModeButton(dark: Boolean, onClick: () -> Unit) {
    IconButton(onClick) {
        Icon(
            if (dark) Icons.Default.LightMode else Icons.Default.DarkMode,
            if (dark) "Switch to light mode" else "Switch to dark mode",
        )
    }
}

@Composable
fun ModeBadge() {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.padding(end = 8.dp),
    ) {
        Text(
            "DEMO",
            Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
fun Eyebrow(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        letterSpacing = 1.5.sp,
        fontWeight = FontWeight.Bold,
    )
}

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            content = content,
        )
    }
}

@Composable
fun EmptyPanel(title: String, description: String, icon: ImageVector = Icons.Default.Inbox) {
    Panel(Modifier.fillMaxWidth()) {
        Icon(icon, null, Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Modifier.pressFeedback(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, label = "Button press")
    return this.scale(scale)
}

@Composable
private fun QueueStatusCard(status: QueueStatus) {
    Panel(Modifier.fillMaxWidth()) {
        Text("Shop queue", style = MaterialTheme.typography.titleMedium)
        Text(
            "${status.waiting} waiting · ${status.printing} printing",
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Reported ${formatDate(status.checkedAt)}. Check your request for the shop’s pickup estimate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    colors: ButtonColors = ButtonDefaults.buttonColors(),
) {
    val interaction = remember { MutableInteractionSource() }
    Button(
        onClick,
        modifier.heightIn(min = 52.dp).pressFeedback(interaction),
        interactionSource = interaction,
        enabled = enabled,
        colors = colors,
        shape = RoundedCornerShape(16.dp),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp),
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text)
    }
}

@Composable
fun AuthContent(
    demo: Boolean,
    busy: Boolean,
    error: String?,
    onDemo: (Boolean) -> Unit,
    onAuth: (String, String, String?) -> Unit,
    onReset: (String) -> Unit,
    onGoogle: () -> Unit = {},
    dark: Boolean = isSystemInDarkTheme(),
    onToggleTheme: (() -> Unit)? = null,
) {
    var register by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var email by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = 520.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Brand()
            onToggleTheme?.let { ThemeModeButton(dark, it) }
        }
        Spacer(Modifier.height(16.dp))
        Eyebrow("YOUR CAMPUS. YOUR TIME.")
        Text(
            "Great prints.\nLess waiting.",
            style =
                MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 42.sp,
                    lineHeight = 47.sp,
                    letterSpacing = (-1.5).sp,
                ),
        )
        Text(
            "Submit your PDF, follow the queue, and pick up when it’s ready.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (error != null) Text(error, color = MaterialTheme.colorScheme.error)
        if (demo)
            Panel(Modifier.fillMaxWidth()) {
                Eyebrow("EXPLORE PRINTEASE")
                Text(
                    "A smoother print day starts here.",
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    "Try both sides of the nearby print shop. Demo orders stay on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton(
                    "Continue as customer",
                    { onDemo(false) },
                    Modifier.fillMaxWidth(),
                    icon = Icons.Default.School,
                )
                OutlinedButton(
                    { onDemo(true) },
                    Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Icon(Icons.Default.Storefront, null)
                    Spacer(Modifier.width(8.dp))
                    Text("Explore the shop workspace")
                }
                Text(
                    "Demo mode · No real accounts or uploads to Firebase",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        else
            Panel(Modifier.fillMaxWidth()) {
                Text(
                    if (register) "Create your account" else "Welcome back",
                    style = MaterialTheme.typography.titleLarge,
                )
                OutlinedButton(
                    onClick = onGoogle,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors =
                        ButtonDefaults.outlinedButtonColors(
                            containerColor = Color.White,
                            contentColor = Color(0xFF1F1F1F),
                        ),
                    border = BorderStroke(1.dp, Color(0xFF747775)),
                ) {
                    Image(
                        androidx.compose.ui.res.painterResource(R.drawable.ic_google),
                        null,
                        Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("Continue with Google")
                }
                Text(
                    "Use your Google account. No PrintEase password needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    HorizontalDivider(Modifier.weight(1f))
                    Text("or use email", style = MaterialTheme.typography.bodySmall)
                    HorizontalDivider(Modifier.weight(1f))
                }
                if (register)
                    OutlinedTextField(
                        name,
                        { name = it.take(200) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Full name") },
                        singleLine = true,
                        enabled = !busy,
                    )
                OutlinedTextField(
                    email,
                    { email = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = !busy,
                )
                OutlinedTextField(
                    password,
                    { password = it },
                    Modifier.fillMaxWidth(),
                    label = { Text("Password") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    visualTransformation =
                        if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton({ visible = !visible }) {
                            Icon(
                                if (visible) Icons.Default.VisibilityOff
                                else Icons.Default.Visibility,
                                if (visible) "Hide password" else "Show password",
                            )
                        }
                    },
                    supportingText = { if (register) Text("At least 8 characters") },
                )
                PrimaryButton(
                    if (busy) "Please wait…" else if (register) "Create account" else "Sign in",
                    { onAuth(email, password, if (register) name else null) },
                    Modifier.fillMaxWidth(),
                    !busy,
                )
                if (!register)
                    TextButton({ onReset(email) }, enabled = !busy) { Text("Reset password") }
                TextButton(
                    {
                        register = !register
                        password = ""
                    },
                    enabled = !busy,
                ) {
                    Text(
                        if (register) "Already registered? Sign in"
                        else "New here? Create an account"
                    )
                }
            }
        Text(
            "Print online · Collect near BSU Alangilan",
            Modifier.align(Alignment.CenterHorizontally),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun Dashboard(
    state: ShopState,
    demo: Boolean,
    overview: Boolean,
    onNew: () -> Unit,
    onOrder: (String) -> Unit,
    submittedId: String? = null,
    hasDraft: Boolean = false,
) {
    val session = state.session ?: return
    var filter by rememberSaveable { mutableStateOf("All") }
    var search by rememberSaveable { mutableStateOf("") }
    val source =
        if (session.admin) sortedQueue(state.orders)
        else state.orders.sortedByDescending { it.createdAt }
    val filtered =
        source.filter { o ->
            (when (filter) {
                "Active" -> o.status.active
                "Ready" -> o.status == OrderStatus.Ready
                "History" -> !o.status.active
                "Pending" -> o.status == OrderStatus.Pending
                else -> true
            }) &&
                (search.isBlank() ||
                    o.fileName.contains(search, true) ||
                    o.studentName.contains(search, true) ||
                    o.queue?.toString() == search.trim())
        }
    LazyColumn(
        Modifier.widthIn(max = 900.dp).fillMaxSize(),
        contentPadding = PaddingValues(22.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (!session.admin && !overview && submittedId != null)
            item {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Request sent", style = MaterialTheme.typography.titleLarge)
                    Text("Request ID $submittedId", style = MaterialTheme.typography.bodySmall)
                    Text(
                        "Follow this request in Orders. The shop assigns a queue number after review."
                    )
                    if (state.orders.any { it.id == submittedId })
                        TextButton({ onOrder(submittedId) }) { Text("View request") }
                }
            }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(if (session.admin) "NEARBY PRINT SHOP" else "PRINTING NEAR BSU ALANGILAN")
                Text(
                    if (session.admin) "Print queue"
                    else
                        greetingName(session.name).let {
                            if (it.isBlank()) "Hello!" else "Hello, $it."
                        },
                    style = MaterialTheme.typography.headlineLarge,
                )
                if (!overview)
                    Text(
                        if (session.admin) "Review and update incoming requests."
                        else "Track your requests from submission to pickup.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
        if (overview) {
            if (!session.admin)
                item {
                    Box(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(26.dp))
                            .background(
                                Brush.linearGradient(listOf(Color(0xFF123D32), Color(0xFF195440)))
                            )
                    ) {
                        Icon(
                            Icons.Default.Description,
                            null,
                            Modifier.align(Alignment.TopEnd).padding(22.dp).size(76.dp),
                            tint = Color.White.copy(alpha = .08f),
                        )
                        Column(
                            Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Surface(
                                color = Color.White.copy(alpha = .14f),
                                contentColor = Lime,
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    null,
                                    Modifier.padding(11.dp).size(22.dp),
                                )
                            }
                            Text(
                                "Send files to print",
                                color = Color.White,
                                style = MaterialTheme.typography.headlineMedium,
                            )
                            Text(
                                "Upload a PDF, JPG, or PNG and collect it at the shop near campus.",
                                color = Color(0xFFC6DBD1),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            PrimaryButton(
                                text =
                                    when {
                                        !state.settings.acceptingOrders -> "Requests paused"
                                        hasDraft -> "Continue your draft"
                                        else -> "New print request"
                                    },
                                onClick = onNew,
                                enabled =
                                    state.settings.acceptingOrders && state.settingsError == null,
                                icon = Icons.Default.Add,
                                colors =
                                    ButtonDefaults.buttonColors(
                                        containerColor = Lime,
                                        contentColor = Ink,
                                        disabledContainerColor = Color.White.copy(alpha = .14f),
                                        disabledContentColor = Color(0xFFC6DBD1),
                                    ),
                            )
                        }
                    }
                }
            item {
                ShopCard(state.settings, Modifier.fillMaxWidth(), stackActions = !session.admin)
            }
            if (!session.admin)
                state.queueStatus?.let { status -> item { QueueStatusCard(status) } }
            if (!session.admin) {
                val needsResponse =
                    source.filter {
                        it.status == OrderStatus.Pending &&
                            it.review?.stage in listOf(ReviewStage.Quoted, ReviewStage.Correction)
                    }
                if (needsResponse.isNotEmpty())
                    item {
                        Panel(Modifier.fillMaxWidth()) {
                            Text(
                                "Requests that need you",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            needsResponse.take(4).forEach { order ->
                                OutlinedButton(
                                    { onOrder(order.id) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column {
                                        Text(
                                            order.fileName,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        Text(
                                            order.reviewStatus,
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    }
                                }
                            }
                        }
                    }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatCard(
                        if (session.admin) "To review" else "In progress",
                        state.orders.count {
                            if (session.admin) it.status == OrderStatus.Pending
                            else it.status.active && it.status != OrderStatus.Ready
                        },
                        Icons.Default.Layers,
                        Modifier.weight(1f),
                    )
                    StatCard(
                        "Ready to collect",
                        state.orders.count { it.status == OrderStatus.Ready },
                        Icons.Default.TaskAlt,
                        Modifier.weight(1f),
                    )
                }
            }
        }
        if (state.cached || state.syncError != null || state.settingsError != null)
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Row(
                        Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.CloudOff, null, Modifier.size(20.dp))
                        Text(
                            state.syncError
                                ?: state.settingsError
                                ?: "Showing saved orders. New updates will appear when connected.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (session.admin) "Print queue" else "Your orders",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                if (!session.admin && !overview)
                    IconButton(onNew) { Icon(Icons.Default.AddCircleOutline, "New print request") }
            }
        }
        item {
            OutlinedTextField(
                search,
                { search = it },
                Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        if (session.admin) "Search document, customer or queue"
                        else "Search your documents"
                    )
                },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (search.isNotEmpty())
                        IconButton({ search = "" }) { Icon(Icons.Default.Close, "Clear search") }
                },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
            )
        }
        item {
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                (if (session.admin) listOf("All", "Pending", "Active", "Ready", "History")
                    else listOf("All", "Active", "Ready", "History"))
                    .forEach { value ->
                        FilterChip(
                            selected = value == filter,
                            onClick = { filter = value },
                            label = { Text(value) },
                        )
                    }
            }
        }
        if (filtered.isEmpty())
            item {
                EmptyPanel(
                    if (state.orders.isEmpty()) "Your queue starts here."
                    else "No matching requests",
                    if (state.orders.isEmpty())
                        "Submit your first PDF and its progress will appear here."
                    else "Try another search or choose a different filter.",
                )
            }
        if (session.admin && filter == "All") {
            val grouped = filtered.groupBy { it.queueGroup }
            ShopQueueGroup.entries.forEach { group ->
                val orders = grouped[group].orEmpty()
                if (orders.isNotEmpty()) {
                    item(key = "heading-${group.name}") {
                        Row(
                            Modifier.fillMaxWidth().padding(top = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Text(group.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                orders.size.toString(),
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    items(orders, key = { it.id }) { order ->
                        OrderCard(order, true, { onOrder(order.id) })
                    }
                }
            }
        } else {
            items(filtered, key = { it.id }) { order ->
                OrderCard(order, session.admin, { onOrder(order.id) })
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Default.Info,
                    null,
                    Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (demo) "Demo workspace · Orders are saved only on this device."
                    else "Pay at the shop when you collect your prints.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun StatCard(title: String, count: Int, icon: ImageVector, modifier: Modifier) {
    Panel(modifier) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Text("$count", style = MaterialTheme.typography.headlineLarge)
        Text(
            title,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun StatusBadge(status: OrderStatus) {
    val color =
        when (status) {
            OrderStatus.Ready,
            OrderStatus.Completed -> MaterialTheme.colorScheme.primary
            OrderStatus.Rejected,
            OrderStatus.Cancelled -> MaterialTheme.colorScheme.error
            OrderStatus.Printing -> Color(0xFF3B63A3)
            else -> Color(0xFF91651D)
        }
    Surface(color = color.copy(alpha = .1f), shape = RoundedCornerShape(8.dp)) {
        Text(
            status.label,
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            color = color,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
fun OrderCard(order: PrintOrder, admin: Boolean, onClick: () -> Unit) {
    Surface(
        onClick,
        shape = RoundedCornerShape(20.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(
                        Icons.Default.PictureAsPdf,
                        null,
                        Modifier.padding(12.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        order.fileName,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        if (admin) order.studentName
                        else
                            "${order.options.pages} pages · ${order.options.copies} copies · ${paperLabel(order.options.paper)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(
                    Icons.Default.ChevronRight,
                    null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                if (order.reviewStatus != order.status.label) {
                    Text(
                        order.reviewStatus,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                } else StatusBadge(order.status)
                Text(
                    order.review?.quote?.let(::pesos)
                        ?: if (
                            needsPaperQuote(order.options.paper) || order.attachments.isNotEmpty()
                        )
                            "Shop quote"
                        else pesos(order.total),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Text(
                if (order.queue != null)
                    "Queue #${order.queue.toString().padStart(3, '0')} · ${order.queueDay}"
                else "Submitted ${formatDate(order.createdAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun RequestContent(
    pdf: PdfFile?,
    options: PrintOptions,
    busy: Boolean,
    progress: Float,
    onPick: (android.net.Uri) -> Unit,
    onOptions: (PrintOptions) -> Unit,
    onSubmit: () -> Unit,
    onPreview: () -> Unit,
    onDiscard: () -> Unit,
    settings: ShopSettings = ShopSettings(),
    settingsError: String? = null,
    queueStatus: QueueStatus? = null,
    reviewAvailable: Boolean = false,
    paperSizesAvailable: Boolean = false,
    printToolsAvailable: Boolean = false,
    onPreviewAttachment: (Int) -> Unit = {},
    onResizeImage: (Int, Int) -> Unit = { _, _ -> },
    requestedAddons: List<String> = emptyList(),
    revisionNote: String? = null,
    onAddon: (String) -> Unit = {},
    attachments: List<AttachmentDraft> = emptyList(),
    onPickFiles: (List<android.net.Uri>) -> Unit = {},
    onAddFiles: (List<android.net.Uri>) -> Unit = {},
    onChangeAttachment: (Int, AttachmentDraft) -> Unit = { _, _ -> },
    onRemoveAttachment: (Int) -> Unit = {},
) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(onPick)
        }
    val fileTypes = arrayOf("application/pdf", "image/jpeg", "image/png")
    val batchPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            if (it.isNotEmpty()) onPickFiles(it)
        }
    val moreFilesPicker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
            if (it.isNotEmpty()) onAddFiles(it)
        }
    var review by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var copiesText by rememberSaveable(options.copies) { mutableStateOf(options.copies.toString()) }
    val validCopies = copiesText.toIntOrNull() in 1..100
    var rangesText by
        rememberSaveable(pdf?.path) { mutableStateOf(pageRangeLabel(options.pageRanges)) }
    val parsedRanges = runCatching { parsePageRanges(rangesText, options.pages) }
    val validRanges = parsedRanges.isSuccess
    val custom = customPaperDimensions(options.paper)
    var paperWidth by rememberSaveable { mutableStateOf(custom?.first?.toString().orEmpty()) }
    var paperHeight by rememberSaveable { mutableStateOf(custom?.second?.toString().orEmpty()) }
    var choosingPaper by remember { mutableStateOf(false) }
    val validDimensions = custom == null || customPaper(paperWidth, paperHeight) == options.paper
    val quoteOnly = needsPaperQuote(options.paper) || attachments.isNotEmpty()
    val paperOut =
        settings.paperStock.status(options.paper) == "out" ||
            attachments.any { settings.paperStock.status(it.options.paper) == "out" }
    val canSubmit =
        pdf != null &&
            validCopies &&
            validRanges &&
            validDimensions &&
            !paperOut &&
            options.batchRangesValid(attachments) &&
            attachments.all { it.validCopies } &&
            (!quoteOnly || paperSizesAvailable) &&
            !busy &&
            (settings.acceptingOrders || revisionNote != null) &&
            settingsError == null
    var reviewedSettings by remember { mutableStateOf(settings) }
    Column(Modifier.widthIn(max = 700.dp).fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            queueStatus?.let { QueueStatusCard(it) }
            settings.pickupSchedule
                ?.let { schedule -> nextPickupWindow(schedule) }
                ?.let { next ->
                    Panel(Modifier.fillMaxWidth()) {
                        Text("Next pickup window", style = MaterialTheme.typography.titleMedium)
                        Text(next.range)
                        next.cutoff?.let {
                            Text(
                                "Same-day request cutoff $it",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(
                            "Philippine time. The shop confirms when your order is ready.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            if (revisionNote != null)
                Panel(Modifier.fillMaxWidth()) {
                    Text("Correction requested", style = MaterialTheme.typography.titleMedium)
                    Text(revisionNote)
                }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Print files", style = MaterialTheme.typography.titleMedium)
                Panel(Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Default.UploadFile,
                        null,
                        Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        pdf?.name ?: "Add a document or image",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (pdf == null) "PDF, JPG, or PNG · Up to 5 MB per file"
                        else "${pdf.pages} pages · ${(pdf.bytes + 1023) / 1024} KB · Saved draft",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        {
                            if (pdf == null && paperSizesAvailable) batchPicker.launch(fileTypes)
                            else picker.launch(fileTypes)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.AttachFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (pdf != null) "Replace first file"
                            else if (paperSizesAvailable) "Choose files" else "Choose a file"
                        )
                    }
                    if (paperSizesAvailable && pdf != null && attachments.size < MAX_ATTACHMENTS)
                        OutlinedButton(
                            { moreFilesPicker.launch(fileTypes) },
                            Modifier.fillMaxWidth().heightIn(min = 48.dp),
                            enabled = !busy,
                        ) {
                            Text("Add files")
                        }
                    if (paperSizesAvailable)
                        Text(
                            if (pdf == null) "Up to three files per request."
                            else "${attachments.size + 1} of ${MAX_ATTACHMENTS + 1} files selected",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (pdf == null)
                        Text(
                            "Export Word, PowerPoint, or Excel as PDF before uploading.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Print options", style = MaterialTheme.typography.titleMedium)
                Panel(Modifier.fillMaxWidth()) {
                    pdf?.let { FileOptionsHeading(it.name, 1, attachments.size + 1) }
                    OutlinedTextField(
                        rangesText,
                        { value ->
                            rangesText = value.take(120)
                            runCatching { parsePageRanges(rangesText, options.pages) }
                                .onSuccess { onOptions(options.copy(pageRanges = it)) }
                        },
                        label = { Text("Pages to print") },
                        placeholder = { Text("All pages") },
                        supportingText = {
                            Text(
                                when {
                                    !validRanges ->
                                        parsedRanges.exceptionOrNull()?.message.orEmpty()
                                    !options.batchRangesValid(attachments) ->
                                        "Multi-file requests print all pages. Clear the ranges or submit this file separately."
                                    attachments.isNotEmpty() ->
                                        "Multi-file requests print all pages."
                                    else -> "Leave blank for all pages, or enter 1-3, 5, 8-10"
                                }
                            )
                        },
                        isError = !validRanges || !options.batchRangesValid(attachments),
                        enabled = !busy && pdf != null,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text("Paper size", fontWeight = FontWeight.SemiBold)
                    Box {
                        OutlinedButton(
                            { choosingPaper = true },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(paperLabel(options.paper), Modifier.weight(1f))
                            Icon(Icons.Default.ExpandMore, null)
                        }
                        DropdownMenu(
                            expanded = choosingPaper,
                            onDismissRequest = { choosingPaper = false },
                        ) {
                            val sizes =
                                standardPaperSizes +
                                    if (paperSizesAvailable) quotedPaperSizes + "Custom"
                                    else emptyList()
                            sizes.forEach { size ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            settings.paperStock.menuLabel(size, printToolsAvailable)
                                        )
                                    },
                                    enabled = settings.paperStock.status(size) != "out",
                                    onClick = {
                                        choosingPaper = false
                                        if (size == "Custom") {
                                            paperWidth = ""
                                            paperHeight = ""
                                            onOptions(options.copy(paper = "Custom 210x297 mm"))
                                        } else onOptions(options.copy(paper = size))
                                    },
                                )
                            }
                        }
                    }
                    if (custom != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                paperWidth,
                                {
                                    paperWidth = it.filter(Char::isDigit).take(4)
                                    customPaper(paperWidth, paperHeight)?.let { size ->
                                        onOptions(options.copy(paper = size))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("Width (mm)") },
                                isError = !validDimensions,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !busy,
                            )
                            OutlinedTextField(
                                paperHeight,
                                {
                                    paperHeight = it.filter(Char::isDigit).take(4)
                                    customPaper(paperWidth, paperHeight)?.let { size ->
                                        onOptions(options.copy(paper = size))
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                label = { Text("Height (mm)") },
                                isError = !validDimensions,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Number),
                                singleLine = true,
                                enabled = !busy,
                            )
                        }
                        Text(
                            "Enter whole millimeters from 50 to 1,000.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (quoteOnly)
                        Text(
                            "The shop checks stock and confirms the final price.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    if (printToolsAvailable) {
                        val stock = settings.paperStock.status(options.paper)
                        Text(
                            if (stock == "out") "Out of stock. Choose another paper size."
                            else stockLabels.getValue(stock),
                            color =
                                if (stock == "out") MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        PreferenceSwitch(
                            "Actual size",
                            "Off for Fit to paper",
                            options.actualSize,
                            !busy,
                        ) {
                            onOptions(options.copy(actualSize = it))
                        }
                    }
                    if (pdf != null) {
                        ImageSizeEditor(pdf, busy) { onResizeImage(0, it) }
                        OutlinedButton(
                            onPreview,
                            enabled = !busy && validDimensions,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.Visibility, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Preview print layout")
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        copiesText,
                        { value ->
                            copiesText = value.filter(Char::isDigit).take(3)
                            value
                                .toIntOrNull()
                                ?.takeIf { it in 1..100 }
                                ?.let { onOptions(options.copy(copies = it)) }
                        },
                        label = { Text("Copies") },
                        supportingText = {
                            Text(
                                if (validCopies) "Choose 1–100 copies"
                                else "Enter a number from 1 to 100"
                            )
                        },
                        isError = !validCopies,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    HorizontalDivider()
                    PreferenceSwitch(
                        "Print in color",
                        "Off for black & white",
                        options.color,
                        !busy,
                    ) {
                        onOptions(options.copy(color = it))
                    }
                    PreferenceSwitch(
                        "Double-sided",
                        "Use both sides to save paper",
                        options.duplex,
                        !busy,
                    ) {
                        onOptions(options.copy(duplex = it))
                    }
                }
                attachments.forEachIndexed { index, item ->
                    key(item.pdf.path) {
                        Panel(Modifier.fillMaxWidth()) {
                            AttachmentEditor(
                                item,
                                busy,
                                { onChangeAttachment(index, it) },
                                { onRemoveAttachment(index) },
                                position = index + 2,
                                count = attachments.size + 1,
                                stock = settings.paperStock,
                                printToolsAvailable = printToolsAvailable,
                                onPreview = { onPreviewAttachment(index) },
                                onResize = { onResizeImage(index + 1, it) },
                            )
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Print summary", style = MaterialTheme.typography.titleMedium)
                Panel(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Estimated total", Modifier.weight(1f))
                        Text(
                            if (pdf == null || !validCopies || !validRanges || paperOut) "—"
                            else if (quoteOnly) "Shop quote"
                            else pesos(options.total(settings.rates)),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (pdf != null && !quoteOnly)
                        Text(
                            "${options.selectedPages} selected pages × ${options.copies} copies × ${pesos(options.rate(settings.rates))} per printed side\n${options.sheets} sheets · Payment at pickup",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    Text(
                        if (settings.ratesConfirmed)
                            "Shop-confirmed rates. Duplex saves sheets and uses the same per-side rate."
                        else
                            "Sample prices. Confirm the actual printing price with the shop before pickup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (reviewAvailable && revisionNote == null) {
                Panel(Modifier.fillMaxWidth()) {
                    Text("Optional add-ons", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Ask the shop to include these in your final quote.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    PrintAddon.entries.forEach { addon ->
                        PreferenceSwitch(
                            addon.label,
                            "Price confirmed by the shop",
                            addon.key in requestedAddons,
                            !busy,
                        ) {
                            onAddon(addon.key)
                        }
                    }
                }
            }
            if (pdf != null)
                TextButton({ discard = true }, enabled = !busy) {
                    Text("Discard this draft", color = MaterialTheme.colorScheme.error)
                }
        }
        Surface(shadowElevation = 6.dp) {
            Column(
                Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (busy) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val files = listOfNotNull(pdf) + attachments.map { it.pdf }
                    val stage = progress * files.size
                    val index = stage.toInt()
                    Text(
                        when {
                            progress >= 1f -> "Saving request…"
                            files.isNotEmpty() && progress > 0f ->
                                "File ${index + 1} of ${files.size} · ${files[index].name} · ${((stage - index) / .97f * 100).toInt().coerceAtMost(100)}%"
                            else -> "Preparing files…"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if ((!settings.acceptingOrders && revisionNote == null) || settingsError != null)
                    Text(
                        settingsError ?: "The shop has paused new requests. Your draft is saved.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                PrimaryButton(
                    if (busy) "Please wait…" else "Review request",
                    {
                        reviewedSettings = settings
                        review = true
                    },
                    Modifier.fillMaxWidth(),
                    canSubmit,
                    Icons.AutoMirrored.Filled.ArrowForward,
                )
            }
        }
    }
    if (review && pdf != null)
        AlertDialog(
            onDismissRequest = { review = false },
            title = {
                Text(if (revisionNote != null) "Send the corrected request?" else "Ready to send?")
            },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val files =
                        listOf(pdf.name to options) + attachments.map { it.pdf.name to it.options }
                    files.forEachIndexed { index, (name, details) ->
                        if (index > 0) HorizontalDivider()
                        FileOptionsHeading(name, index + 1, files.size)
                        Text(
                            "${details.pageLabel} · ${paperLabel(details.paper)} · ${details.copies} copies\n" +
                                "${if (details.color) "Color" else "Black & white"} · " +
                                "${if (details.duplex) "Double-sided" else "Single-sided"} · ${details.scaleLabel}"
                        )
                    }
                    Text(
                        if (quoteOnly) "Final price confirmed by the shop"
                        else "Estimated total: ${pesos(options.total(reviewedSettings.rates))}",
                        fontWeight = FontWeight.Bold,
                    )
                    if (requestedAddons.isNotEmpty()) {
                        val labels =
                            PrintAddon.entries
                                .filter { it.key in requestedAddons }
                                .joinToString(", ") { it.label }
                        Text("Requested add-ons · $labels")
                    }
                    Text(
                        "Pickup at ${settings.name}. The shop will review your request and assign a queue number. Pay when you collect."
                    )
                }
            },
            confirmButton = {
                TextButton({
                    review = false
                    if (reviewedSettings.version == settings.version && canSubmit) onSubmit()
                }) {
                    Text(
                        if (reviewedSettings.version == settings.version && canSubmit) {
                            if (revisionNote != null) "Send correction" else "Submit request"
                        } else "Review updated details"
                    )
                }
            },
            dismissButton = { TextButton({ review = false }) { Text("Keep editing") } },
        )
    if (discard)
        AlertDialog(
            onDismissRequest = { discard = false },
            title = { Text("Discard draft?") },
            text = { Text("The selected PDF and print options will be removed from this device.") },
            confirmButton = {
                TextButton({
                    discard = false
                    onDiscard()
                }) {
                    Text("Discard")
                }
            },
            dismissButton = { TextButton({ discard = false }) { Text("Keep draft") } },
        )
}

@Composable
fun PreferenceSwitch(
    title: String,
    subtitle: String,
    value: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(value, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(value, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
fun OrderDetail(
    order: PrintOrder,
    admin: Boolean,
    busy: Boolean,
    cached: Boolean,
    onTransition: (String, OrderStatus, String) -> Unit,
    onPreview: () -> Unit,
    settings: ShopSettings = ShopSettings(),
    onEstimate: (String, Long?) -> Unit = { _, _ -> },
    onReorder: (PrintOrder) -> Unit = {},
    hasDraft: Boolean = false,
    reviewAvailable: Boolean = false,
    onReview: (ReviewAction, Long, String, Long?) -> Unit = { _, _, _, _ -> },
    onEdit: () -> Unit = {},
    onCleanup: () -> Unit = {},
    onPreviewAttachment: (Int) -> Unit = {},
    printToolsAvailable: Boolean = false,
    onPrinted: (Int, Boolean) -> Unit = { _, _ -> },
    onSaveFile: (Int) -> Unit = {},
) {
    val checklistActive = admin && printToolsAvailable && order.status == OrderStatus.Printing
    val checkedAll = order.printedFiles.size == order.attachments.size + 1
    var replaceForCorrection by rememberSaveable(order.id) { mutableStateOf(false) }
    var removePdf by rememberSaveable(order.id) { mutableStateOf(false) }
    var action by remember { mutableStateOf<OrderStatus?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable(order.id) { mutableStateOf("") }
    var estimateDialog by rememberSaveable(order.id) { mutableStateOf(false) }
    var repeatDialog by rememberSaveable(order.id) { mutableStateOf(false) }
    Column(
        Modifier.widthIn(max = 700.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        ReviewPanel(order, admin, reviewAvailable, !busy && !cached, onReview) {
            if (hasDraft) replaceForCorrection = true else onEdit()
        }
        Panel(Modifier.fillMaxWidth()) {
            StatusBadge(order.status)
            Text(
                if (order.queue == null)
                    when (order.status) {
                        OrderStatus.Cancelled -> "Request cancelled"
                        OrderStatus.Rejected -> "Request not accepted"
                        else -> "We have your request."
                    }
                else "Queue #${order.queue.toString().padStart(3, '0')}",
                style = MaterialTheme.typography.headlineLarge,
            )
            Text(
                if (order.queueDay != null) "${order.queueDay} · Pickup near campus"
                else "Submitted ${formatDate(order.createdAt)}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (order.status == OrderStatus.Ready)
                Text(
                    "Your prints are ready. Collect them and pay at the shop.",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            if (order.note.isNotBlank())
                Text("Shop note: ${order.note}", color = MaterialTheme.colorScheme.error)
        }
        PickupSummary(order, admin, busy || cached) { estimateDialog = true }
        if (order.status !in listOf(OrderStatus.Rejected, OrderStatus.Cancelled))
            Panel(Modifier.fillMaxWidth()) {
                Text("From upload to pickup", style = MaterialTheme.typography.titleLarge)
                listOf(
                        OrderStatus.Pending,
                        OrderStatus.Accepted,
                        OrderStatus.Printing,
                        OrderStatus.Ready,
                        OrderStatus.Completed,
                    )
                    .forEach { status ->
                        val passed = order.status.ordinal >= status.ordinal
                        Row(
                            Modifier.padding(vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                if (passed) Icons.Default.CheckCircle
                                else Icons.Default.RadioButtonUnchecked,
                                null,
                                tint =
                                    if (passed) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outlineVariant,
                            )
                            Text(
                                status.label,
                                fontWeight =
                                    if (status == order.status) FontWeight.Bold
                                    else FontWeight.Normal,
                                color =
                                    if (passed) MaterialTheme.colorScheme.onSurface
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
            }
        Panel(Modifier.fillMaxWidth()) {
            Text(order.fileName, style = MaterialTheme.typography.titleLarge)
            Text(order.studentName, color = MaterialTheme.colorScheme.onSurfaceVariant)
            HorizontalDivider()
            DetailLine("Paper size", paperLabel(order.options.paper))
            DetailLine("Pages / copies", "${order.options.pages} / ${order.options.copies}")
            DetailLine("Print mode", if (order.options.color) "Color" else "Black & white")
            DetailLine("Sides", if (order.options.duplex) "Double-sided" else "Single-sided")
            DetailLine("Page sizing", order.options.scaleLabel)
            DetailLine("Sheets", "${order.options.sheets}")
            HorizontalDivider()
            DetailLine(
                "Estimated total",
                if (needsPaperQuote(order.options.paper) || order.attachments.isNotEmpty())
                    "Shop quote"
                else pesos(order.total),
            )
            DetailLine("Pages selected", order.options.pageLabel)
            DetailLine("Updated", formatDate(order.updatedAt))
            if (order.documentDeletedAt != null)
                Text(
                    "The PDF has been removed under the shop’s retention policy. Your order history is saved.",
                    style = MaterialTheme.typography.bodySmall,
                )
            OutlinedButton(
                onPreview,
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !busy && order.documentDeletedAt == null,
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Spacer(Modifier.width(8.dp))
                Text("View PDF")
            }
            if (admin && order.documentDeletedAt == null)
                TextButton({ onSaveFile(0) }, enabled = !busy) { Text("Save PDF") }
            if (checklistActive) {
                Text(
                    "Print checklist · ${order.printedFiles.size} of ${order.attachments.size + 1} printed",
                    style = MaterialTheme.typography.titleMedium,
                )
                PrintChecklistToggle(order.fileName, 0 in order.printedFiles, !busy && !cached) {
                    onPrinted(0, it)
                }
            }
            order.attachments.forEachIndexed { index, item ->
                HorizontalDivider()
                Text(item.fileName, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${paperLabel(item.options.paper)} · ${item.options.pages} pages · ${item.options.copies} copies"
                )
                Text(
                    (if (item.options.color) "Color" else "Black & white") +
                        " · " +
                        (if (item.options.duplex) "Double-sided" else "Single-sided") +
                        " · " +
                        item.options.scaleLabel,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (order.documentDeletedAt == null)
                    OutlinedButton(
                        { onPreviewAttachment(index + 1) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("View PDF")
                    }
                if (admin && order.documentDeletedAt == null)
                    TextButton({ onSaveFile(index + 1) }, enabled = !busy) { Text("Save PDF") }
                if (checklistActive)
                    PrintChecklistToggle(
                        item.fileName,
                        index + 1 in order.printedFiles,
                        !busy && !cached,
                    ) {
                        onPrinted(index + 1, it)
                    }
            }
            if (admin && reviewAvailable && canRemovePdf(order, settings)) {
                OutlinedButton(
                    { removePdf = true },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !cached,
                ) {
                    Text("Remove expired PDF")
                }
            }
            if (!admin && !order.status.active && order.documentDeletedAt == null) {
                PrimaryButton(
                    "Print this again",
                    { if (hasDraft) repeatDialog = true else onReorder(order) },
                    Modifier.fillMaxWidth(),
                    !busy && !cached,
                )
            }
            Text(
                "Request ID: ${order.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        ShopCard(settings, Modifier.fillMaxWidth())
        if (cached)
            Text(
                "You’re viewing cached details. Connect to the internet before changing this order.",
                style = MaterialTheme.typography.bodySmall,
            )
        if (admin) {
            if (action == OrderStatus.Completed)
                Panel(Modifier.fillMaxWidth()) {
                    Text("Verify collection", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Ask the customer for the six-character code shown on their request before handing over the prints."
                    )
                    OutlinedTextField(
                        code,
                        { code = it.take(12) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Collection code") },
                        singleLine = true,
                        enabled = !busy,
                    )
                    PrimaryButton(
                        "Confirm collection",
                        {
                            action = null
                            onTransition(order.id, OrderStatus.Completed, code)
                            code = ""
                        },
                        Modifier.fillMaxWidth(),
                        !busy &&
                            !cached &&
                            code
                                .replace(Regex("[^A-Za-z0-9]"), "")
                                .uppercase(java.util.Locale.ROOT) == collectionCode(order.id),
                    )
                    TextButton(
                        {
                            action = null
                            code = ""
                        },
                        enabled = !busy,
                    ) {
                        Text("Keep ready for pickup")
                    }
                }
            else if (action == OrderStatus.Rejected)
                Panel(Modifier.fillMaxWidth()) {
                    Text("Why reject this request?", style = MaterialTheme.typography.titleLarge)
                    Text("Tell the customer what to change before submitting again.")
                    OutlinedTextField(
                        reason,
                        { reason = it.take(500) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Reason shown to the customer") },
                        minLines = 3,
                        maxLines = 5,
                        supportingText = { Text("${reason.length}/500") },
                        enabled = !busy,
                    )
                    PrimaryButton(
                        "Confirm rejection",
                        {
                            action = null
                            onTransition(order.id, OrderStatus.Rejected, reason.trim())
                            reason = ""
                        },
                        Modifier.fillMaxWidth(),
                        reason.isNotBlank() && !busy && !cached,
                    )
                    TextButton(
                        {
                            action = null
                            reason = ""
                        },
                        enabled = !busy,
                    ) {
                        Text("Keep request")
                    }
                }
            else {
                if (order.status == OrderStatus.Pending)
                    Text(
                        "Review the PDF, page count, and preferences before accepting.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                if (checklistActive && !checkedAll)
                    Text(
                        "Mark every file printed before marking the request ready.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                OrderStatus.entries
                    .filter {
                        order.status.canMoveTo(it) &&
                            it != OrderStatus.Cancelled &&
                            (it != OrderStatus.Accepted || order.canAccept)
                    }
                    .forEach { next ->
                        PrimaryButton(
                            when (next) {
                                OrderStatus.Accepted -> "Accept & assign queue number"
                                OrderStatus.Rejected -> "Reject request"
                                OrderStatus.Completed -> "Verify collection"
                                else -> "Mark as ${next.label}"
                            },
                            { action = next },
                            Modifier.fillMaxWidth(),
                            !busy &&
                                !cached &&
                                (next != OrderStatus.Ready || !checklistActive || checkedAll),
                        )
                    }
            }
        } else if (order.status == OrderStatus.Pending)
            OutlinedButton(
                { action = OrderStatus.Cancelled },
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !busy && !cached,
            ) {
                Text("Cancel request")
            }
        Text(
            "Payment is collected at the shop. A queue number identifies your request; it is not an exact wait-time estimate.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    action
        ?.takeIf { it != OrderStatus.Rejected && it != OrderStatus.Completed }
        ?.let { next ->
            AlertDialog(
                onDismissRequest = { action = null },
                title = {
                    Text(
                        when (next) {
                            OrderStatus.Rejected -> "Why reject this request?"
                            OrderStatus.Cancelled -> "Cancel this request?"
                            else -> "Update to ${next.label}?"
                        }
                    )
                },
                text = {
                    Text(
                        if (next == OrderStatus.Cancelled)
                            "The shop will no longer process this document. You can submit a new request later."
                        else "Make sure the document is ready for the next step before confirming."
                    )
                },
                confirmButton = {
                    TextButton(
                        {
                            action = null
                            onTransition(
                                order.id,
                                next,
                                when (next) {
                                    OrderStatus.Rejected -> reason.trim()
                                    OrderStatus.Completed -> code
                                    else -> ""
                                },
                            )
                            reason = ""
                        },
                        enabled =
                            !busy &&
                                (next != OrderStatus.Rejected || reason.isNotBlank()) &&
                                (next != OrderStatus.Completed ||
                                    code
                                        .replace(Regex("[^A-Za-z0-9]"), "")
                                        .uppercase(java.util.Locale.ROOT) ==
                                        collectionCode(order.id)),
                    ) {
                        Text("Confirm")
                    }
                },
                dismissButton = { TextButton({ action = null }) { Text("Go back") } },
            )
        }
    if (estimateDialog)
        PickupTimeDialog(
            order,
            { time ->
                estimateDialog = false
                onEstimate(order.id, time)
            },
            { estimateDialog = false },
        )
    if (repeatDialog)
        AlertDialog(
            onDismissRequest = { repeatDialog = false },
            title = { Text("Replace the unfinished draft?") },
            text = {
                Text(
                    "The repeat draft uses this previous PDF and print options. You will review current prices before sending."
                )
            },
            confirmButton = {
                TextButton({
                    repeatDialog = false
                    onReorder(order)
                }) {
                    Text("Prepare repeat order")
                }
            },
            dismissButton = { TextButton({ repeatDialog = false }) { Text("Keep draft") } },
        )
    if (replaceForCorrection)
        AlertDialog(
            onDismissRequest = { replaceForCorrection = false },
            title = { Text("Edit this request?") },
            text = {
                Text(
                    "The correction will use your draft slot. A draft for a different request will be replaced."
                )
            },
            confirmButton = {
                TextButton({
                    replaceForCorrection = false
                    onEdit()
                }) {
                    Text("Edit request")
                }
            },
            dismissButton = { TextButton({ replaceForCorrection = false }) { Text("Keep draft") } },
        )
    if (removePdf)
        AlertDialog(
            onDismissRequest = { removePdf = false },
            title = { Text("Remove the stored PDF?") },
            text = {
                Text(
                    "This removes the PDF and earlier versions for this request. The order history stays available."
                )
            },
            confirmButton = {
                TextButton({
                    removePdf = false
                    onCleanup()
                }) {
                    Text("Remove PDF")
                }
            },
            dismissButton = { TextButton({ removePdf = false }) { Text("Keep PDF") } },
        )
}

@Composable
fun DetailLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun AccountContent(
    session: Session,
    demo: Boolean,
    busy: Boolean,
    onSwitch: (Boolean) -> Unit,
    onSignOut: () -> Unit,
    settings: ShopSettings = ShopSettings(),
    onSettings: () -> Unit = {},
) {
    Column(
        Modifier.widthIn(max = 700.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Eyebrow("YOUR WORKSPACE")
        Text("Account & print guide", style = MaterialTheme.typography.headlineLarge)
        Panel(Modifier.fillMaxWidth()) {
            UserAvatar(session, size = 88.dp)
            Text(session.name, style = MaterialTheme.typography.titleLarge)
            Text(session.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (session.admin) Text("Shop administrator", fontWeight = FontWeight.SemiBold)
        }
        ShopCard(settings, Modifier.fillMaxWidth(), if (session.admin) onSettings else null)
        Panel(Modifier.fillMaxWidth()) {
            Text(
                if (settings.ratesConfirmed) "Shop-confirmed rates" else "Print estimates",
                style = MaterialTheme.typography.titleLarge,
            )
            if (!settings.ratesConfirmed)
                Text(
                    "Sample estimates. Confirm the actual printing price with the shop.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            DetailLine("A4 / Letter · B&W", "${pesos(settings.rates.bw)} / side")
            DetailLine("A4 / Letter · Color", "${pesos(settings.rates.color)} / side")
            DetailLine("Legal paper", "+${pesos(settings.rates.legalExtra)} / side")
            Text(
                "Selected pages × copies × rate per printed side. Duplex saves sheets. All selected pages use the same color mode. Your submitted quote stays saved if rates change.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (session.admin)
                OutlinedButton(onSettings, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text("Manage shop details & rates")
                }
        }
        if (!demo && !session.admin) PickupAlertsCard()
        if (demo)
            Panel(Modifier.fillMaxWidth()) {
                Text("Try the other side", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Orders are shared between demo roles on this device and remain after restarting. Demo does not send documents to Firebase."
                )
                OutlinedButton(
                    { onSwitch(!session.admin) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Default.SwapHoriz, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (session.admin) "Switch to customer" else "Switch to administrator")
                }
            }
        OutlinedButton(
            onSignOut,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
        ) {
            Text("Sign out")
        }
        Text(
            "PrintEase ${BuildConfig.VERSION_NAME} · Native Android\nPDF, JPG, and PNG, up to 5 MB per file. Keep your queue number for pickup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PrintChecklistToggle(
    name: String,
    checked: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = onChange)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onCheckedChange = null, enabled = enabled)
        Spacer(Modifier.width(10.dp))
        Text(if (checked) "$name · Printed" else "Mark $name printed")
    }
}

@Composable
fun PreviewDialog(
    name: String,
    documents: Documents,
    load: suspend () -> File,
    onClose: () -> Unit,
    printOptions: PrintOptions? = null,
) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var page by rememberSaveable {
        mutableIntStateOf((printOptions?.pageRanges?.firstOrNull()?.start ?: 1) - 1)
    }
    var printLayout by rememberSaveable { mutableStateOf(printOptions != null) }
    var clipped by remember { mutableStateOf(false) }
    var pages by remember { mutableIntStateOf(0) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(retry) {
        error = null
        try {
            val result = load()
            pages = withContext(Dispatchers.IO) { documents.inspect(result, name).pages }
            file = result
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            error = friendlyError(e)
        }
    }
    LaunchedEffect(file, page, printLayout) {
        file?.let { f ->
            bitmap = null
            try {
                if (printLayout && printOptions != null) {
                    val (image, crop) = documents.renderPrint(f, page, printOptions)
                    bitmap = image
                    clipped = crop
                } else {
                    bitmap = documents.render(f, page)
                    clipped = false
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = "This page could not be displayed. Try opening the PDF in another app."
            }
        }
    }
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    navigationIcon = {
                        IconButton(onClose) { Icon(Icons.Default.Close, "Close PDF") }
                    },
                    actions = {
                        if (file != null)
                            IconButton({
                                runCatching {
                                        val uri =
                                            FileProvider.getUriForFile(
                                                context,
                                                "${context.packageName}.files",
                                                file!!,
                                            )
                                        context.startActivity(
                                            Intent.createChooser(
                                                Intent(Intent.ACTION_VIEW)
                                                    .setDataAndType(uri, "application/pdf")
                                                    .addFlags(
                                                        Intent.FLAG_GRANT_READ_URI_PERMISSION
                                                    ),
                                                "Open PDF",
                                            )
                                        )
                                    }
                                    .onFailure {
                                        error = "No PDF viewer is installed. Use the preview below."
                                    }
                            }) {
                                Icon(Icons.AutoMirrored.Filled.OpenInNew, "Open in another app")
                            }
                    },
                )
            },
            bottomBar = {
                if (pages > 0)
                    Surface {
                        Row(
                            Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton({ page-- }, enabled = page > 0) { Text("Previous") }
                            Text("${page + 1} / $pages")
                            TextButton({ page++ }, enabled = page < pages - 1) { Text("Next") }
                        }
                    }
            },
        ) { padding ->
            Box(
                Modifier.fillMaxSize()
                    .padding(padding)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    error != null ->
                        Column(
                            Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(error!!)
                            TextButton({
                                error = null
                                file = null
                                retry++
                            }) {
                                Text("Retry preview")
                            }
                        }
                    bitmap != null ->
                        Column(
                            Modifier.fillMaxSize()
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        ) {
                            if (printOptions != null) {
                                Panel(Modifier.fillMaxWidth()) {
                                    Text(
                                        "${paperLabel(printOptions.paper)} · ${printOptions.scaleLabel}",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                    if (printLayout)
                                        Text(
                                            "Dashed lines show estimated printer margins.",
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                    if (clipped)
                                        Text(
                                            "Actual size may cut off content. Use Fit to paper to keep the whole page.",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    TextButton({ printLayout = !printLayout }) {
                                        Text(
                                            if (printLayout) "View original PDF"
                                            else "View print layout"
                                        )
                                    }
                                }
                                Spacer(Modifier.height(12.dp))
                            }
                            Image(
                                bitmap!!.asImageBitmap(),
                                "Page ${page + 1} of $pages",
                                Modifier.fillMaxWidth()
                                    .aspectRatio(bitmap!!.width.toFloat() / bitmap!!.height),
                            )
                        }
                    else -> CircularProgressIndicator()
                }
            }
        }
    }
}
