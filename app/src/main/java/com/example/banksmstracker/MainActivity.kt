package com.example.banksmstracker

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var notificationSmsId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        notificationSmsId = intent?.getStringExtra(BackgroundSmsReceiver.EXTRA_SMS_ID)

        setContent {
            MaterialTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        BankSmsApp(
                            openDialogForSmsId = notificationSmsId,
                            onDialogOpenedFromNotification = { notificationSmsId = null }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        notificationSmsId = intent.getStringExtra(BackgroundSmsReceiver.EXTRA_SMS_ID)
    }
}

enum class TypeFilter(val title: String) {
    ALL("همه"),
    DEPOSIT("فقط واریز"),
    WITHDRAWAL("فقط برداشت")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BankSmsApp(
    openDialogForSmsId: String? = null,
    onDialogOpenedFromNotification: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val database = remember { AppDatabase.getDatabase(context) }
    val noteDao = remember { database.noteDao() }

    val requiredPermissions = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS
            )
        }
    }

    var hasSmsPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        )
    }

    var allTransactions by remember { mutableStateOf<List<BankTransaction>>(emptyList()) }
    var isLoading by remember { mutableStateOf(false) }
    var selectedTransaction by remember { mutableStateOf<BankTransaction?>(null) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedTypeFilter by remember { mutableStateOf(TypeFilter.ALL) }
    var selectedBankFilter by remember { mutableStateOf("همه بانک‌ها") }

    val availableBanks by remember(allTransactions) {
        derivedStateOf {
            val banks = allTransactions.map { it.bankName }.distinct().sorted()
            listOf("همه بانک‌ها") + banks
        }
    }

    val filteredTransactions by remember(allTransactions, searchQuery, selectedTypeFilter, selectedBankFilter) {
        derivedStateOf {
            allTransactions.filter { tx ->
                val matchesType = when (selectedTypeFilter) {
                    TypeFilter.ALL -> true
                    TypeFilter.DEPOSIT -> tx.type == TransactionType.DEPOSIT
                    TypeFilter.WITHDRAWAL -> tx.type == TransactionType.WITHDRAWAL
                }
                val matchesBank = selectedBankFilter == "همه بانک‌ها" || tx.bankName == selectedBankFilter
                val query = searchQuery.trim()
                val matchesQuery = query.isEmpty() ||
                        tx.userNote.contains(query, ignoreCase = true) ||
                        tx.bankName.contains(query, ignoreCase = true) ||
                        tx.rawMessage.contains(query, ignoreCase = true) ||
                        tx.amount.toString().contains(query)

                matchesType && matchesBank && matchesQuery
            }
        }
    }

    val loadSms: () -> Unit = {
        coroutineScope.launch {
            isLoading = true
            val list = withContext(Dispatchers.IO) {
                val savedNotesMap = noteDao.getAllNotes().associate { it.smsId to it.note }
                readBankSmsList(context, savedNotesMap)
            }
            allTransactions = list
            isLoading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_SMS] ?: false
        val receiveGranted = permissions[Manifest.permission.RECEIVE_SMS] ?: false
        hasSmsPermission = readGranted && receiveGranted
        if (hasSmsPermission) loadSms()
    }

    LaunchedEffect(hasSmsPermission) {
        if (hasSmsPermission && allTransactions.isEmpty()) {
            loadSms()
        }
    }

    LaunchedEffect(allTransactions, openDialogForSmsId) {
        if (!openDialogForSmsId.isNullOrEmpty() && allTransactions.isNotEmpty()) {
            val targetTx = allTransactions.find { it.id == openDialogForSmsId }
            if (targetTx != null) {
                selectedTransaction = targetTx
                onDialogOpenedFromNotification()
            }
        }
    }

    SmsBroadcastObserver(
        hasPermission = hasSmsPermission,
        onNewBankTransaction = {
            coroutineScope.launch {
                delay(1500L)
                loadSms()
            }
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("مدیریت پیامک‌های بانکی", fontWeight = FontWeight.Bold) },
                actions = {
                    if (hasSmsPermission) {
                        IconButton(
                            onClick = {
                                CsvExporter.exportAndShareCsv(context, filteredTransactions)
                            },
                            enabled = filteredTransactions.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.FileDownload,
                                contentDescription = "خروجی اکسل (CSV)"
                            )
                        }
                        IconButton(onClick = { loadSms() }) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "بروزرسانی"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                !hasSmsPermission -> {
                    PermissionRequestView(
                        onRequestPermission = {
                            permissionLauncher.launch(requiredPermissions)
                        }
                    )
                }
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        FilterAndSearchSection(
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            selectedType = selectedTypeFilter,
                            onTypeChange = { selectedTypeFilter = it },
                            banks = availableBanks,
                            selectedBank = selectedBankFilter,
                            onBankChange = { selectedBankFilter = it }
                        )

                        SummaryHeader(transactions = filteredTransactions)

                        if (filteredTransactions.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "تراکنشی با این مشخصات یافت نشد.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                items(filteredTransactions, key = { it.id }) { item ->
                                    TransactionCard(
                                        transaction = item,
                                        onClick = { selectedTransaction = item }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        selectedTransaction?.let { currentTx ->
            NoteDialog(
                transaction = currentTx,
                onDismiss = { selectedTransaction = null },
                onSave = { newNote ->
                    coroutineScope.launch(Dispatchers.IO) {
                        if (newNote.isBlank()) {
                            noteDao.deleteNote(currentTx.id)
                        } else {
                            noteDao.saveNote(TransactionNoteEntity(smsId = currentTx.id, note = newNote))
                        }
                    }
                    allTransactions = allTransactions.map {
                        if (it.id == currentTx.id) it.copy(userNote = newNote) else it
                    }
                    selectedTransaction = null
                }
            )
        }
    }
}

@Composable
fun SmsBroadcastObserver(
    hasPermission: Boolean,
    onNewBankTransaction: (BankTransaction) -> Unit
) {
    val context = LocalContext.current
    val currentOnNewTransaction by rememberUpdatedState(onNewBankTransaction)

    DisposableEffect(context, hasPermission) {
        if (!hasPermission) return@DisposableEffect onDispose { }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
                val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
                if (messages.isNullOrEmpty()) return

                val sender = messages[0].originatingAddress ?: "نامشخص"
                val timestamp = messages[0].timestampMillis
                val fullBody = messages.joinToString("") { it.messageBody ?: "" }

                IranBankSmsParser.parseSms(timestamp.toString(), sender, fullBody, timestamp)?.let {
                    currentOnNewTransaction(it)
                }
            }
        }

        val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply {
            priority = 999
        }
        ContextCompat.registerReceiver(context, receiver, intentFilter, ContextCompat.RECEIVER_EXPORTED)

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
}

@Composable
fun FilterAndSearchSection(
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedType: TypeFilter,
    onTypeChange: (TypeFilter) -> Unit,
    banks: List<String>,
    selectedBank: String,
    onBankChange: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("جستجو در توضیحات، بانک یا متن پیامک...") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "جستجو") },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchChange("") }) {
                        Icon(Icons.Default.Close, contentDescription = "پاک کردن")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TypeFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedType == filter,
                    onClick = { onTypeChange(filter) },
                    label = { Text(filter.title) },
                    leadingIcon = if (selectedType == filter) {
                        { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp)) }
                    } else null
                )
            }
        }

        if (banks.size > 1) {
            LazyRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(banks) { bank ->
                    FilterChip(
                        selected = selectedBank == bank,
                        onClick = { onBankChange(bank) },
                        label = { Text(bank) }
                    )
                }
            }
        }
    }
}

