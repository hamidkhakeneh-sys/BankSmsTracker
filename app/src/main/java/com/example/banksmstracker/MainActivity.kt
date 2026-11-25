package com.example.banksmstracker

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
                        color = Color(0xFFF4F6F9)
                    ) {
                        HolooSmsAccountingApp(
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

enum class ScopeTab(val code: String, val title: String) {
    ALL("ALL", "کل حساب‌ها"),
    PERSONAL("PERSONAL", "حساب شخصی"),
    BUILDING("BUILDING", "مدیریت ساختمان")
}

enum class TypeFilter(val title: String) {
    ALL("همه"),
    DEPOSIT("واریز"),
    WITHDRAWAL("برداشت"),
    INTERNAL("انتقال داخلی")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolooSmsAccountingApp(
    openDialogForSmsId: String? = null,
    onDialogOpenedFromNotification: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val database = remember { AppDatabase.getDatabase(context) }
    val appDao = remember { database.appDao() }

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

    // داده‌های اصلی برنامه
    var allTransactions by remember { mutableStateOf<List<BankTransaction>>(emptyList()) }
    var accountProfiles by remember { mutableStateOf<List<AccountProfileEntity>>(emptyList()) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var ignoredItems by remember { mutableStateOf<List<IgnoredItemEntity>>(emptyList()) }
    var discrepancies by remember { mutableStateOf<List<AccountDiscrepancy>>(emptyList()) }

    var isLoading by remember { mutableStateOf(false) }

    // ناوبری تب‌های پایین (سبک نرم‌افزار حسابداری)
    var bottomTabIndex by remember { mutableIntStateOf(0) } // 0: داشبورد، 1: دفتر روزنامه، 2: حساب‌ها و مغایرت، 3: گزارشات
    var selectedScope by remember { mutableStateOf(ScopeTab.ALL) }

    // دیالوگ‌ها
    var selectedTransaction by remember { mutableStateOf<BankTransaction?>(null) }
    var showCategoryManager by remember { mutableStateOf(false) }
    var showIgnoredManager by remember { mutableStateOf(false) }

    // فیلترهای دفتر روزنامه
    var searchQuery by remember { mutableStateOf("") }
    var selectedTypeFilter by remember { mutableStateOf(TypeFilter.ALL) }
    var selectedAccountFilter by remember { mutableStateOf("همه حساب‌ها") }
    var selectedCategoryFilter by remember { mutableStateOf("همه دسته‌ها") }

    // تراکنش‌های فیلترشده بر اساس صندوق انتخابی (شخصی / ساختمان / کل)
    val scopedTransactions by remember(allTransactions, selectedScope) {
        derivedStateOf {
            when (selectedScope) {
                ScopeTab.ALL -> allTransactions
                ScopeTab.PERSONAL -> allTransactions.filter { it.scope == "PERSONAL" }
                ScopeTab.BUILDING -> allTransactions.filter { it.scope == "BUILDING" }
            }
        }
    }

    val scopedDiscrepancies by remember(discrepancies, accountProfiles, selectedScope) {
        derivedStateOf {
            val scopeMap = accountProfiles.associate { it.accountKey to it.scope }
            when (selectedScope) {
                ScopeTab.ALL -> discrepancies
                ScopeTab.PERSONAL -> discrepancies.filter { scopeMap[it.accountKey] == "PERSONAL" }
                ScopeTab.BUILDING -> discrepancies.filter { scopeMap[it.accountKey] == "BUILDING" }
            }
        }
    }

    val availableAccounts by remember(scopedTransactions) {
        derivedStateOf {
            val accs = scopedTransactions.map { it.accountKey }.distinct().sorted()
            listOf("همه حساب‌ها") + accs
        }
    }

    val availableCategoryFilters by remember(categories, selectedScope) {
        derivedStateOf {
            val filteredCats = categories.filter {
                selectedScope == ScopeTab.ALL || it.scope == "ALL" || it.scope == selectedScope.code
            }.map { it.name }
            listOf("همه دسته‌ها", "دسته‌بندی نشده") + filteredCats
        }
    }

    val journalTransactions by remember(
        scopedTransactions, searchQuery, selectedTypeFilter, selectedAccountFilter, selectedCategoryFilter
    ) {
        derivedStateOf {
            scopedTransactions.filter { tx ->
                val matchesType = when (selectedTypeFilter) {
                    TypeFilter.ALL -> true
                    TypeFilter.DEPOSIT -> tx.type == TransactionType.DEPOSIT
                    TypeFilter.WITHDRAWAL -> tx.type == TransactionType.WITHDRAWAL
                    TypeFilter.INTERNAL -> tx.type == TransactionType.INTERNAL
                }
                val matchesAcc = selectedAccountFilter == "همه حساب‌ها" || tx.accountKey == selectedAccountFilter
                val matchesCat = when (selectedCategoryFilter) {
                    "همه دسته‌ها" -> true
                    "دسته‌بندی نشده" -> tx.category.isBlank()
                    else -> tx.category == selectedCategoryFilter
                }
                val query = searchQuery.trim()
                val matchesQuery = query.isEmpty() ||
                        tx.userNote.contains(query, ignoreCase = true) ||
                        tx.category.contains(query, ignoreCase = true) ||
                        tx.accountKey.contains(query, ignoreCase = true) ||
                        tx.rawMessage.contains(query, ignoreCase = true) ||
                        tx.amount.toString().contains(query)

                matchesType && matchesAcc && matchesCat && matchesQuery
            }
        }
    }

    val loadData: () -> Unit = {
        coroutineScope.launch {
            isLoading = true
            withContext(Dispatchers.IO) {
                var currentCats = appDao.getAllCategories()
                if (currentCats.isEmpty()) {
                    appDao.insertCategories(AppDatabase.DEFAULT_CATEGORIES)
                    currentCats = appDao.getAllCategories()
                }
                categories = currentCats

                val ignoredList = appDao.getAllIgnored()
                ignoredItems = ignoredList
                val ignoredIds = ignoredList.filter { !it.isSenderBlocked }.map { it.keyId }.toSet()
                val blockedSenders = ignoredList.filter { it.isSenderBlocked }.map { it.keyId }.toSet()

                val savedNotesMap = appDao.getAllNotes().associateBy { it.smsId }
                val existingProfilesMap = appDao.getAllAccountProfiles().associateBy { it.accountKey }

                val (txList, updatedProfiles) = readBankSmsList(
                    context = context,
                    savedNotes = savedNotesMap,
                    accountProfiles = existingProfilesMap,
                    ignoredSmsIds = ignoredIds,
                    blockedSenders = blockedSenders
                )

                // ذخیره پروفایل حساب‌های جدید کشف‌شده در دیتابیس
                updatedProfiles.forEach { prof ->
                    if (!existingProfilesMap.containsKey(prof.accountKey)) {
                        appDao.saveAccountProfile(prof)
                    }
                }

                accountProfiles = updatedProfiles
                allTransactions = txList
                discrepancies = IranBankSmsParser.findDiscrepancies(txList)
            }
            isLoading = false
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val readGranted = permissions[Manifest.permission.READ_SMS] ?: false
        val receiveGranted = permissions[Manifest.permission.RECEIVE_SMS] ?: false
        hasSmsPermission = readGranted && receiveGranted
        if (hasSmsPermission) loadData()
    }

    LaunchedEffect(hasSmsPermission) {
        if (hasSmsPermission && allTransactions.isEmpty()) {
            loadData()
        }
    }

    LaunchedEffect(allTransactions, openDialogForSmsId) {
        if (!openDialogForSmsId.isNullOrEmpty() && allTransactions.isNotEmpty()) {
            val targetTx = allTransactions.find { it.id == openDialogForSmsId }
            if (targetTx != null) {
                selectedTransaction = targetTx
                bottomTabIndex = 1
                onDialogOpenedFromNotification()
            }
        }
    }

    SmsBroadcastObserver(
        hasPermission = hasSmsPermission,
        onNewBankTransaction = {
            coroutineScope.launch {
                delay(1500L)
                loadData()
            }
        }
    )

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF1A237E))) {
                TopAppBar(
                    title = {
                        Column {
                            Text("حسابداری هوشمند پیامکی", fontWeight = FontWeight.Bold, fontSize = 17.sp, color = Color.White)
                            Text("تفکیک شخصی و مدیریت ساختمان", fontSize = 11.sp, color = Color(0xFFC5CAE9))
                        }
                    },
                    actions = {
                        if (hasSmsPermission) {
                            IconButton(onClick = { showCategoryManager = true }) {
                                Icon(Icons.Default.Category, contentDescription = "دسته‌بندی‌ها", tint = Color.White)
                            }
                            IconButton(onClick = { showIgnoredManager = true }) {
                                Icon(Icons.Default.Block, contentDescription = "مسدودشده‌ها", tint = Color.White)
                            }
                            IconButton(
                                onClick = { CsvExporter.exportAndShareCsv(context, journalTransactions, selectedScope.title) },
                                enabled = journalTransactions.isNotEmpty()
                            ) {
                                Icon(Icons.Default.FileDownload, contentDescription = "خروجی اکسل", tint = Color.White)
                            }
                            IconButton(onClick = { loadData() }) {
                                Icon(Icons.Default.Refresh, contentDescription = "بروزرسانی", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A237E))
                )

                // سوییچر تفکیک صندوق‌ها (کل / شخصی / مدیریت ساختمان)
                if (hasSmsPermission) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        ScopeTab.entries.forEach { tab ->
                            val isSelected = selectedScope == tab
                            Surface(
                                modifier = Modifier
                                    .weight(1f)
                                    .clickable {
                                        selectedScope = tab
                                        selectedAccountFilter = "همه حساب‌ها"
                                        selectedCategoryFilter = "همه دسته‌ها"
                                    },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color.White else Color(0xFF283593)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = tab.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) Color(0xFF1A237E) else Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        bottomBar = {
            if (hasSmsPermission) {
                NavigationBar(containerColor = Color.White, tonalElevation = 8.dp) {
                    NavigationBarItem(
                        selected = bottomTabIndex == 0,
                        onClick = { bottomTabIndex = 0 },
                        icon = { Icon(Icons.Default.Dashboard, contentDescription = null) },
                        label = { Text("داشبورد و نمودار", fontSize = 11.sp) }
                    )
                    NavigationBarItem(
                        selected = bottomTabIndex == 1,
                        onClick = { bottomTabIndex = 1 },
                        icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) },
                        label = { Text("دفتر روزنامه", fontSize = 11.sp) }
                    )
                    NavigationBarItem(
                        selected = bottomTabIndex == 2,
                        onClick = { bottomTabIndex = 2 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (scopedDiscrepancies.isNotEmpty()) {
                                        Badge { Text("${scopedDiscrepancies.size}") }
                                    }
                                }
                            ) {
                                Icon(Icons.Default.AccountBalance, contentDescription = null)
                            }
                        },
                        label = { Text("حساب و مغایرت", fontSize = 11.sp) }
                    )
                    NavigationBarItem(
                        selected = bottomTabIndex == 3,
                        onClick = { bottomTabIndex = 3 },
                        icon = { Icon(Icons.Default.Assessment, contentDescription = null) },
                        label = { Text("گزارشات", fontSize = 11.sp) }
                    )
                }
            }
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
                        onRequestPermission = { permissionLauncher.launch(requiredPermissions) }
                    )
                }
                isLoading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }
                else -> {
                    when (bottomTabIndex) {
                        0 -> DashboardScreen(
                            transactions = scopedTransactions,
                            accountProfiles = accountProfiles,
                            discrepancyCount = scopedDiscrepancies.size,
                            scopeTitle = selectedScope.title,
                            onNavigateToDiscrepancies = { bottomTabIndex = 2 }
                        )
                        1 -> JournalScreen(
                            transactions = journalTransactions,
                            searchQuery = searchQuery,
                            onSearchChange = { searchQuery = it },
                            selectedType = selectedTypeFilter,
                            onTypeChange = { selectedTypeFilter = it },
                            accounts = availableAccounts,
                            selectedAccount = selectedAccountFilter,
                            onAccountChange = { selectedAccountFilter = it },
                            categoryFilters = availableCategoryFilters,
                            selectedCategory = selectedCategoryFilter,
                            onCategoryChange = { selectedCategoryFilter = it },
                            onTransactionClick = { selectedTransaction = it }
                        )
                        2 -> AccountsAndDiscrepanciesScreen(
                            accountProfiles = accountProfiles,
                            transactions = allTransactions,
                            discrepancies = scopedDiscrepancies,
                            onToggleAccountScope = { profile, newScope ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveAccountProfile(profile.copy(scope = newScope))
                                    loadData()
                                }
                            }
                        )
                        3 -> ReportsScreen(
                            transactions = scopedTransactions,
                            scopeTitle = selectedScope.title,
                            onExportCsv = {
                                CsvExporter.exportAndShareCsv(context, scopedTransactions, selectedScope.title)
                            }
                        )
                    }
                }
            }
        }

        // دیالوگ ثبت سند، دسته‌بندی، ورودی صوتی و انتقال بین حساب‌ها
        selectedTransaction?.let { currentTx ->
            TransactionDetailDialog(
                transaction = currentTx,
                categories = categories,
                onDismiss = { selectedTransaction = null },
                onSave = { newCategory, newNote, transferOverride ->
                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.saveNote(
                            TransactionNoteEntity(
                                smsId = currentTx.id,
                                note = newNote,
                                category = newCategory,
                                isInternalTransferOverride = transferOverride
                            )
                        )
                        loadData()
                    }
                    selectedTransaction = null
                },
                onMarkAsNotBankSms = { blockEntireSender ->
                    coroutineScope.launch(Dispatchers.IO) {
                        if (blockEntireSender) {
                            appDao.addIgnored(
                                IgnoredItemEntity(
                                    keyId = currentTx.sender,
                                    isSenderBlocked = true,
                                    label = "سرشماره مسدودشده: ${currentTx.sender}"
                                )
                            )
                        } else {
                            appDao.addIgnored(
                                IgnoredItemEntity(
                                    keyId = currentTx.id,
                                    isSenderBlocked = false,
                                    label = "پیامک ${currentTx.sender} (${currentTx.persianDate})"
                                )
                            )
                        }
                        loadData()
                    }
                    selectedTransaction = null
                    Toast.makeText(context, "با موفقیت از محاسبات حذف شد", Toast.LENGTH_SHORT).show()
                },
                onAddNewCategory = { newCatName ->
                    coroutineScope.launch(Dispatchers.IO) {
                        val isExp = currentTx.type != TransactionType.DEPOSIT
                        appDao.addCategory(CategoryEntity(newCatName, isExp, currentTx.scope))
                        categories = appDao.getAllCategories()
                    }
                }
            )
        }

        if (showCategoryManager) {
            CategoryManagerDialog(
                categories = categories,
                onDismiss = { showCategoryManager = false },
                onAddCategory = { name, isExpense, scope ->
                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.addCategory(CategoryEntity(name, isExpense, scope))
                        categories = appDao.getAllCategories()
                    }
                },
                onDeleteCategory = { name ->
                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.deleteCategory(name)
                        categories = appDao.getAllCategories()
                    }
                }
            )
        }

        if (showIgnoredManager) {
            IgnoredItemsDialog(
                ignoredItems = ignoredItems,
                onDismiss = { showIgnoredManager = false },
                onRestoreItem = { keyId ->
                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.removeIgnored(keyId)
                        loadData()
                    }
                }
            )
        }
    }
}

