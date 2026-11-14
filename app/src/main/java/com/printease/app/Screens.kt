@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.printease.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
fun PrintEase(model: PrintViewModel) {
    val authContext = LocalContext.current
    val state by model.shop.collectAsStateWithLifecycle()
    val busy by model.busy.collectAsStateWithLifecycle()
    val progress by model.progress.collectAsStateWithLifecycle()
    val route by model.route.collectAsStateWithLifecycle()
    val selected by model.selectedId.collectAsStateWithLifecycle()
    val pdf by model.pdf.collectAsStateWithLifecycle()
    val options by model.options.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var signOut by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PrintOrder?>(null) }
    var draftPreview by remember { mutableStateOf(false) }
    LaunchedEffect(model) {
        model.messages.collect { snackbar.showSnackbar(it, duration = SnackbarDuration.Long) }
    }
    val session = state.session
    val mainRoute = route in listOf("home", "orders", "account")
    BackHandler(enabled = session != null && !mainRoute) { if (!busy) model.navigate("orders") }
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
                                if (route == "request") "New print request" else "Order details",
                                style = MaterialTheme.typography.titleLarge,
                            )
                    },
                    navigationIcon = {
                        if (!mainRoute)
                            IconButton(onClick = { model.navigate("orders") }, enabled = !busy) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                            }
                    },
                    actions = {
                        if (BuildConfig.DEMO) ModeBadge()
                        if (mainRoute && !BuildConfig.DEMO)
                            IconButton(onClick = model::refresh, enabled = !busy) {
                                Icon(Icons.Default.Refresh, "Refresh orders")
                            }
                        if (mainRoute)
                            IconButton(onClick = { signOut = true }, enabled = !busy) {
                                Icon(Icons.AutoMirrored.Filled.Logout, "Sign out")
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
                        state.syncError,
                        model::demoLogin,
                        model::authenticate,
                        model::resetPassword,
                        onGoogle = { model.signInWithGoogle(authContext) },
                    )
                route == "request" ->
                    RequestContent(
                        pdf,
                        options,
                        busy,
                        progress,
                        onPick = model::pick,
                        onOptions = model::setOptions,
                        onSubmit = model::submit,
                        onPreview = { draftPreview = true },
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
                            { preview = order },
                        )
                    else
                        EmptyPanel(
                            "Order unavailable",
                            "It may still be syncing. Return to Orders to refresh your view.",
                            Icons.Default.CloudOff,
                        )
                }
                route == "account" ->
                    AccountContent(
                        session,
                        BuildConfig.DEMO,
                        busy,
                        model::demoLogin,
                        { signOut = true },
                    )
                else ->
                    Dashboard(
                        state,
                        BuildConfig.DEMO,
                        route == "home",
                        onNew = { model.navigate("request") },
                        onOrder = model::openOrder,
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
            order.fileName,
            model.documents,
            load = { model.loadDocument(order) },
            onClose = { preview = null },
        )
    }
    if (draftPreview && pdf != null)
        PreviewDialog(
            pdf!!.name,
            model.documents,
            load = { File(pdf!!.path) },
            onClose = { draftPreview = false },
        )
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
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null,
) {
    Button(
        onClick,
        modifier.heightIn(min = 52.dp),
        enabled = enabled,
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
        Brand()
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
                    "Try both sides of the campus print shop. Demo orders stay on this device.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                PrimaryButton(
                    "Continue as student",
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
                    if (register) "Create your student account" else "Welcome back",
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
            "BSU Alangilan · Campus Print Shop",
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
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow(if (session.admin) "CAMPUS PRINT SHOP" else "BSU ALANGILAN CAMPUS")
                Text(
                    if (session.admin) "A clear queue.\nA smoother day."
                    else "Hello, ${session.name.substringBefore(' ')}.",
                    style = MaterialTheme.typography.headlineLarge,
                )
                Text(
                    if (session.admin) "Review requests and keep everyone in the loop."
                    else "Make time for what’s next. We’ll handle the prints.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (overview) {
            if (!session.admin)
                item {
                    Surface(
                        color = Forest,
                        shape = RoundedCornerShape(26.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Box {
                            Icon(
                                Icons.Default.Description,
                                null,
                                Modifier.align(Alignment.TopEnd).padding(22.dp).size(82.dp),
                                tint = Color.White.copy(alpha = .08f),
                            )
                            Column(
                                Modifier.padding(24.dp),
                                verticalArrangement = Arrangement.spacedBy(14.dp),
                            ) {
                                Icon(Icons.Default.Description, null, tint = Lime)
                                Text(
                                    "Your next print,\njust a tap away.",
                                    style = MaterialTheme.typography.headlineMedium,
                                    color = Color.White,
                                )
                                Text(
                                    "PDF to pickup, without the wait in line.",
                                    color = Color(0xFFCEE1D3),
                                )
                                Button(
                                    onNew,
                                    colors =
                                        ButtonDefaults.buttonColors(
                                            containerColor = Lime,
                                            contentColor = Ink,
                                        ),
                                    modifier = Modifier.heightIn(min = 52.dp),
                                    shape = RoundedCornerShape(14.dp),
                                ) {
                                    Icon(Icons.Default.Add, null)
                                    Spacer(Modifier.width(8.dp))
                                    Text("New print request")
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
        if (state.cached || state.syncError != null)
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
                        if (session.admin) "Search document, student or queue"
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
        items(filtered, key = { it.id }) { order ->
            OrderCard(order, session.admin, { onOrder(order.id) })
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
                            "${order.options.pages} pages · ${order.options.copies} copies · ${order.options.paper}",
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
                StatusBadge(order.status)
                Text(
                    pesos(order.options.total),
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
) {
    val picker =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(onPick)
        }
    var review by rememberSaveable { mutableStateOf(false) }
    var discard by remember { mutableStateOf(false) }
    var copiesText by rememberSaveable(options.copies) { mutableStateOf(options.copies.toString()) }
    val validCopies = copiesText.toIntOrNull() in 1..100
    Column(Modifier.widthIn(max = 700.dp).fillMaxSize()) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("01 / YOUR DOCUMENT")
                Panel(Modifier.fillMaxWidth()) {
                    Icon(
                        Icons.Default.UploadFile,
                        null,
                        Modifier.size(38.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        pdf?.name ?: "Let’s get it on paper.",
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (pdf == null) "PDF only · Up to 5 MB · Up to 1,000 pages"
                        else "${pdf.pages} pages · ${(pdf.bytes + 1023) / 1024} KB · Saved draft",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(
                        { picker.launch(arrayOf("application/pdf")) },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                    ) {
                        Icon(Icons.Default.AttachFile, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (pdf == null) "Choose PDF" else "Replace PDF")
                    }
                    if (pdf != null)
                        TextButton(onPreview, enabled = !busy) {
                            Icon(Icons.Default.Visibility, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Preview document")
                        }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("02 / MAKE IT YOURS")
                Panel(Modifier.fillMaxWidth()) {
                    Text("Paper size", fontWeight = FontWeight.SemiBold)
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        listOf("A4", "Letter", "Legal").forEachIndexed { index, paper ->
                            SegmentedButton(
                                selected = options.paper == paper,
                                onClick = { onOptions(options.copy(paper = paper)) },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                                enabled = !busy,
                            ) {
                                Text(paper)
                            }
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
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Eyebrow("03 / KNOW YOUR TOTAL")
                Panel(Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Estimated total", Modifier.weight(1f))
                        Text(
                            if (pdf == null || !validCopies) "—" else pesos(options.total),
                            style = MaterialTheme.typography.headlineMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (pdf != null)
                        Text(
                            "${options.pages} pages × ${options.copies} copies × ${pesos(options.rate)} per printed side\n${options.sheets} sheets · Payment at pickup",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    Text(
                        "Sample shop rates. Duplex saves sheets; the per-side rate stays the same. The shop reviews your PDF before accepting.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
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
                    Text(
                        if (progress >= 1f) "Saving request…"
                        else "Preparing or uploading your document…",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                PrimaryButton(
                    if (busy) "Please wait…" else "Review request",
                    { review = true },
                    Modifier.fillMaxWidth(),
                    pdf != null && validCopies && !busy,
                    Icons.AutoMirrored.Filled.ArrowForward,
                )
            }
        }
    }
    if (review && pdf != null)
        AlertDialog(
            onDismissRequest = { review = false },
            title = { Text("Ready to send?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(pdf.name, fontWeight = FontWeight.Bold)
                    Text(
                        "${options.pages} pages · ${options.copies} copies · ${options.paper}\n${if(options.color) "Color" else "Black & white"} · ${if(options.duplex) "Double-sided" else "Single-sided"}"
                    )
                    Text("Estimated total: ${pesos(options.total)}", fontWeight = FontWeight.Bold)
                    Text(
                        "The shop will review your request and assign a queue number. Pay when you collect."
                    )
                }
            },
            confirmButton = {
                TextButton({
                    review = false
                    onSubmit()
                }) {
                    Text("Submit request")
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
) {
    var action by remember { mutableStateOf<OrderStatus?>(null) }
    var reason by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.widthIn(max = 700.dp)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState())
            .padding(22.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
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
                if (order.queueDay != null) "${order.queueDay} · Campus Print Shop"
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
            DetailLine("Paper size", order.options.paper)
            DetailLine("Pages / copies", "${order.options.pages} / ${order.options.copies}")
            DetailLine("Print mode", if (order.options.color) "Color" else "Black & white")
            DetailLine("Sides", if (order.options.duplex) "Double-sided" else "Single-sided")
            DetailLine("Sheets", "${order.options.sheets}")
            HorizontalDivider()
            DetailLine("Estimated total", pesos(order.options.total))
            OutlinedButton(
                onPreview,
                Modifier.fillMaxWidth().heightIn(min = 48.dp),
                enabled = !busy,
            ) {
                Icon(Icons.Default.PictureAsPdf, null)
                Spacer(Modifier.width(8.dp))
                Text("View PDF")
            }
            Text(
                "Request ID: ${order.id}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (cached)
            Text(
                "You’re viewing cached details. Connect to the internet before changing this order.",
                style = MaterialTheme.typography.bodySmall,
            )
        if (admin) {
            if (action == OrderStatus.Rejected)
                Panel(Modifier.fillMaxWidth()) {
                    Text("Why reject this request?", style = MaterialTheme.typography.titleLarge)
                    Text("Tell the student what to change before submitting again.")
                    OutlinedTextField(
                        reason,
                        { reason = it.take(500) },
                        Modifier.fillMaxWidth(),
                        label = { Text("Reason shown to the student") },
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
                OrderStatus.entries
                    .filter { order.status.canMoveTo(it) && it != OrderStatus.Cancelled }
                    .forEach { next ->
                        PrimaryButton(
                            when (next) {
                                OrderStatus.Accepted -> "Accept & assign queue number"
                                OrderStatus.Rejected -> "Reject request"
                                else -> "Mark as ${next.label}"
                            },
                            { action = next },
                            Modifier.fillMaxWidth(),
                            !busy && !cached,
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
        ?.takeIf { it != OrderStatus.Rejected }
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
                                if (next == OrderStatus.Rejected) reason.trim() else "",
                            )
                            reason = ""
                        },
                        enabled = next != OrderStatus.Rejected || reason.isNotBlank(),
                    ) {
                        Text("Confirm")
                    }
                },
                dismissButton = { TextButton({ action = null }) { Text("Go back") } },
            )
        }
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
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(20.dp),
            ) {
                Icon(
                    Icons.Default.PersonOutline,
                    null,
                    Modifier.padding(18.dp).size(30.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(session.name, style = MaterialTheme.typography.titleLarge)
            Text(session.email, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (session.admin) "Shop administrator" else "Student",
                fontWeight = FontWeight.SemiBold,
            )
        }
        Panel(Modifier.fillMaxWidth()) {
            Text("Campus Print Shop", style = MaterialTheme.typography.titleLarge)
            Text("BSU Alangilan Campus\nSubmit online. Collect and pay at the shop.")
            HorizontalDivider()
            Text("Sample print rates", fontWeight = FontWeight.Bold)
            DetailLine("A4 / Letter · B&W", "₱2 per side")
            DetailLine("A4 / Letter · Color", "₱10 per side")
            DetailLine("Legal paper", "+₱1 per side")
            Text(
                "Pages × copies × rate per printed side. Duplex saves paper, not the per-side charge. All pages use your selected color mode. Confirm final pricing with the shop.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
                    Text(if (session.admin) "Switch to student" else "Switch to administrator")
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
            "PrintEase 2.0 · Native Android\nPDF only, up to 5 MB. Keep your queue number for pickup.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PreviewDialog(
    name: String,
    documents: Documents,
    load: suspend () -> File,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    var file by remember { mutableStateOf<File?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var page by rememberSaveable { mutableIntStateOf(0) }
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
    LaunchedEffect(file, page) {
        file?.let { f ->
            bitmap = null
            try {
                bitmap = documents.render(f, page)
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