@Composable
fun SummaryHeader(transactions: List<BankTransaction>) {
    val totalDeposit = remember(transactions) {
        transactions.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
    }
    val totalWithdrawal = remember(transactions) {
        transactions.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(10.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("جمع واریز (${transactions.count { it.type == TransactionType.DEPOSIT }})", fontSize = 12.sp)
                Text(
                    text = "+${formatCurrency(totalDeposit)} ریال",
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("جمع برداشت (${transactions.count { it.type == TransactionType.WITHDRAWAL }})", fontSize = 12.sp)
                Text(
                    text = "-${formatCurrency(totalWithdrawal)} ریال",
                    color = Color(0xFFC62828),
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

@Composable
fun PermissionRequestView(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "دسترسی به پیامک‌ها و اعلان‌ها",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "برای استخراج خودکار تراکنش‌های واریز و برداشت بانکی، تبدیل تاریخ آن‌ها به شمسی و نمایش نوتیفیکیشن هنگام دریافت پیامک جدید، لطفاً مجوزهای مورد نیاز را تایید کنید.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission) {
            Text("اعطای مجوزهای مورد نیاز")
        }
    }
}

@Composable
fun TransactionCard(
    transaction: BankTransaction,
    onClick: () -> Unit
) {
    val isDeposit = transaction.type == TransactionType.DEPOSIT
    val statusColor = if (isDeposit) Color(0xFF2E7D32) else Color(0xFFC62828)
    val typeLabel = if (isDeposit) "واریز (بستانکار)" else "برداشت (بدهکار)"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isDeposit) Icons.Default.ArrowDownward else Icons.Default.ArrowUpward,
                        contentDescription = null,
                        tint = statusColor,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$typeLabel • ${transaction.bankName}",
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
                Text(
                    text = transaction.persianDate,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatCurrency(transaction.amount)} ریال",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold
                )
                transaction.balance?.let { bal ->
                    Text(
                        text = "مانده: ${formatCurrency(bal)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (transaction.userNote.isNotBlank()) {
                        "بابت: ${transaction.userNote}"
                    } else {
                        "برای ثبت علت واریز/برداشت لمس کنید..."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (transaction.userNote.isNotBlank()) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "ویرایش توضیح",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

@Composable
fun NoteDialog(
    transaction: BankTransaction,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit
) {
    var noteText by remember { mutableStateOf(transaction.userNote) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = "ثبت توضیح (${transaction.bankName})", fontWeight = FontWeight.Bold)
        },
        text = {
            Column {
                Text(
                    text = "مبلغ: ${formatCurrency(transaction.amount)} ریال",
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "تاریخ: ${transaction.persianDate}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = transaction.rawMessage,
                        modifier = Modifier.padding(8.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    label = { Text("بابت چه چیزی هزینه/واریز شد؟") },
                    placeholder = { Text("مثلاً: خرید سوپرمارکت، حقوق...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSave(noteText.trim()) }) {
                Text("ذخیره")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("انصراف")
            }
        }
    )
}