// ============================================================================
// تب ۱: داشبورد مدیریتی و نمودارهای پویا
// ============================================================================
@Composable
fun DashboardScreen(
    transactions: List<BankTransaction>,
    accountProfiles: List<AccountProfileEntity>,
    discrepancyCount: Int,
    scopeTitle: String,
    onNavigateToDiscrepancies: () -> Unit
) {
    val totalDeposit = remember(transactions) {
        transactions.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
    }
    val totalWithdrawal = remember(transactions) {
        transactions.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
    }
    val internalTransferSum = remember(transactions) {
        transactions.filter { it.type == TransactionType.INTERNAL }.sumOf { it.amount } / 2
    }

    var chartExpenseMode by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // کارت هشدار مغایرت در صورت وجود
        if (discrepancyCount > 0) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToDiscrepancies() },
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE65100))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "هشدار مغایرت حسابداری ($discrepancyCount مورد)",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100),
                            fontSize = 13.sp
                        )
                        Text(
                            text = "اختلاف بین مانده حساب‌ها در پیامک‌ها شناسایی شد. برای بررسی لمس کنید.",
                            fontSize = 11.sp,
                            color = Color(0xFF5D4037)
                        )
                    }
                    Icon(Icons.Default.ChevronLeft, contentDescription = null, tint = Color(0xFFE65100))
                }
            }
        }

        // کارت خلاصه تراز مالی (سبک داشبورد هلو)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("تراز مالی ($scopeTitle)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF1A237E))
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text("کل واریزی‌ها (بستانکار)", fontSize = 12.sp, color = Color.Gray)
                        Text(
                            text = "+${formatCurrency(totalDeposit)} ریال",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = Color(0xFF2E7D32)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("کل برداشت‌ها (بدهکار)", fontSize = 12.sp, color = Color.Gray)
                        Text(
                            text = "-${formatCurrency(totalWithdrawal)} ریال",
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 14.sp,
                            color = Color(0xFFC62828)
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("جریان خالص نقدینگی:", fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    val net = totalDeposit - totalWithdrawal
                    Text(
                        text = "${if (net >= 0) "+" else ""}${formatCurrency(net)} ریال",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = if (net >= 0) Color(0xFF1565C0) else Color(0xFFC62828)
                    )
                }
                if (internalTransferSum > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "انتقال بین حساب‌های خودی (کسر شده از تورم آمار): ${formatCurrency(internalTransferSum)} ریال",
                        fontSize = 11.sp,
                        color = Color(0xFF0277BD)
                    )
                }
            }
        }

        // سوییچر نمودار هزینه / درآمد
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = chartExpenseMode,
                onClick = { chartExpenseMode = true },
                label = { Text("نمودار هزینه‌ها") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = !chartExpenseMode,
                onClick = { chartExpenseMode = false },
                label = { Text("نمودار درآمدها") },
                modifier = Modifier.weight(1f)
            )
        }

        // نمودار دایره‌ای تعاملی دسته‌بندی‌ها
        InteractiveCategoryDonutChart(
            transactions = transactions,
            isExpenseMode = chartExpenseMode
        )

        // نمودار میله‌ای مقایسه ماهانه
        MonthlyComparisonBarChart(transactions = transactions)
    }
}

