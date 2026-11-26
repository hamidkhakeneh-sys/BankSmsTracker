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
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
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
            var fontScale by remember { mutableFloatStateOf(1.0f) }

            val dynamicTypography = remember(fontScale) {
                Typography(
                    titleLarge = TextStyle(fontSize = (20 * fontScale).sp, fontWeight = FontWeight.Bold),
                    titleMedium = TextStyle(fontSize = (16 * fontScale).sp, fontWeight = FontWeight.Bold),
                    titleSmall = TextStyle(fontSize = (14 * fontScale).sp, fontWeight = FontWeight.SemiBold),
                    bodyLarge = TextStyle(fontSize = (15 * fontScale).sp),
                    bodyMedium = TextStyle(fontSize = (13 * fontScale).sp),
                    bodySmall = TextStyle(fontSize = (12 * fontScale).sp),
                    labelLarge = TextStyle(fontSize = (13 * fontScale).sp, fontWeight = FontWeight.Medium),
                    labelMedium = TextStyle(fontSize = (11 * fontScale).sp),
                    labelSmall = TextStyle(fontSize = (10 * fontScale).sp)
                )
            }

            MaterialTheme(typography = dynamicTypography) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = Color(0xFFF3F5F9)
                    ) {
                        HolooSmsAccountingApp(
                            openDialogForSmsId = notificationSmsId,
                            onDialogOpenedFromNotification = { notificationSmsId = null },
                            fontScale = fontScale,
                            onFontScaleLoaded = { fontScale = it },
                            onFontScaleChanged = { fontScale = it }
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
    DEPOSIT("واریز"),
    WITHDRAWAL("برداشت"),
    INTERNAL("انتقال داخلی")
}

enum class ReportPeriodMode(val title: String) {
    ALL("کل دوره"),
    YEARLY("سالانه"),
    MONTHLY("ماهانه (ماه خاص)"),
    CUSTOM_RANGE("تاریخ تا تاریخ")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HolooSmsAccountingApp(
    openDialogForSmsId: String? = null,
    onDialogOpenedFromNotification: () -> Unit = {},
    fontScale: Float = 1.0f,
    onFontScaleLoaded: (Float) -> Unit = {},
    onFontScaleChanged: (Float) -> Unit = {}
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

    var allTransactions by remember { mutableStateOf<List<BankTransaction>>(emptyList()) }
    var accountProfiles by remember { mutableStateOf<List<AccountProfileEntity>>(emptyList()) }
    var categories by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    var ignoredItems by remember { mutableStateOf<List<IgnoredItemEntity>>(emptyList()) }
    var payerMappings by remember { mutableStateOf<List<PayerUnitMappingEntity>>(emptyList()) }
    var customScopes by remember { mutableStateOf<List<String>>(listOf("شخصی", "مدیریت ساختمان")) }

    var isLoading by remember { mutableStateOf(false) }

    var bottomTabIndex by remember { mutableIntStateOf(0) }
    var selectedScope by remember { mutableStateOf("همه صندوق‌ها") }

    var selectedTransaction by remember { mutableStateOf<BankTransaction?>(null) }
    var showCategoryManager by remember { mutableStateOf(false) }

    var searchQuery by remember { mutableStateOf("") }
    var selectedTypeFilter by remember { mutableStateOf(TypeFilter.ALL) }
    var selectedAccountFilter by remember { mutableStateOf("همه حساب‌ها") }
    var selectedCategoryFilter by remember { mutableStateOf("همه دسته‌ها") }

    val discrepancies by remember(allTransactions) {
        derivedStateOf { IranBankSmsParser.findDiscrepancies(allTransactions) }
    }

    val scopedTransactions by remember(allTransactions, selectedScope) {
        derivedStateOf {
            if (selectedScope == "همه صندوق‌ها") allTransactions
            else allTransactions.filter { it.scope == selectedScope }
        }
    }

    val scopedDiscrepancies by remember(discrepancies, accountProfiles, selectedScope) {
        derivedStateOf {
            if (selectedScope == "همه صندوق‌ها") discrepancies
            else {
                val scopeMap = accountProfiles.associate { it.accountKey to it.scope }
                discrepancies.filter { scopeMap[it.accountKey] == selectedScope }
            }
        }
    }

    val availableAccounts by remember(scopedTransactions) {
        derivedStateOf {
            listOf("همه حساب‌ها") + scopedTransactions.map { it.accountKey }.distinct().sorted()
        }
    }

    val availableCategoryFilters by remember(categories) {
        derivedStateOf {
            listOf("همه دسته‌ها", "دسته‌بندی نشده") + categories.map { it.name }
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
                        tx.payerIdentifier.contains(query, ignoreCase = true) ||
                        tx.rawMessage.contains(query, ignoreCase = true) ||
                        tx.amount.toString().contains(query)

                matchesType && matchesAcc && matchesCat && matchesQuery
            }
        }
    }

    val fullReloadFromSms: () -> Unit = {
        coroutineScope.launch {
            isLoading = true
            withContext(Dispatchers.IO) {
                var currentCats = appDao.getAllCategories()
                if (currentCats.isEmpty()) {
                    appDao.insertCategories(AppDatabase.DEFAULT_CATEGORIES)
                    currentCats = appDao.getAllCategories()
                }
                categories = currentCats

                val settingsMap = appDao.getAllSettings().associate { it.settingKey to it.settingValue }
                val savedScale = settingsMap["font_scale"]?.toFloatOrNull() ?: 1.0f
                withContext(Dispatchers.Main) { onFontScaleLoaded(savedScale) }

                val savedScopesStr = settingsMap["custom_scopes"]
                if (!savedScopesStr.isNullOrBlank()) {
                    customScopes = savedScopesStr.split("|").filter { it.isNotBlank() }
                }

                val ignoredList = appDao.getAllIgnored()
                ignoredItems = ignoredList
                val ignoredIds = ignoredList.filter { !it.isSenderBlocked }.map { it.keyId }.toSet()
                val blockedSenders = ignoredList.filter { it.isSenderBlocked }.map { it.keyId }.toSet()

                val mappingsList = appDao.getAllPayerMappings()
                payerMappings = mappingsList
                val mappingsMap = mappingsList.associate { it.payerIdentifier to it.unitTitle }

                val savedNotesMap = appDao.getAllNotes().associateBy { it.smsId }
                val existingProfilesMap = appDao.getAllAccountProfiles().associate {
                    val normalizedScope = when (it.scope) {
                        "PERSONAL" -> "شخصی"
                        "BUILDING" -> "مدیریت ساختمان"
                        else -> it.scope
                    }
                    it.accountKey to it.copy(scope = normalizedScope)
                }

                val importedList = appDao.getAllImportedRecords()

                val (txList, updatedProfiles) = readAllTransactionsFast(
                    context = context,
                    savedNotes = savedNotesMap,
                    accountProfiles = existingProfilesMap,
                    payerMappings = mappingsMap,
                    importedRecords = importedList,
                    ignoredSmsIds = ignoredIds,
                    blockedSenders = blockedSenders
                )

                updatedProfiles.forEach { prof ->
                    if (!existingProfilesMap.containsKey(prof.accountKey)) {
                        appDao.saveAccountProfile(prof)
                    }
                }

                accountProfiles = updatedProfiles
                allTransactions = txList
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
        if (hasSmsPermission) fullReloadFromSms()
    }

    LaunchedEffect(hasSmsPermission) {
        if (hasSmsPermission && allTransactions.isEmpty()) {
            fullReloadFromSms()
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
                fullReloadFromSms()
            }
        }
    )

    Scaffold(
        topBar = {
            Column(modifier = Modifier.background(Color(0xFF0F2942))) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AccountBalanceWallet,
                                contentDescription = null,
                                tint = Color(0xFF64B5F6),
                                modifier = Modifier.size(26.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("حسابداری هوشمند", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text("مدیریت شخصی و ساختمان", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB0BEC5))
                            }
                        }
                    },
                    actions = {
                        if (hasSmsPermission) {
                            IconButton(onClick = { showCategoryManager = true }) {
                                Icon(Icons.Rounded.Category, contentDescription = "سرفصل‌ها", tint = Color.White)
                            }
                            IconButton(
                                onClick = { CsvExporter.exportAndShareCsv(context, journalTransactions, selectedScope) },
                                enabled = journalTransactions.isNotEmpty()
                            ) {
                                Icon(Icons.Rounded.IosShare, contentDescription = "خروجی اکسل", tint = Color.White)
                            }
                            IconButton(onClick = { fullReloadFromSms() }) {
                                Icon(Icons.Rounded.Sync, contentDescription = "بروزرسانی پیامک‌ها", tint = Color.White)
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF0F2942))
                )

                if (hasSmsPermission) {
                    val allScopes = listOf("همه صندوق‌ها") + customScopes
                    LazyRow(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(allScopes) { scopeName ->
                            val isSelected = selectedScope == scopeName
                            Surface(
                                modifier = Modifier.clickable {
                                    selectedScope = scopeName
                                    selectedAccountFilter = "همه حساب‌ها"
                                    selectedCategoryFilter = "همه دسته‌ها"
                                },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) Color(0xFF42A5F5) else Color(0xFF1C3B59)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (scopeName) {
                                            "مدیریت ساختمان" -> Icons.Rounded.Apartment
                                            "شخصی" -> Icons.Rounded.Person
                                            else -> Icons.Rounded.FolderShared
                                        },
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(15.dp)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = scopeName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = Color.White
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
                        icon = { Icon(Icons.Rounded.PieChart, contentDescription = null) },
                        label = { Text("داشبورد", style = MaterialTheme.typography.labelSmall) }
                    )
                    NavigationBarItem(
                        selected = bottomTabIndex == 1,
                        onClick = { bottomTabIndex = 1 },
                        icon = { Icon(Icons.Rounded.ReceiptLong, contentDescription = null) },
                        label = { Text("تراکنش‌ها", style = MaterialTheme.typography.labelSmall) }
                    )
                    NavigationBarItem(
                        selected = bottomTabIndex == 2,
                        onClick = { bottomTabIndex = 2 },
                        icon = { Icon(Icons.Rounded.Analytics, contentDescription = null) },
                        label = { Text("گزارشات", style = MaterialTheme.typography.labelSmall) }
                    )
                    NavigationBarItem(
                        selected = bottomTabIndex == 3,
                        onClick = { bottomTabIndex = 3 },
                        icon = {
                            BadgedBox(
                                badge = {
                                    if (scopedDiscrepancies.isNotEmpty()) {
                                        Badge { Text("${scopedDiscrepancies.size}") }
                                    }
                                }
                            ) {
                                Icon(Icons.Rounded.Tune, contentDescription = null)
                            }
                        },
                        label = { Text("تنظیمات", style = MaterialTheme.typography.labelSmall) }
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
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        CircularProgressIndicator(color = Color(0xFF0F2942))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("در حال پردازش اسناد بانکی...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    when (bottomTabIndex) {
                        0 -> DashboardScreen(
                            transactions = scopedTransactions,
                            discrepancyCount = scopedDiscrepancies.size,
                            scopeTitle = selectedScope,
                            onNavigateToDiscrepancies = { bottomTabIndex = 3 }
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
                        2 -> AdvancedReportsScreen(
                            transactions = scopedTransactions,
                            scopeTitle = selectedScope,
                            onExportCsv = { listToExport ->
                                CsvExporter.exportAndShareCsv(context, listToExport, selectedScope)
                            }
                        )
                        3 -> SettingsAndReconciliationScreen(
                            accountProfiles = accountProfiles,
                            transactions = allTransactions,
                            discrepancies = scopedDiscrepancies,
                            customScopes = customScopes,
                            payerMappings = payerMappings,
                            ignoredItems = ignoredItems,
                            fontScale = fontScale,
                            onFontScaleChange = { newScale ->
                                onFontScaleChanged(newScale)
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveSetting(AppSettingEntity("font_scale", newScale.toString()))
                                }
                            },
                            onAddCustomScope = { newScopeName ->
                                if (newScopeName.isNotBlank() && !customScopes.contains(newScopeName)) {
                                    val updated = customScopes + newScopeName
                                    customScopes = updated
                                    coroutineScope.launch(Dispatchers.IO) {
                                        appDao.saveSetting(AppSettingEntity("custom_scopes", updated.joinToString("|")))
                                    }
                                }
                            },
                            onChangeAccountScope = { profile, newScope ->
                                accountProfiles = accountProfiles.map {
                                    if (it.accountKey == profile.accountKey) it.copy(scope = newScope) else it
                                }
                                allTransactions = allTransactions.map {
                                    if (it.accountKey == profile.accountKey) it.copy(scope = newScope) else it
                                }
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveAccountProfile(profile.copy(scope = newScope))
                                }
                            },
                            onImportExcelRecords = { newRecords ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.insertImportedRecords(newRecords)
                                    fullReloadFromSms()
                                }
                            },
                            onDeletePayerMapping = { payerId ->
                                payerMappings = payerMappings.filterNot { it.payerIdentifier == payerId }
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.deletePayerMapping(payerId)
                                }
                            },
                            onRestoreIgnored = { keyId ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.removeIgnored(keyId)
                                    fullReloadFromSms()
                                }
                            }
                        )
                    }
                }
            }
        }

        selectedTransaction?.let { currentTx ->
            TransactionDetailDialog(
                transaction = currentTx,
                categories = categories,
                onDismiss = { selectedTransaction = null },
                onSave = { newCategory, newNote, transferOverride, assignUnitToPayerCard ->
                    val targetPayer = currentTx.payerIdentifier
                    val shouldAutoPropagate = assignUnitToPayerCard && targetPayer.isNotEmpty() && newNote.isNotBlank()

                    allTransactions = allTransactions.map { tx ->
                        when {
                            tx.id == currentTx.id -> {
                                val updatedType = when {
                                    transferOverride == true -> TransactionType.INTERNAL
                                    transferOverride == false && tx.type == TransactionType.INTERNAL ->
                                        if ((tx.balance ?: 0L) > 0) TransactionType.DEPOSIT else TransactionType.WITHDRAWAL
                                    else -> tx.type
                                }
                                tx.copy(
                                    userNote = newNote,
                                    category = newCategory,
                                    type = updatedType
                                )
                            }
                            shouldAutoPropagate && tx.payerIdentifier == targetPayer -> {
                                tx.copy(
                                    userNote = newNote,
                                    category = newCategory.ifBlank { "شارژ ماهیانه واحدها" }
                                )
                            }
                            else -> tx
                        }
                    }

                    if (shouldAutoPropagate) {
                        val updatedMapList = payerMappings.filterNot { it.payerIdentifier == targetPayer } +
                                PayerUnitMappingEntity(targetPayer, newNote)
                        payerMappings = updatedMapList
                    }

                    selectedTransaction = null

                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.saveNote(
                            TransactionNoteEntity(
                                smsId = currentTx.id,
                                note = newNote,
                                category = newCategory,
                                isInternalTransferOverride = transferOverride
                            )
                        )
                        if (shouldAutoPropagate) {
                            appDao.savePayerMapping(PayerUnitMappingEntity(targetPayer, newNote))
                            val matchingIds = allTransactions.filter { it.payerIdentifier == targetPayer && it.id != currentTx.id }
                            val batchNotes = matchingIds.map {
                                TransactionNoteEntity(
                                    smsId = it.id,
                                    note = newNote,
                                    category = newCategory.ifBlank { "شارژ ماهیانه واحدها" }
                                )
                            }
                            if (batchNotes.isNotEmpty()) {
                                appDao.saveNotesBatch(batchNotes)
                            }
                        }
                    }

                    if (shouldAutoPropagate) {
                        Toast.makeText(context, "واحد «$newNote» برای تمام واریزهای این کارت اعمال شد", Toast.LENGTH_SHORT).show()
                    }
                },
                onMarkAsNotBankSms = { blockEntireSender ->
                    if (blockEntireSender) {
                        allTransactions = allTransactions.filterNot { it.sender == currentTx.sender }
                    } else {
                        allTransactions = allTransactions.filterNot { it.id == currentTx.id }
                    }
                    selectedTransaction = null

                    coroutineScope.launch(Dispatchers.IO) {
                        if (blockEntireSender) {
                            appDao.addIgnored(IgnoredItemEntity(currentTx.sender, true, "سرشماره: ${currentTx.sender}"))
                        } else {
                            appDao.addIgnored(IgnoredItemEntity(currentTx.id, false, "پیامک ${currentTx.sender} (${currentTx.persianDate})"))
                        }
                        ignoredItems = appDao.getAllIgnored()
                    }
                    Toast.makeText(context, "بلافاصله از لیست و آمار حذف شد", Toast.LENGTH_SHORT).show()
                },
                onAddNewCategory = { newCatName ->
                    val isExp = currentTx.type != TransactionType.DEPOSIT
                    val newCat = CategoryEntity(newCatName, isExp, "ALL")
                    categories = categories + newCat
                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.addCategory(newCat)
                    }
                }
            )
        }

        if (showCategoryManager) {
            CategoryManagerDialog(
                categories = categories,
                onDismiss = { showCategoryManager = false },
                onAddCategory = { name, isExpense ->
                    val newCat = CategoryEntity(name, isExpense, "ALL")
                    categories = categories + newCat
                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.addCategory(newCat)
                    }
                },
                onDeleteCategory = { name ->
                    categories = categories.filterNot { it.name == name }
                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.deleteCategory(name)
                    }
                }
            )
        }
    }
}