// ============================================================================
// تب ۲: دفتر روزنامه (لیست اسناد و تراکنش‌ها)
// ============================================================================
@Composable
fun JournalScreen(
    transactions: List<BankTransaction>,
    searchQuery: String,
    onSearchChange: (String) -> Unit,
    selectedType: TypeFilter,
    onTypeChange: (TypeFilter) -> Unit,
    accounts: List<String>,
    selectedAccount: String,
    onAccountChange: (String) -> Unit,
    categoryFilters: List<String>,
    selectedCategory: String,
    onCategoryChange: (String) -> Unit,
    onTransactionClick: (BankTransaction) -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("جستجو در شرح سند، دسته، حساب یا پیامک...", fontSize = 13.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = null)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TypeFilter.entries) { filter ->
                    FilterChip(
                        selected = selectedType == filter,
                        onClick = { onTypeChange(filter) },
                        label = { Text(filter.title, fontSize = 12.sp) }
                    )
                }
            }

            if (accounts.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(accounts) { acc ->
                        FilterChip(
                            selected = selectedAccount == acc,
                            onClick = { onAccountChange(acc) },
                            label = { Text(acc, fontSize = 11.sp) }
                        )
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(categoryFilters) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onCategoryChange(cat) },
                        label = { Text(cat, fontSize = 11.sp) }
                    )
                }
            }
        }

        if (transactions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("سندی با این فیلترها یافت نشد.", color = Color.Gray)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(transactions, key = { it.id }) { tx ->
                    TransactionCard(transaction = tx, onClick = { onTransactionClick(tx) })
                }
            }
        }
    }
}

// ============================================================================
// تب ۳: مدیریت حساب‌ها (تفکیک شخصی / ساختمان) و کشف مغایرت‌ها
// ============================================================================
@Composable
fun AccountsAndDiscrepanciesScreen(
    accountProfiles: List<AccountProfileEntity>,
    transactions: List<BankTransaction>,
    discrepancies: List<AccountDiscrepancy>,
    onToggleAccountScope: (AccountProfileEntity, String) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = "تفکیک حساب‌های شخصی و مدیریت ساختمان",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFF1A237E)
            )
            Text(
                text = "برای هر شماره حساب مشخص کنید مربوط به امور شخصی است یا صندوق ساختمان:",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        items(accountProfiles, key = { it.accountKey }) { profile ->
            val latestTx = transactions.firstOrNull { it.accountKey == profile.accountKey && it.balance != null }
            val isBuilding = profile.scope == "BUILDING"

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(profile.accountKey, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            latestTx?.balance?.let { bal ->
                                Text(
                                    text = "آخرین موجودی: ${formatCurrency(bal)} ریال",
                                    fontSize = 12.sp,
                                    color = Color(0xFF2E7D32)
                                )
                            }
                        }
                        Surface(
                            color = if (isBuilding) Color(0xFFE8EAF6) else Color(0xFFE0F2F1),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isBuilding) "مدیریت ساختمان" else "حساب شخصی",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isBuilding) Color(0xFF1A237E) else Color(0xFF00695C)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !isBuilding,
                            onClick = { onToggleAccountScope(profile, "PERSONAL") },
                            label = { Text("حساب شخصی", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                        FilterChip(
                            selected = isBuilding,
                            onClick = { onToggleAccountScope(profile, "BUILDING") },
                            label = { Text("حساب ساختمان", fontSize = 12.sp) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "گزارش مغایرت‌های حسابداری (${discrepancies.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                color = Color(0xFFC62828)
            )
            Text(
                text = "مواردی که مانده پیامک جدید با جمع و تفریق مانده قبلی هم‌خوانی ندارد:",
                fontSize = 12.sp,
                color = Color.Gray
            )
        }

        if (discrepancies.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("هیچ مغایرتی در مانده حساب‌ها یافت نشد. تمام تراکنش‌ها تراز هستند.", fontSize = 13.sp)
                    }
                }
            }
        } else {
            items(discrepancies) { disc ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(disc.accountKey, fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C), fontSize = 14.sp)
                            Text(disc.date, fontSize = 11.sp, color = Color.DarkGray)
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(disc.description, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "مانده قبلی: ${formatCurrency(disc.previousBalance)} | مانده ثبت‌شده: ${formatCurrency(disc.currentBalance)}",
                            fontSize = 11.sp,
                            color = Color.DarkGray
                        )
                    }
                }
            }
        }
    }
}