@Composable
fun DashboardScreen(
    transactions: List<BankTransaction>,
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
        if (discrepancyCount > 0) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onNavigateToDiscrepancies() },
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.WarningAmber, contentDescription = null, tint = Color(0xFFE65100))
                    Spacer(modifier = Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "هشدار مغایرت حسابداری ($discrepancyCount مورد)",
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFE65100),
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "برای بررسی یا بارگذاری فایل اکسل بانک جهت رفع مغایرت لمس کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5D4037)
                        )
                    }
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = Color(0xFFE65100))
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountBalance, contentDescription = null, tint = Color(0xFF0F2942), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تراز مالی ($scopeTitle)", style = MaterialTheme.typography.titleMedium, color = Color(0xFF0F2942))
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("کل واریزی‌ها (بستانکار)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(
                            text = "+${formatCurrency(totalDeposit)} ریال",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF2E7D32)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("کل برداشت‌ها (بدهکار)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(
                            text = "-${formatCurrency(totalWithdrawal)} ریال",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFC62828)
                        )
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("جریان خالص نقدینگی:", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    val net = totalDeposit - totalWithdrawal
                    Text(
                        text = "${if (net >= 0) "+" else ""}${formatCurrency(net)} ریال",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (net >= 0) Color(0xFF1565C0) else Color(0xFFC62828)
                    )
                }
                if (internalTransferSum > 0) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "انتقال بین حساب‌های خودی: ${formatCurrency(internalTransferSum)} ریال",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF0277BD)
                    )
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = chartExpenseMode,
                onClick = { chartExpenseMode = true },
                label = { Text("نمودار هزینه‌ها") },
                leadingIcon = { Icon(Icons.Rounded.TrendingDown, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = !chartExpenseMode,
                onClick = { chartExpenseMode = false },
                label = { Text("نمودار درآمدها") },
                leadingIcon = { Icon(Icons.Rounded.TrendingUp, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f)
            )
        }

        InteractiveCategoryDonutChart(transactions = transactions, isExpenseMode = chartExpenseMode)
        MonthlyComparisonBarChart(transactions = transactions)
    }
}

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
                placeholder = { Text("جستجو در واحد، دسته، کارت، حساب یا پیامک...", style = MaterialTheme.typography.bodySmall) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = null)
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
                        label = { Text(filter.title, style = MaterialTheme.typography.labelMedium) }
                    )
                }
            }

            if (accounts.size > 1) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(accounts) { acc ->
                        FilterChip(
                            selected = selectedAccount == acc,
                            onClick = { onAccountChange(acc) },
                            label = { Text(acc, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(categoryFilters) { cat ->
                    FilterChip(
                        selected = selectedCategory == cat,
                        onClick = { onCategoryChange(cat) },
                        label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
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

@Composable
fun AdvancedReportsScreen(
    transactions: List<BankTransaction>,
    scopeTitle: String,
    onExportCsv: (List<BankTransaction>) -> Unit
) {
    var periodMode by remember { mutableStateOf(ReportPeriodMode.ALL) }

    val availableYears = remember(transactions) {
        transactions.map { it.persianYear }.distinct().sortedDescending()
    }
    val availableMonths = remember(transactions) {
        transactions.map { it.persianMonthYear }.distinct().sortedDescending()
    }

    var selectedYear by remember(availableYears) { mutableStateOf(availableYears.firstOrNull() ?: "1405") }
    var selectedMonth by remember(availableMonths) { mutableStateOf(availableMonths.firstOrNull() ?: "1405/09") }
    var fromDateText by remember { mutableStateOf("1405/01/01") }
    var toDateText by remember { mutableStateOf("1405/12/29") }

    val reportTransactions by remember(
        transactions, periodMode, selectedYear, selectedMonth, fromDateText, toDateText
    ) {
        derivedStateOf {
            when (periodMode) {
                ReportPeriodMode.ALL -> transactions
                ReportPeriodMode.YEARLY -> transactions.filter { it.persianYear == selectedYear }
                ReportPeriodMode.MONTHLY -> transactions.filter { it.persianMonthYear == selectedMonth }
                ReportPeriodMode.CUSTOM_RANGE -> {
                    val f = IranBankSmsParser.normalizePersian(fromDateText.trim())
                    val t = IranBankSmsParser.normalizePersian(toDateText.trim())
                    transactions.filter { it.persianDateOnly >= f && it.persianDateOnly <= t }
                }
            }
        }
    }

    val totalDep = remember(reportTransactions) {
        reportTransactions.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
    }
    val totalWit = remember(reportTransactions) {
        reportTransactions.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
    }

    val expenseByCategory = remember(reportTransactions) {
        reportTransactions
            .filter { it.type == TransactionType.WITHDRAWAL }
            .groupBy { it.category.ifBlank { "دسته‌بندی نشده" } }
            .mapValues { it.value.sumOf { tx -> tx.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

    val incomeByCategory = remember(reportTransactions) {
        reportTransactions
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(14.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("بازه زمانی گزارش ($scopeTitle)", style = MaterialTheme.typography.titleSmall, color = Color(0xFF0F2942))
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(ReportPeriodMode.entries) { mode ->
                            FilterChip(
                                selected = periodMode == mode,
                                onClick = { periodMode = mode },
                                label = { Text(mode.title, style = MaterialTheme.typography.labelMedium) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    when (periodMode) {
                        ReportPeriodMode.ALL -> {
                            Text("نمایش گزارش تجمیعی از ابتدا تا امروز", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        ReportPeriodMode.YEARLY -> {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(availableYears) { yr ->
                                    FilterChip(
                                        selected = selectedYear == yr,
                                        onClick = { selectedYear = yr },
                                        label = { Text("سال $yr") }
                                    )
                                }
                            }
                        }
                        ReportPeriodMode.MONTHLY -> {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(availableMonths) { mo ->
                                    FilterChip(
                                        selected = selectedMonth == mo,
                                        onClick = { selectedMonth = mo },
                                        label = { Text(mo) }
                                    )
                                }
                            }
                        }
                        ReportPeriodMode.CUSTOM_RANGE -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = fromDateText,
                                    onValueChange = { fromDateText = it },
                                    label = { Text("از تاریخ (مثلاً 1405/08/01)", style = MaterialTheme.typography.labelSmall) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = toDateText,
                                    onValueChange = { toDateText = it },
                                    label = { Text("تا تاریخ (مثلاً 1405/09/30)", style = MaterialTheme.typography.labelSmall) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("واریز دوره: +${formatCurrency(totalDep)}", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("برداشت دوره: -${formatCurrency(totalWit)}", color = Color(0xFFC62828), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Button(
                onClick = { onExportCsv(reportTransactions) },
                enabled = reportTransactions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F2942))
            ) {
                Icon(Icons.Rounded.FileDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("خروجی اکسل این بازه (${reportTransactions.size} سند)")
            }
        }

        item {
            Text("هزینه‌های دوره به تفکیک سرفصل", style = MaterialTheme.typography.titleSmall, color = Color(0xFFC62828))
        }
        items(expenseByCategory) { (cat, sum) ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(cat, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("${formatCurrency(sum)} ریال", color = Color(0xFFC62828), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Text("درآمدها و واریزی‌های دوره به تفکیک سرفصل", style = MaterialTheme.typography.titleSmall, color = Color(0xFF2E7D32))
        }
        items(incomeByCategory) { (cat, sum) ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(cat, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("${formatCurrency(sum)} ریال", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
fun SettingsAndReconciliationScreen(
    accountProfiles: List<AccountProfileEntity>,
    transactions: List<BankTransaction>,
    discrepancies: List<AccountDiscrepancy>,
    customScopes: List<String>,
    payerMappings: List<PayerUnitMappingEntity>,
    ignoredItems: List<IgnoredItemEntity>,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    onAddCustomScope: (String) -> Unit,
    onChangeAccountScope: (AccountProfileEntity, String) -> Unit,
    onImportExcelRecords: (List<ImportedRecordEntity>) -> Unit,
    onDeletePayerMapping: (String) -> Unit,
    onRestoreIgnored: (String) -> Unit
) {
    val context = LocalContext.current
    var newScopeInput by remember { mutableStateOf("") }
    var selectedAccountForExcel by remember(accountProfiles) {
        mutableStateOf(accountProfiles.firstOrNull()?.accountKey ?: "")
    }

    val excelPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null && selectedAccountForExcel.isNotEmpty()) {
            try {
                val imported = ExcelImporter.parseBankStatementCsv(
                    context = context,
                    uri = uri,
                    targetAccountKey = selectedAccountForExcel,
                    existingTransactions = transactions
                )
                if (imported.isEmpty()) {
                    Toast.makeText(context, "تمام رکوردهای این فایل اکسل قبلاً در پیامک‌ها وجود داشتند!", Toast.LENGTH_LONG).show()
                } else {
                    onImportExcelRecords(imported)
                    Toast.makeText(context, "${imported.size} تراکنش جاافتاده از فایل اکسل اضافه و مغایرت اصلاح شد!", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "خطا در خواندن فایل CSV: مطمئن شوید فایل با فرمت CSV ذخیره شده است", Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.FormatSize, contentDescription = null, tint = Color(0xFF0F2942))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("تنظیم اندازه فونت برنامه", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val scales = listOf(0.85f to "کوچک", 1.0f to "معمولی", 1.15f to "بزرگ", 1.3f to "خیلی بزرگ")
                        scales.forEach { (sc, label) ->
                            FilterChip(
                                selected = kotlin.math.abs(fontScale - sc) < 0.05f,
                                onClick = { onFontScaleChange(sc) },
                                label = { Text(label, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AccountBalance, contentDescription = null, tint = Color(0xFF0F2942))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مدیریت صندوق‌ها و تخصیص حساب‌های بانکی", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("در صورت نیاز صندوق جدید بسازید و مشخص کنید هر حساب مربوط به کدام بخش است:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

                    Spacer(modifier = Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = newScopeInput,
                            onValueChange = { newScopeInput = it },
                            placeholder = { Text("نام صندوق جدید (مثلاً: مغازه، پروژه...)", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = {
                            onAddCustomScope(newScopeInput.trim())
                            newScopeInput = ""
                        }) { Text("افزودن") }
                    }

                    Spacer(modifier = Modifier.height(10.dp))
                    accountProfiles.forEach { profile ->
                        val latestBal = transactions.firstOrNull { it.accountKey == profile.accountKey && it.balance != null }?.balance
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Text(profile.accountKey, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                        if (latestBal != null) {
                            Text("موجودی: ${formatCurrency(latestBal)} ریال", style = MaterialTheme.typography.labelSmall, color = Color(0xFF2E7D32))
                        }
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(customScopes) { sc ->
                                FilterChip(
                                    selected = profile.scope == sc,
                                    onClick = { onChangeAccountScope(profile, sc) },
                                    label = { Text(sc, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(14.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, tint = Color(0xFF1565C0))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("بارگذاری فایل اکسل (CSV) گردش حساب بانک", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "فایل صورت‌حساب CSV دریافتی از اینترنت‌بانک را انتخاب کنید تا تراکنش‌های جاافتاده اضافه و مغایرت‌ها برطرف شوند:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    if (accountProfiles.isNotEmpty()) {
                        Text("انتخاب حساب مقصد:", style = MaterialTheme.typography.labelSmall)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(accountProfiles) { prof ->
                                FilterChip(
                                    selected = selectedAccountForExcel == prof.accountKey,
                                    onClick = { selectedAccountForExcel = prof.accountKey },
                                    label = { Text(prof.accountKey, style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = { excelPickerLauncher.launch("text/*") },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                        ) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("انتخاب فایل اکسل (CSV) و رفع مغایرت")
                        }
                    }
                }
            }
        }

        item {
            Text("مغایرت‌های مانده حساب (${discrepancies.size})", style = MaterialTheme.typography.titleSmall, color = Color(0xFFC62828))
        }
        if (discrepancies.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE8F5E9))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF2E7D32))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("تمام حساب‌ها تراز هستند و هیچ مغایرتی وجود ندارد.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else {
            items(discrepancies) { disc ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text("${disc.accountKey} (${disc.date})", fontWeight = FontWeight.Bold, color = Color(0xFFB71C1C), style = MaterialTheme.typography.bodySmall)
                        Text(disc.description, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        if (payerMappings.isNotEmpty()) {
            item {
                Text("کارت‌های متصل‌شده به واحدهای ساختمان (${payerMappings.size})", style = MaterialTheme.typography.titleSmall, color = Color(0xFF0F2942))
            }
            items(payerMappings) { mapItem ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mapItem.unitTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                            Text("شماره کارت/حساب: ${mapItem.payerIdentifier}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                        }
                        IconButton(onClick = { onDeletePayerMapping(mapItem.payerIdentifier) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.Red)
                        }
                    }
                }
            }
        }

        if (ignoredItems.isNotEmpty()) {
            item {
                Text("پیامک‌ها و سرشماره‌های مسدودشده (${ignoredItems.size})", style = MaterialTheme.typography.titleSmall)
            }
            items(ignoredItems) { item ->
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                    Row(modifier = Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(item.label.ifEmpty { item.keyId }, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { onRestoreIgnored(item.keyId) }) { Text("بازگردانی") }
                    }
                }
            }
        }
    }
}

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
        TransactionType.DEPOSIT -> Icons.Rounded.SouthWest
        TransactionType.WITHDRAWAL -> Icons.Rounded.NorthEast
        TransactionType.INTERNAL -> Icons.Rounded.SwapHoriz
        TransactionType.UNKNOWN -> Icons.Rounded.HelpOutline
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
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                Surface(
                    color = if (transaction.scope == "مدیریت ساختمان") Color(0xFFE8EAF6) else Color(0xFFE0F2F1),
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Text(
                        text = transaction.scope,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF0F2942),
                        fontWeight = FontWeight.Bold
                    )
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

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                transaction.balance?.let { bal ->
                    Text(text = "مانده: ${formatCurrency(bal)} ریال", style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
                }
                if (transaction.payerIdentifier.isNotEmpty()) {
                    Text(text = "کارت/حساب واریزکننده: ${transaction.payerIdentifier}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1565C0))
                }
            }

            if (transaction.transferPairInfo.isNotBlank()) {
                Text(text = transaction.transferPairInfo, style = MaterialTheme.typography.labelSmall, color = Color(0xFF0277BD))
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
                        Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(6.dp)) {
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
                            else -> "برای دسته‌بندی یا ثبت واحد لمس کنید..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (transaction.userNote.isNotBlank()) Color(0xFF0F2942) else Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(Icons.Rounded.EditNote, contentDescription = null, tint = Color(0xFF0F2942), modifier = Modifier.size(18.dp))
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
    onSave: (String, String, Boolean?, Boolean) -> Unit,
    onMarkAsNotBankSms: (Boolean) -> Unit,
    onAddNewCategory: (String) -> Unit
) {
    val context = LocalContext.current
    var noteText by remember { mutableStateOf(transaction.userNote) }
    var selectedCategory by remember { mutableStateOf(transaction.category) }
    var isInternalTransfer by remember { mutableStateOf(transaction.type == TransactionType.INTERNAL) }
    var assignUnitToAllPayerDeposits by remember { mutableStateOf(transaction.payerIdentifier.isNotEmpty() && transaction.type == TransactionType.DEPOSIT) }
    var showQuickAddCategory by remember { mutableStateOf(false) }
    var quickNewCatName by remember { mutableStateOf("") }
    var showWrongDetectionConfirm by remember { mutableStateOf(false) }

    val sortedCategories = remember(categories, transaction.type) {
        val isExpense = transaction.type != TransactionType.DEPOSIT
        categories.sortedByDescending { it.isExpense == isExpense }
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
                    Toast.makeText(context, "دسته «$suggested» انتخاب شد", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val startVoiceInput = {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "بگویید بابت چه چیزی یا کدام واحد...")
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
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("ثبت و دسته‌بندی سند", style = MaterialTheme.typography.titleMedium)
                TextButton(
                    onClick = { showWrongDetectionConfirm = !showWrongDetectionConfirm },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Rounded.DeleteForever, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text("بانکی نیست!", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("${transaction.accountKey} • ${formatCurrency(transaction.amount)} ریال", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                Text("تاریخ: ${transaction.persianDate}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                Spacer(modifier = Modifier.height(6.dp))

                Surface(color = Color(0xFFF1F3F4), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text(transaction.rawMessage, modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.bodySmall)
                }

                if (transaction.payerIdentifier.isNotEmpty() && transaction.type == TransactionType.DEPOSIT) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(color = Color(0xFFE3F2FD), shape = RoundedCornerShape(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .padding(8.dp)
                                .clickable { assignUnitToAllPayerDeposits = !assignUnitToAllPayerDeposits },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = assignUnitToAllPayerDeposits, onCheckedChange = { assignUnitToAllPayerDeposits = it })
                            Text(
                                text = "اتصال خودکار شماره کارت/حساب «${transaction.payerIdentifier}» به این واحد برای تمام واریزهای قبلی و بعدی",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF0D47A1)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clickable { isInternalTransfer = !isInternalTransfer },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = isInternalTransfer, onCheckedChange = { isInternalTransfer = it })
                    Text("این تراکنش «انتقال بین حساب‌های خودم» است", style = MaterialTheme.typography.bodySmall)
                }

                if (!isInternalTransfer) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("سرفصل / دسته‌بندی:", style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { showQuickAddCategory = !showQuickAddCategory }) {
                            Icon(Icons.Rounded.AddCircleOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                            Text("دسته جدید", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    if (showQuickAddCategory) {
                        Row(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = quickNewCatName,
                                onValueChange = { quickNewCatName = it },
                                placeholder = { Text("نام دسته جدید...", style = MaterialTheme.typography.bodySmall) },
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
                                label = { Text(cat.name, style = MaterialTheme.typography.labelSmall) }
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
                    label = { Text(if (transaction.scope == "مدیریت ساختمان") "نام واحد یا شرح هزینه" else "شرح سند (تایپ یا میکروفون)") },
                    placeholder = { Text("مثلاً: واحد ۴ - آقای رضایی یا بنزین...") },
                    trailingIcon = {
                        IconButton(onClick = startVoiceInput) {
                            Icon(Icons.Rounded.Mic, contentDescription = "ورودی صوتی", tint = Color(0xFF0F2942))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (showWrongDetectionConfirm) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(8.dp)) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text("حذف پیامک اشتباه:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(
                                    onClick = { onMarkAsNotBankSms(false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f)
                                ) { Text("حذف همین مورد", style = MaterialTheme.typography.labelSmall) }
                                OutlinedButton(
                                    onClick = { onMarkAsNotBankSms(true) },
                                    modifier = Modifier.weight(1f)
                                ) { Text("مسدودی ${transaction.sender}", style = MaterialTheme.typography.labelSmall) }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val transferOverride: Boolean? = if (isInternalTransfer) true else if (transaction.type == TransactionType.INTERNAL) false else null
                onSave(selectedCategory, noteText.trim(), transferOverride, assignUnitToAllPayerDeposits)
            }) { Text("ثبت آنی") }
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
    onAddCategory: (String, Boolean) -> Unit,
    onDeleteCategory: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مدیریت سرفصل‌ها و دسته‌بندی‌ها", style = MaterialTheme.typography.titleMedium) },
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
                    Text("هزینه", style = MaterialTheme.typography.bodySmall)
                    RadioButton(selected = !isExpense, onClick = { isExpense = false })
                    Text("درآمد", style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.weight(1f))
                    Button(onClick = {
                        val t = newName.trim()
                        if (t.isNotEmpty()) {
                            onAddCategory(t, isExpense)
                            newName = ""
                        }
                    }) { Text("افزودن") }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(categories) { cat ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${cat.name} (${if (cat.isExpense) "هزینه" else "درآمد"})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onDeleteCategory(cat.name) }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
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
        Icon(Icons.Rounded.Security, contentDescription = null, tint = Color(0xFF0F2942), modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("دسترسی به پیامک‌ها و اعلان‌ها", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "برای استخراج خودکار اسناد بانکی، تفکیک حساب شخصی و ساختمان، کشف مغایرت‌ها و رسم نمودارها، مجوزهای مورد نیاز را تایید کنید.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F2942))) {
            Text("اعطای مجوزهای مورد نیاز")
        }
    }
}