// ============================================================================
// تب ۴: گزارشات جامع (ترازنامه دسته‌بندی‌ها و خروجی اکسل)
// ============================================================================
@Composable
fun ReportsScreen(
    transactions: List<BankTransaction>,
    scopeTitle: String,
    onExportCsv: () -> Unit
) {
    val expenseByCategory = remember(transactions) {
        transactions
            .filter { it.type == TransactionType.WITHDRAWAL }
            .groupBy { it.category.ifBlank { "دسته‌بندی نشده" } }
            .mapValues { it.value.sumOf { tx -> tx.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

    val incomeByCategory = remember(transactions) {
        transactions
            .filter { it.type == TransactionType.DEPOSIT }
            .groupBy { it.category.ifBlank { "دسته‌بندی نشده" } }
            .mapValues { it.value.sumOf { tx -> tx.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Button(
                onClick = onExportCsv,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1A237E))
            ) {
                Icon(Icons.Default.FileDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("دریافت خروجی اکسل کامل ($scopeTitle)")
            }
        }

        item {
            Text("خلاصه هزینه‌ها به تفکیک سرفصل ($scopeTitle)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFFC62828))
        }

        if (expenseByCategory.isEmpty()) {
            item { Text("هزینه‌ای ثبت نشده است.", fontSize = 13.sp, color = Color.Gray) }
        } else {
            items(expenseByCategory) { (cat, sum) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cat, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("${formatCurrency(sum)} ریال", fontWeight = FontWeight.Bold, color = Color(0xFFC62828), fontSize = 14.sp)
                    }
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(6.dp))
            Text("خلاصه درآمدها و واریزی‌ها به تفکیک سرفصل ($scopeTitle)", fontWeight = FontWeight.Bold, fontSize = 15.sp, color = Color(0xFF2E7D32))
        }

        if (incomeByCategory.isEmpty()) {
            item { Text("واریزی ثبت نشده است.", fontSize = 13.sp, color = Color.Gray) }
        } else {
            items(incomeByCategory) { (cat, sum) ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(cat, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                        Text("${formatCurrency(sum)} ریال", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32), fontSize = 14.sp)
                    }
                }
            }
        }
    }
}

// ============================================================================
// کامپوننت‌های مشترک: کارت تراکنش، دیالوگ ثبت سند، ورودی صوتی و مدیریت دسته‌ها
// ============================================================================
@Composable
fun TransactionCard(
    transaction: BankTransaction,
    onClick: () -> Unit
) {
    val statusColor = when (transaction.type) {
        TransactionType.DEPOSIT -> Color(0xFF2E7D32)
        TransactionType.WITHDRAWAL -> Color(0xFFC62828)
        TransactionType.INTERNAL -> Color(0xFF0277BD)
        TransactionType.UNKNOWN -> Color.Gray
    }
    val typeLabel = when (transaction.type) {
        TransactionType.DEPOSIT -> "واریز"
        TransactionType.WITHDRAWAL -> "برداشت"
        TransactionType.INTERNAL -> "انتقال بین حساب‌ها"
        TransactionType.UNKNOWN -> "نامشخص"
    }
    val icon = when (transaction.type) {
        TransactionType.DEPOSIT -> Icons.Default.ArrowDownward
        TransactionType.WITHDRAWAL -> Icons.Default.ArrowUpward
        TransactionType.INTERNAL -> Icons.Default.SwapHoriz
        TransactionType.UNKNOWN -> Icons.Default.HelpOutline
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(imageVector = icon, contentDescription = null, tint = statusColor, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "$typeLabel • ${transaction.accountKey}",
                        color = statusColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
                if (transaction.scope == "BUILDING") {
                    Surface(color = Color(0xFFE8EAF6), shape = RoundedCornerShape(4.dp)) {
                        Text(
                            text = "ساختمان",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            fontSize = 10.sp,
                            color = Color(0xFF1A237E),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

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
                Text(
                    text = transaction.persianDate,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.Gray
                )
            }

            transaction.balance?.let { bal ->
                Text(
                    text = "مانده: ${formatCurrency(bal)} ریال",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.DarkGray
                )
            }

            if (transaction.transferPairInfo.isNotBlank()) {
                Text(
                    text = transaction.transferPairInfo,
                    fontSize = 11.sp,
                    color = Color(0xFF0277BD),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.5.dp)
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    if (transaction.category.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = transaction.category,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = when {
                            transaction.userNote.isNotBlank() -> transaction.userNote
                            transaction.category.isNotBlank() -> ""
                            else -> "برای دسته‌بندی یا ثبت صوتی لمس کنید..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (transaction.userNote.isNotBlank()) Color(0xFF1A237E) else Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Default.Edit, contentDescription = null, tint = Color(0xFF1A237E), modifier = Modifier.size(16.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransactionDetailDialog(
    transaction: BankTransaction,
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onSave: (String, String, Boolean?) -> Unit,
    onMarkAsNotBankSms: (Boolean) -> Unit,
    onAddNewCategory: (String) -> Unit
) {
    val context = LocalContext.current
    var noteText by remember { mutableStateOf(transaction.userNote) }
    var selectedCategory by remember { mutableStateOf(transaction.category) }
    var isInternalTransfer by remember { mutableStateOf(transaction.type == TransactionType.INTERNAL) }
    var showQuickAddCategory by remember { mutableStateOf(false) }
    var quickNewCatName by remember { mutableStateOf("") }
    var showWrongDetectionConfirm by remember { mutableStateOf(false) }

    val sortedCategories = remember(categories, transaction.scope, transaction.type) {
        val isExpense = transaction.type != TransactionType.DEPOSIT
        categories
            .filter { it.scope == "ALL" || it.scope == transaction.scope }
            .sortedByDescending { it.isExpense == isExpense }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim() ?: ""
            if (spoken.isNotEmpty()) {
                noteText = spoken
                val suggested = SmartVoiceCategorizer.suggestCategory(
                    spokenText = spoken,
                    availableCategories = sortedCategories.map { it.name }
                )
                if (suggested != null) {
                    selectedCategory = suggested
                    Toast.makeText(context, "دسته «$suggested» هوشمندانه انتخاب شد", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val startVoiceInput = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "بگویید بابت چه چیزی هزینه یا واریز شد...")
        }
        try {
            voiceLauncher.launch(intent)
        } catch (e: Exception) {
            Toast.makeText(context, "سرویس تبدیل گفتار به متن فعال نیست", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ثبت و دسته‌بندی سند", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                TextButton(
                    onClick = { showWrongDetectionConfirm = !showWrongDetectionConfirm },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("بانکی نیست!", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("${transaction.accountKey} • ${formatCurrency(transaction.amount)} ریال", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Text("تاریخ: ${transaction.persianDate}", fontSize = 11.sp, color = Color.Gray)
                Spacer(modifier = Modifier.height(6.dp))

                Surface(color = Color(0xFFF1F3F4), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(transaction.rawMessage, modifier = Modifier.padding(8.dp), fontSize = 12.sp)
                }

                // گزینه انتقال بین حساب‌های خودی
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                        .clickable { isInternalTransfer = !isInternalTransfer },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isInternalTransfer, onCheckedChange = { isInternalTransfer = it })
                    Text("این تراکنش «انتقال بین حساب‌های خودم» است", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }

                if (!isInternalTransfer) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("سرفصل / دسته‌بندی:", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        TextButton(onClick = { showQuickAddCategory = !showQuickAddCategory }) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("دسته جدید", fontSize = 12.sp)
                        }
                    }

                    if (showQuickAddCategory) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = quickNewCatName,
                                onValueChange = { quickNewCatName = it },
                                placeholder = { Text("نام دسته جدید...", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Button(onClick = {
                                val t = quickNewCatName.trim()
                                if (t.isNotEmpty()) {
                                    onAddNewCategory(t)
                                    selectedCategory = t
                                    quickNewCatName = ""
                                    showQuickAddCategory = false
                                }
                            }) { Text("ثبت") }
                        }
                    }

                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        sortedCategories.forEach { cat ->
                            FilterChip(
                                selected = selectedCategory == cat.name,
                                onClick = { selectedCategory = if (selectedCategory == cat.name) "" else cat.name },
                                label = { Text(cat.name, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                OutlinedTextField(
                    value = noteText,
                    onValueChange = {
                        noteText = it
                        if (!isInternalTransfer && selectedCategory.isEmpty() && it.length >= 3) {
                            SmartVoiceCategorizer.suggestCategory(it, sortedCategories.map { c -> c.name })?.let { sug ->
                                selectedCategory = sug
                            }
                        }
                    },
                    label = { Text("شرح سند (تایپ یا میکروفون)") },
                    placeholder = { Text("مثلاً: شارژ واحد ۴ یا بنزین ماشین...") },
                    trailingIcon = {
                        IconButton(onClick = startVoiceInput) {
                            Icon(Icons.Default.Mic, contentDescription = "ورودی صوتی", tint = Color(0xFF1A237E))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (showWrongDetectionConfirm) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("حذف پیامک اشتباه:", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onMarkAsNotBankSms(false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) { Text("حذف همین مورد", fontSize = 11.sp) }
                                OutlinedButton(
                                    onClick = { onMarkAsNotBankSms(true) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("مسدودی ${transaction.sender}", fontSize = 11.sp) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val transferOverride: Boolean? = if (isInternalTransfer) true else if (transaction.type == TransactionType.INTERNAL) false else null
                onSave(selectedCategory, noteText.trim(), transferOverride)
            }) { Text("ثبت سند") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}

@Composable
fun CategoryManagerDialog(
    categories: List<CategoryEntity>,
    onDismiss: () -> Unit,
    onAddCategory: (String, Boolean, String) -> Unit,
    onDeleteCategory: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }
    var scope by remember { mutableStateOf("PERSONAL") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مدیریت سرفصل‌ها و دسته‌بندی‌ها", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            Column {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("نام سرفصل جدید") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = isExpense, onClick = { isExpense = true })
                    Text("هزینه", fontSize = 12.sp)
                    RadioButton(selected = !isExpense, onClick = { isExpense = false })
                    Text("درآمد", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = scope == "BUILDING",
                        onClick = { scope = if (scope == "BUILDING") "PERSONAL" else "BUILDING" },
                        label = { Text(if (scope == "BUILDING") "مخصوص ساختمان" else "مخصوص شخصی", fontSize = 11.sp) }
                    )
                }
                Button(
                    onClick = {
                        val t = newName.trim()
                        if (t.isNotEmpty()) {
                            onAddCategory(t, isExpense, scope)
                            newName = ""
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("افزودن سرفصل") }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(categories) { cat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val scopeLabel = when (cat.scope) {
                                "BUILDING" -> "ساختمان"
                                "PERSONAL" -> "شخصی"
                                else -> "عمومی"
                            }
                            Text("${cat.name} ($scopeLabel)", fontSize = 13.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onDeleteCategory(cat.name) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
}

@Composable
fun IgnoredItemsDialog(
    ignoredItems: List<IgnoredItemEntity>,
    onDismiss: () -> Unit,
    onRestoreItem: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("موارد حذف‌شده و مسدودی‌ها", fontWeight = FontWeight.Bold, fontSize = 16.sp) },
        text = {
            if (ignoredItems.isEmpty()) {
                Text("موردی وجود ندارد.")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(ignoredItems) { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(item.label.ifEmpty { item.keyId }, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            TextButton(onClick = { onRestoreItem(item.keyId) }) { Text("بازگردانی", fontSize = 11.sp) }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("بستن") } }
    )
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

        val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply { priority = 999 }
        ContextCompat.registerReceiver(context, receiver, intentFilter, ContextCompat.RECEIVER_EXPORTED)

        onDispose { context.unregisterReceiver(receiver) }
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
        Text("دسترسی به پیامک‌ها و اعلان‌ها", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "برای استخراج خودکار اسناد بانکی، تفکیک حساب شخصی و ساختمان، کشف مغایرت‌ها و رسم نمودارها، مجوزهای مورد نیاز را تایید کنید.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission) { Text("اعطای مجوزهای مورد نیاز") }
    }
}
