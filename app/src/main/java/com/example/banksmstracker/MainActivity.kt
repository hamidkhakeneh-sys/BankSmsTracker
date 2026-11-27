package com.example.banksmstracker

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import saman.zamani.persiandate.PersianDate
import saman.zamani.persiandate.PersianDateFormat

val AppColorScheme = lightColorScheme(
    primary = Color(0xFF0F2942),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E4F0),
    onPrimaryContainer = Color(0xFF0F2942),
    secondary = Color(0xFF00796B),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0F2F1),
    onSecondaryContainer = Color(0xFF004D40),
    tertiary = Color(0xFFE65100),
    background = Color(0xFFF2F5F8),
    surface = Color.White,
    onSurface = Color(0xFF1C2434)
)

class MainActivity : ComponentActivity() {
    private var notificationSmsId by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (DatabaseSecurityManager.isScreenPrivacyEnabled(this)) {
            window.setFlags(
                android.view.WindowManager.LayoutParams.FLAG_SECURE,
                android.view.WindowManager.LayoutParams.FLAG_SECURE
            )
        }
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

            MaterialTheme(colorScheme = AppColorScheme, typography = dynamicTypography) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        var unlockedDbPassphrase by remember { mutableStateOf<ByteArray?>(null) }

                        if (unlockedDbPassphrase == null) {
                            DatabaseAuthGateScreen(
                                onUnlocked = { keyBytes -> unlockedDbPassphrase = keyBytes }
                            )
                        } else {
                            HolooSmsAccountingApp(
                                dbPassphrase = unlockedDbPassphrase!!,
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
    dbPassphrase: ByteArray,
    openDialogForSmsId: String? = null,
    onDialogOpenedFromNotification: () -> Unit = {},
    fontScale: Float = 1.0f,
    onFontScaleLoaded: (Float) -> Unit = {},
    onFontScaleChanged: (Float) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val database = remember { AppDatabase.getDatabase(context, dbPassphrase) }
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
    var accountBankMappings by remember { mutableStateOf<List<AccountBankMappingEntity>>(emptyList()) }
    var buildingUnits by remember { mutableStateOf<List<BuildingUnitEntity>>(emptyList()) }
    var unitCharges by remember { mutableStateOf<List<UnitChargeEntity>>(emptyList()) }
    var manualBuildingEntries by remember { mutableStateOf<List<ManualBuildingEntryEntity>>(emptyList()) }
    var buildingName by remember { mutableStateOf("ساختمان من") }
    var autoMonthlyChargeEnabled by remember { mutableStateOf(false) }
    var hasCheckedAutoMonthlyCharge by remember { mutableStateOf(false) }
    var smsFromYearMonth by remember { mutableStateOf("همه") }
    var customScopes by remember { mutableStateOf<List<String>>(listOf("شخصی", "مدیریت ساختمان")) }

    var isLoading by remember { mutableStateOf(false) }

    var bottomTabIndex by remember { mutableIntStateOf(0) }
    var selectedScope by remember { mutableStateOf("همه صندوق‌ها") }
    var chartViewMode by remember { mutableStateOf(ChartViewMode.YEARLY_DRILLDOWN) }
    var dashboardSelectedYear by remember(selectedScope) { mutableStateOf<String?>(null) }
    var dashboardSelectedMonth by remember(selectedScope) { mutableStateOf<String?>(null) }
    var dashboardSelectedAccount by remember(selectedScope) { mutableStateOf<String?>(null) }

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

    val dashboardAccountFilteredBase by remember(scopedTransactions, dashboardSelectedAccount) {
        derivedStateOf {
            if (dashboardSelectedAccount == null) scopedTransactions
            else scopedTransactions.filter { it.accountKey == dashboardSelectedAccount }
        }
    }

    // تراکنش‌های فیلترشده صفحه اول بر اساس بانک/حساب، سال یا ماه انتخاب‌شده در نمودار
    val dashboardDisplayTransactions by remember(dashboardAccountFilteredBase, dashboardSelectedYear, dashboardSelectedMonth) {
        derivedStateOf {
            when {
                dashboardSelectedMonth != null -> dashboardAccountFilteredBase.filter { it.persianMonthYear == dashboardSelectedMonth }
                dashboardSelectedYear != null -> dashboardAccountFilteredBase.filter { it.persianYear == dashboardSelectedYear }
                else -> dashboardAccountFilteredBase
            }
        }
    }

    val buildingTransactions by remember(allTransactions) {
        derivedStateOf { allTransactions.filter { it.scope == "مدیریت ساختمان" } }
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

    val availableCategoryFilters by remember(categories, selectedScope) {
        derivedStateOf {
            val relevantCats = when (selectedScope) {
                "شخصی" -> categories.filter { it.scope == "شخصی" }
                "مدیریت ساختمان" -> categories.filter { it.scope == "مدیریت ساختمان" }
                else -> categories
            }.map { it.name }
            listOf("همه دسته‌ها", "دسته‌بندی نشده") + relevantCats
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

                val savedChartMode = settingsMap["chart_view_mode"]
                if (savedChartMode == ChartViewMode.MONTHLY_ALL.name) {
                    chartViewMode = ChartViewMode.MONTHLY_ALL
                } else {
                    chartViewMode = ChartViewMode.YEARLY_DRILLDOWN
                }

                val loadedUnits = appDao.getAllBuildingUnits()
                var loadedCharges = appDao.getAllUnitCharges()
                val isAutoChargeOn = settingsMap["auto_monthly_charge"]?.toBooleanStrictOrNull() ?: false
                autoMonthlyChargeEnabled = isAutoChargeOn
                val savedFromYm = settingsMap["sms_from_ym"]
                if (!savedFromYm.isNullOrBlank()) smsFromYearMonth = savedFromYm

                // صدور خودکار شارژ ماهانه در تاریخ ۱ ماه جاری (فقط یک‌بار در هر نشست و در صورت فعال‌سازی کاربر)
                if (isAutoChargeOn && !hasCheckedAutoMonthlyCharge && loadedUnits.isNotEmpty()) {
                    hasCheckedAutoMonthlyCharge = true
                    val pNow = PersianDate()
                    val currentMonthKey = PersianDateFormat("Y/m").format(pNow) // مثلاً "1405/09"
                    val firstDayOfMonthDate = "$currentMonthKey/01"
                    val monthTitle = "شارژ ماهانه ${formatPersianMonthShort(currentMonthKey)} (صدور خودکار یکم ماه)"

                    val missingAutoCharges = loadedUnits.filter { unit ->
                        unit.monthlyCharge > 0L && loadedCharges.none { ch ->
                            ch.unitId == unit.id && ch.persianMonthYear == currentMonthKey && ch.isAutoMonthly
                        }
                    }.map { unit ->
                        UnitChargeEntity(
                            unitId = unit.id,
                            amount = unit.monthlyCharge,
                            title = monthTitle,
                            persianDate = firstDayOfMonthDate,
                            persianMonthYear = currentMonthKey,
                            timestampMillis = System.currentTimeMillis(),
                            isAutoMonthly = true
                        )
                    }

                    if (missingAutoCharges.isNotEmpty()) {
                        val adjustedAutoCharges = UnitCreditAccountingEngine.applyExistingCreditToNewCharges(
                            draftCharges = missingAutoCharges,
                            units = loadedUnits,
                            existingCharges = loadedCharges,
                            manualEntries = appDao.getAllManualBuildingEntries(),
                            buildingTransactions = allTransactions.filter { it.scope == "مدیریت ساختمان" }
                        )
                        appDao.insertUnitCharges(adjustedAutoCharges)
                        loadedCharges = appDao.getAllUnitCharges()
                    }
                }

                buildingUnits = loadedUnits
                unitCharges = loadedCharges
                manualBuildingEntries = appDao.getAllManualBuildingEntries()
                val savedBName = settingsMap["building_name"]
                if (!savedBName.isNullOrBlank()) buildingName = savedBName

                val ignoredList = appDao.getAllIgnored()
                ignoredItems = ignoredList
                val ignoredIds = ignoredList.filter { !it.isSenderBlocked }.map { it.keyId }.toSet()
                val blockedSenders = ignoredList.filter { it.isSenderBlocked }.map { it.keyId }.toSet()
                BackgroundSmsReceiver.syncBlockedSenders(context, blockedSenders)

                val bankMappingsList = appDao.getAllAccountBankMappings()
                accountBankMappings = bankMappingsList
                val bankMappingsMap = bankMappingsList.associate { it.rawAccountNumber to it.bankName }

                val mappingsList = appDao.getAllPayerMappings()
                payerMappings = mappingsList
                val mappingsMap = mappingsList.associateBy { it.payerIdentifier }

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
                    accountBankMappings = bankMappingsMap,
                    payerMappings = mappingsMap,
                    importedRecords = importedList,
                    ignoredSmsIds = ignoredIds,
                    blockedSenders = blockedSenders,
                    fromYearMonth = smsFromYearMonth
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

    // ناظر بهینه پیامک فقط زمانی که اپلیکیشن در پیش‌زمینه (ON_START..ON_STOP) است فعال می‌ماند
    // و به محض رفتن برنامه به پس‌زمینه (ON_STOP) آزاد می‌شود تا مصرف باتری صفر باشد
    LifecycleAwareSmsObserver(
        hasPermission = hasSmsPermission,
        onNewBankTransaction = {
            coroutineScope.launch {
                delay(1200L)
                fullReloadFromSms()
            }
        }
    )

    Scaffold(
        topBar = {
            Column(
                modifier = Modifier.background(
                    Brush.horizontalGradient(listOf(Color(0xFF0B1F3A), Color(0xFF133B5C)))
                )
            ) {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = Color(0xFF00897B),
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("هم‌تراز", style = MaterialTheme.typography.titleMedium, color = Color.White)
                                Text("حسابداری هوشمند شخصی و مدیریت مجتمع", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB2DFDB))
                            }
                        }
                    },
                    actions = {
                        if (hasSmsPermission) {
                            IconButton(onClick = { showCategoryManager = true }) {
                                Icon(Icons.Rounded.AccountTree, contentDescription = "سرفصل‌ها", tint = Color.White)
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
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
                )

                if (hasSmsPermission && bottomTabIndex != 2) {
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
                                color = if (isSelected) Color(0xFF00897B) else Color.White.copy(alpha = 0.14f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = when (scopeName) {
                                            "مدیریت ساختمان" -> Icons.Rounded.Apartment
                                            "شخصی" -> Icons.Rounded.Person
                                            else -> Icons.Rounded.AccountBalance
                                        },
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(16.dp)
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
                NavigationBar(containerColor = Color.White, tonalElevation = 10.dp) {
                    NavigationBarItem(
                        selected = bottomTabIndex == 0,
                        onClick = { bottomTabIndex = 0 },
                        icon = {
                            Icon(
                                if (bottomTabIndex == 0) Icons.Rounded.DonutLarge else Icons.Rounded.PieChartOutline,
                                contentDescription = null
                            )
                        },
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
                        icon = { Icon(Icons.Rounded.Domain, contentDescription = null) },
                        label = { Text("ساختمان‌یار", style = MaterialTheme.typography.labelSmall) }
                    )
                    NavigationBarItem(
                        selected = bottomTabIndex == 3,
                        onClick = { bottomTabIndex = 3 },
                        icon = { Icon(Icons.Rounded.QueryStats, contentDescription = null) },
                        label = { Text("گزارشات", style = MaterialTheme.typography.labelSmall) }
                    )
                    NavigationBarItem(
                        selected = bottomTabIndex == 4,
                        onClick = { bottomTabIndex = 4 },
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
                        CircularProgressIndicator(color = Color(0xFF00796B))
                        Spacer(modifier = Modifier.height(10.dp))
                        Text("در حال پردازش اسناد بانکی...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                else -> {
                    when (bottomTabIndex) {
                        0 -> DashboardScreen(
                            allSystemTransactions = allTransactions,
                            allScopedTransactions = dashboardAccountFilteredBase,
                            displayTransactions = dashboardDisplayTransactions,
                            selectedAccountKey = dashboardSelectedAccount,
                            onSelectAccountKey = { dashboardSelectedAccount = it },
                            viewMode = chartViewMode,
                            onViewModeChange = { newMode ->
                                chartViewMode = newMode
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveSetting(AppSettingEntity("chart_view_mode", newMode.name))
                                }
                            },
                            selectedYear = dashboardSelectedYear,
                            onSelectYear = { dashboardSelectedYear = it },
                            selectedMonth = dashboardSelectedMonth,
                            onSelectMonth = { dashboardSelectedMonth = it },
                            discrepancyCount = scopedDiscrepancies.size,
                            scopeTitle = selectedScope,
                            onNavigateToDiscrepancies = { bottomTabIndex = 4 }
                        )
                        1 -> JournalScreen(
                            transactions = journalTransactions,
                            buildingUnits = buildingUnits,
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
                        2 -> SharjemanBuildingScreen(
                            units = buildingUnits,
                            unitCharges = unitCharges,
                            manualEntries = manualBuildingEntries,
                            buildingTransactions = buildingTransactions,
                            buildingCategories = categories.filter { it.scope == "مدیریت ساختمان" },
                            buildingName = buildingName,
                            autoMonthlyChargeEnabled = autoMonthlyChargeEnabled,
                            onToggleAutoMonthlyCharge = { enabled ->
                                autoMonthlyChargeEnabled = enabled
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveSetting(AppSettingEntity("auto_monthly_charge", enabled.toString()))
                                    if (enabled) fullReloadFromSms()
                                }
                            },
                            onUpdateBuildingName = { newBName ->
                                buildingName = newBName
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveSetting(AppSettingEntity("building_name", newBName))
                                }
                            },
                            onAddOrUpdateUnit = { unit ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    val insertedId = appDao.saveBuildingUnit(unit).toInt()
                                    val updatedUnits = appDao.getAllBuildingUnits()
                                    buildingUnits = updatedUnits
                                    // اگر واحد جدید تعریف شد و شارژ خودکار یکم ماه فعال است، شارژ ماه جاری را برایش ثبت کن
                                    if (autoMonthlyChargeEnabled && unit.monthlyCharge > 0L) {
                                        val targetId = if (unit.id != 0) unit.id else insertedId
                                        val pNow = PersianDate()
                                        val currentMonthKey = PersianDateFormat("Y/m").format(pNow)
                                        val existing = appDao.getAllUnitCharges()
                                        val alreadyHas = existing.any { it.unitId == targetId && it.persianMonthYear == currentMonthKey && it.isAutoMonthly }
                                        if (!alreadyHas && targetId > 0) {
                                            appDao.insertUnitCharges(
                                                listOf(
                                                    UnitChargeEntity(
                                                        unitId = targetId,
                                                        amount = unit.monthlyCharge,
                                                        title = "شارژ ماهانه ${formatPersianMonthShort(currentMonthKey)} (صدور خودکار یکم ماه)",
                                                        persianDate = "$currentMonthKey/01",
                                                        persianMonthYear = currentMonthKey,
                                                        timestampMillis = System.currentTimeMillis(),
                                                        isAutoMonthly = true
                                                    )
                                                )
                                            )
                                            unitCharges = appDao.getAllUnitCharges()
                                        }
                                    }
                                }
                            },
                            onBatchCreateUnits = { newBName, newUnits ->
                                buildingName = newBName
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveSetting(AppSettingEntity("building_name", newBName))
                                    newUnits.forEach { u -> appDao.saveBuildingUnit(u) }
                                    buildingUnits = appDao.getAllBuildingUnits()
                                    if (autoMonthlyChargeEnabled) fullReloadFromSms()
                                }
                            },
                            onDeleteUnit = { unitId ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.getAllUnitCharges().filter { it.unitId == unitId }.forEach { appDao.deleteUnitCharge(it.id) }
                                    appDao.getAllPayerMappings().filter { it.unitId == unitId }.forEach { appDao.deletePayerMapping(it.payerIdentifier) }
                                    appDao.deleteBuildingUnit(unitId)
                                    buildingUnits = appDao.getAllBuildingUnits()
                                    unitCharges = appDao.getAllUnitCharges()
                                    payerMappings = appDao.getAllPayerMappings()
                                }
                            },
                            onDeclareDebtBatch = { chargesList ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    val adjustedCharges = UnitCreditAccountingEngine.applyExistingCreditToNewCharges(
                                        draftCharges = chargesList,
                                        units = buildingUnits,
                                        existingCharges = appDao.getAllUnitCharges(),
                                        manualEntries = appDao.getAllManualBuildingEntries(),
                                        buildingTransactions = buildingTransactions
                                    )
                                    appDao.insertUnitCharges(adjustedCharges)
                                    unitCharges = appDao.getAllUnitCharges()
                                }
                            },
                            onDeleteCharge = { chId ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.deleteUnitCharge(chId)
                                    unitCharges = appDao.getAllUnitCharges()
                                }
                            },
                            onAddManualEntry = { entry ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.insertManualBuildingEntry(entry)
                                    manualBuildingEntries = appDao.getAllManualBuildingEntries()
                                }
                            },
                            onAddManualEntriesBatch = { entriesList ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.insertManualBuildingEntries(entriesList)
                                    manualBuildingEntries = appDao.getAllManualBuildingEntries()
                                }
                            },
                            onDeleteManualEntry = { entryId ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.deleteManualBuildingEntry(entryId)
                                    manualBuildingEntries = appDao.getAllManualBuildingEntries()
                                }
                            },
                            onSelectBankTransaction = { tx ->
                                selectedTransaction = tx
                            }
                        )
                        3 -> AdvancedReportsScreen(
                            transactions = scopedTransactions,
                            scopeTitle = selectedScope,
                            onExportCsv = { listToExport ->
                                CsvExporter.exportAndShareCsv(context, listToExport, selectedScope)
                            }
                        )
                        4 -> SettingsAndReconciliationScreen(
                            accountProfiles = accountProfiles,
                            transactions = allTransactions,
                            discrepancies = scopedDiscrepancies,
                            customScopes = customScopes,
                            payerMappings = payerMappings,
                            accountBankMappings = accountBankMappings,
                            ignoredItems = ignoredItems,
                            smsFromYearMonth = smsFromYearMonth,
                            onSmsFromYearMonthChange = { chosenYm ->
                                smsFromYearMonth = chosenYm
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveSetting(AppSettingEntity("sms_from_ym", chosenYm))
                                    fullReloadFromSms()
                                }
                            },
                            fontScale = fontScale,
                            chartViewMode = chartViewMode,
                            onChartViewModeChange = { newMode ->
                                chartViewMode = newMode
                                dashboardSelectedYear = null
                                dashboardSelectedMonth = null
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveSetting(AppSettingEntity("chart_view_mode", newMode.name))
                                }
                            },
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
                            onImportStatementRecords = { newRecords ->
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
                            onUpdatePayerMapping = { oldPayerId, updatedMapping ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    if (oldPayerId != updatedMapping.payerIdentifier) {
                                        appDao.deletePayerMapping(oldPayerId)
                                    }
                                    appDao.savePayerMapping(updatedMapping)
                                    fullReloadFromSms()
                                }
                            },
                            onSaveAccountBankMapping = { rawAcc, selectedBank ->
                                val rawDigits = rawAcc.replace(Regex("""[^\d]"""), "")
                                val coreDigits = if (rawDigits.length >= 15) rawDigits.drop(7).take(7) else rawDigits.takeLast(7)

                                val updatedRawList = allTransactions.map { tx ->
                                    val txDigits = tx.rawAccountNumber.replace(Regex("""[^\d]"""), "")
                                    val matchesAcc = (rawAcc.isNotEmpty() && tx.rawAccountNumber == rawAcc) ||
                                            (coreDigits.isNotEmpty() && (tx.accountNumber == coreDigits || txDigits.contains(coreDigits))) ||
                                            (rawDigits.isNotEmpty() && txDigits == rawDigits)
                                    if (matchesAcc) {
                                        tx.copy(
                                            bankName = selectedBank,
                                            accountKey = "$selectedBank - ${tx.accountNumber}"
                                        )
                                    } else tx
                                }
                                val profilesMap = accountProfiles.associateBy { it.accountKey }
                                val mapEntry = mapOf(rawAcc to selectedBank, coreDigits to selectedBank)
                    val (recomputedTxs, recomputedProfiles) = IranBankSmsParser.applyAccountBankMappingsInMemory(allTransactions, mapEntry, profilesMap)
                                allTransactions = recomputedTxs
                                accountProfiles = recomputedProfiles

                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.saveAccountBankMapping(AccountBankMappingEntity(rawAcc, selectedBank))
                                    if (coreDigits.isNotEmpty() && coreDigits != rawAcc) {
                                        appDao.saveAccountBankMapping(AccountBankMappingEntity(coreDigits, bankName = selectedBank))
                                    }
                                    recomputedProfiles.forEach { appDao.saveAccountProfile(it) }
                                }
                            },
                            onDeleteAccountBankMapping = { rawAcc ->
                                coroutineScope.launch(Dispatchers.IO) {
                                    appDao.deleteAccountBankMapping(rawAcc)
                                    fullReloadFromSms()
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
                buildingUnits = buildingUnits,
                allTransactionsForSuggestions = allTransactions,
                onDismiss = { selectedTransaction = null },
                onMultiUnitCreditSplit = { creditEntries, extraMappings ->
                    coroutineScope.launch(Dispatchers.IO) {
                        if (creditEntries.isNotEmpty()) {
                            appDao.insertManualBuildingEntries(creditEntries)
                            manualBuildingEntries = appDao.getAllManualBuildingEntries()
                        }
                        if (extraMappings.isNotEmpty()) {
                            extraMappings.forEach { appDao.savePayerMapping(it) }
                            fullReloadFromSms()
                        }
                    }
                    if (creditEntries.isNotEmpty()) {
                        Toast.makeText(context, "مبلغ بین ${creditEntries.size} واحد تقسیم و از بدهی آن‌ها کسر شد", Toast.LENGTH_LONG).show()
                    }
                },
                onAssignAccountToBank = { rawAccKey, chosenBank ->
                    selectedTransaction = null
                    val rawDigits = rawAccKey.replace(Regex("""[^\d]"""), "")
                    val coreDigits = IranBankSmsParser.extractAccountCore(rawAccKey)
                    val tail4 = if (rawDigits.length >= 4) rawDigits.takeLast(4) else rawDigits
                    val tail6 = if (rawDigits.length >= 6) rawDigits.takeLast(6) else rawDigits

                    val matchedRawAccounts = allTransactions
                        .mapNotNull { tx ->
                            val cand = tx.rawAccountNumber.ifBlank { tx.accountNumber }
                            val candDigits = cand.replace(Regex("""[^\d]"""), "")
                            if (candDigits.isNotEmpty() && IranBankSmsParser.matchesLastDigits(rawDigits, candDigits)) cand else null
                        }
                        .distinct()

                    val profilesMap = accountProfiles.associateBy { it.accountKey }
                    val mapEntry = buildMap<String, String> {
                        if (rawAccKey.isNotBlank()) put(rawAccKey, chosenBank)
                        if (rawDigits.isNotEmpty()) put(rawDigits, chosenBank)
                        if (coreDigits.isNotBlank() && coreDigits != "اصلی") put(coreDigits, chosenBank)
                        if (tail4.length >= 4) put(tail4, chosenBank)
                        if (tail6.length >= 6) put(tail6, chosenBank)
                        matchedRawAccounts.forEach { put(it, chosenBank) }
                    }

                    val (recomputedTxs, recomputedProfiles) = IranBankSmsParser.applyAccountBankMappingsInMemory(allTransactions, mapEntry, profilesMap)
                    allTransactions = recomputedTxs
                    accountProfiles = recomputedProfiles

                    coroutineScope.launch(Dispatchers.IO) {
                        mapEntry.keys.forEach { keyStr ->
                            if (keyStr.isNotBlank()) {
                                appDao.saveAccountBankMapping(AccountBankMappingEntity(keyStr, chosenBank))
                            }
                        }
                        recomputedProfiles.forEach { appDao.saveAccountProfile(it) }
                    }
                    if (selectedAccountFilter != "همه حساب‌ها" && !selectedAccountFilter.startsWith(chosenBank)) {
                        selectedAccountFilter = "همه حساب‌ها"
                    }
                    Toast.makeText(context, "حساب و موارد هم‌شماره (ارقام پایانی مشترک) به «$chosenBank» متصل شدند", Toast.LENGTH_LONG).show()
                },
                onSplitBuildingExpense = { generatedCharges ->
                    coroutineScope.launch(Dispatchers.IO) {
                        val adjustedCharges = UnitCreditAccountingEngine.applyExistingCreditToNewCharges(
                            draftCharges = generatedCharges,
                            units = buildingUnits,
                            existingCharges = appDao.getAllUnitCharges(),
                            manualEntries = appDao.getAllManualBuildingEntries(),
                            buildingTransactions = buildingTransactions
                        )
                        appDao.insertUnitCharges(adjustedCharges)
                        unitCharges = appDao.getAllUnitCharges()
                    }
                    Toast.makeText(context, "مبلغ هزینه بین ${generatedCharges.size} واحد تقسیم و بستانکاری‌های موجود اعمال شد", Toast.LENGTH_LONG).show()
                },
                onSave = { newCategory, newNote, transferOverride, selectedUnitId, applyToBuildingAccounting, assignUnitToPayerCard, receiptPath ->
                    val targetPayer = currentTx.payerIdentifier
                    val unitObj = buildingUnits.find { it.id == selectedUnitId }
                    val autoUnitLabel = if (unitObj != null) "${unitObj.unitNumber} - ${unitObj.residentName}" else newNote
                    val effectiveUnitIdForBalance = if (applyToBuildingAccounting) selectedUnitId else null
                    val shouldAutoPropagate = assignUnitToPayerCard && targetPayer.isNotEmpty() && selectedUnitId != null

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
                                    userNote = autoUnitLabel,
                                    category = newCategory,
                                    unitId = effectiveUnitIdForBalance,
                                    receiptImagePath = receiptPath,
                                    type = updatedType
                                )
                            }
                            shouldAutoPropagate && tx.payerIdentifier == targetPayer -> {
                                tx.copy(
                                    userNote = autoUnitLabel,
                                    unitId = if (applyToBuildingAccounting) selectedUnitId else tx.unitId,
                                    category = newCategory.ifBlank { "شارژ ماهیانه واحدها" }
                                )
                            }
                            else -> tx
                        }
                    }

                    if (shouldAutoPropagate && selectedUnitId != null) {
                        val updatedMapList = payerMappings.filterNot { it.payerIdentifier == targetPayer } +
                                PayerUnitMappingEntity(targetPayer, selectedUnitId, autoUnitLabel)
                        payerMappings = updatedMapList
                    }

                    selectedTransaction = null

                    coroutineScope.launch(Dispatchers.IO) {
                        appDao.saveNote(
                            TransactionNoteEntity(
                                smsId = currentTx.stableKey.ifBlank { currentTx.id },
                                note = autoUnitLabel,
                                category = newCategory,
                                isInternalTransferOverride = transferOverride,
                                unitId = effectiveUnitIdForBalance,
                                receiptImagePath = receiptPath
                            )
                        )
                        if (shouldAutoPropagate && selectedUnitId != null) {
                            appDao.savePayerMapping(PayerUnitMappingEntity(targetPayer, selectedUnitId, autoUnitLabel))
                            val matchingIds = allTransactions.filter { it.payerIdentifier == targetPayer && it.id != currentTx.id }
                            val batchNotes = matchingIds.map {
                                TransactionNoteEntity(
                                    smsId = it.id,
                                    note = autoUnitLabel,
                                    category = newCategory.ifBlank { "شارژ ماهیانه واحدها" },
                                    unitId = selectedUnitId,
                                    receiptImagePath = it.receiptImagePath
                                )
                            }
                            if (batchNotes.isNotEmpty()) {
                                appDao.saveNotesBatch(batchNotes)
                            }
                        }
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
                    val targetScope = if (currentTx.scope == "مدیریت ساختمان") "مدیریت ساختمان" else "شخصی"
                    val newCat = CategoryEntity(newCatName, isExp, targetScope)
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
                onAddCategory = { name, isExpense, catScope ->
                    val newCat = CategoryEntity(name, isExpense, catScope)
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

// ============================================================================
// تب ۱: داشبورد مدیریتی با نمودار سلسله‌مراتبی (سالانه -> ماهانه -> تفکیک سرفصل‌ها)
// ============================================================================
@Composable
fun DashboardScreen(
    allSystemTransactions: List<BankTransaction>,
    allScopedTransactions: List<BankTransaction>,
    displayTransactions: List<BankTransaction>,
    selectedAccountKey: String?,
    onSelectAccountKey: (String?) -> Unit,
    viewMode: ChartViewMode,
    onViewModeChange: (ChartViewMode) -> Unit,
    selectedYear: String?,
    onSelectYear: (String?) -> Unit,
    selectedMonth: String?,
    onSelectMonth: (String?) -> Unit,
    discrepancyCount: Int,
    scopeTitle: String,
    onNavigateToDiscrepancies: () -> Unit
) {
    val totalDeposit = remember(displayTransactions) {
        displayTransactions.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
    }
    val totalWithdrawal = remember(displayTransactions) {
        displayTransactions.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
    }
    val internalTransferSum = remember(displayTransactions) {
        displayTransactions.filter { it.type == TransactionType.INTERNAL }.sumOf { it.amount } / 2
    }

    var chartExpenseMode by remember { mutableStateOf(true) }

    val activePeriodLabel = remember(selectedYear, selectedMonth) {
        when {
            selectedMonth != null -> formatPersianMonthShort(selectedMonth)
            selectedYear != null -> "سال $selectedYear"
            else -> null
        }
    }

    // لیست تفکیک‌شده سرفصل‌های دوره انتخاب‌شده (نمایش جزئیات مبالغ زیر نمودار)
    val categoryBreakdown = remember(displayTransactions, chartExpenseMode) {
        val targetType = if (chartExpenseMode) TransactionType.WITHDRAWAL else TransactionType.DEPOSIT
        displayTransactions
            .filter { it.type == targetType }
            .groupBy { it.category.ifBlank { "دسته‌بندی نشده" } }
            .mapValues { entry -> Pair(entry.value.size, entry.value.sumOf { it.amount }) }
            .toList()
            .sortedByDescending { it.second.second }
    }

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
                shape = RoundedCornerShape(14.dp)
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
                            text = "برای بررسی یا بارگذاری صورت‌حساب بانک (اکسل/PDF) لمس کنید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5D4037)
                        )
                    }
                    Icon(Icons.Rounded.ChevronLeft, contentDescription = null, tint = Color(0xFFE65100))
                }
            }
        }

        // کارت تراز مالی
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AccountBalance, contentDescription = null, tint = Color(0xFF0F2942), modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (activePeriodLabel == null) "تراز مالی ($scopeTitle - کل)" else "تراز مالی ($scopeTitle - $activePeriodLabel)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F2942)
                        )
                    }
                    if (activePeriodLabel != null) {
                        Surface(
                            modifier = Modifier.clickable {
                                onSelectMonth(null)
                                onSelectYear(null)
                            },
                            color = Color(0xFFFFEBEE),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "حذف فیلتر ✕",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFC62828),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column {
                        Text("کل واریزی‌ها (بستانکار)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(
                            text = "+${formatCurrency(totalDeposit)} ریال",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF00897B)
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("کل برداشت‌ها (بدهکار)", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                        Text(
                            text = "-${formatCurrency(totalWithdrawal)} ریال",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFE53935)
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
                        color = if (net >= 0) Color(0xFF1565C0) else Color(0xFFE53935)
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

        // نمودار و کارت موجودی حساب‌های بانکی (تفکیک شخصی از ساختمان + فیلتر با کلیک روی هر بانک)
        BankBalancesInteractiveCard(
            allTransactions = allSystemTransactions,
            selectedAccountKey = selectedAccountKey,
            onSelectAccountKey = onSelectAccountKey
        )

        // نمودار سلسله‌مراتبی سالانه -> ماهانه با کلیک روی ستون‌ها
        HierarchicalTimeBarChart(
            allScopedTransactions = allScopedTransactions,
            viewMode = viewMode,
            onViewModeChange = onViewModeChange,
            selectedYear = selectedYear,
            onSelectYear = onSelectYear,
            selectedMonth = selectedMonth,
            onSelectMonth = onSelectMonth
        )

        // سوییچر انتخاب نمودار هزینه‌ها یا درآمدها
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = chartExpenseMode,
                onClick = { chartExpenseMode = true },
                label = { Text("هزینه‌های ${activePeriodLabel ?: "کل"}") },
                leadingIcon = { Icon(Icons.Rounded.TrendingDown, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = !chartExpenseMode,
                onClick = { chartExpenseMode = false },
                label = { Text("درآمدهای ${activePeriodLabel ?: "کل"}") },
                leadingIcon = { Icon(Icons.Rounded.TrendingUp, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f)
            )
        }

        // نمودار دایره‌ای سهم هر سرفصل
        InteractiveCategoryDonutChart(
            transactions = displayTransactions,
            isExpenseMode = chartExpenseMode,
            periodBadgeLabel = activePeriodLabel
        )

        // جدول تفکیک مبالغ هر سرفصل برای سال یا ماه انتخاب‌شده
        if (categoryBreakdown.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ریز تفکیک سرفصل‌ها (${activePeriodLabel ?: "کل دوره"})",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF0F2942)
                    )
                    categoryBreakdown.forEachIndexed { idx, (catName, countAndSum) ->
                        val (count, sum) = countAndSum
                        val color = ChartPalette[idx % ChartPalette.size]
                        HorizontalDivider(thickness = 0.5.dp, color = Color(0xFFEEEEEE))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Surface(
                                    color = color,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.size(10.dp)
                                ) {}
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("$catName ($count سند)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            }
                            Text(
                                text = "${formatCurrency(sum)} ریال",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = if (chartExpenseMode) Color(0xFFE53935) else Color(0xFF00897B)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun JournalScreen(
    transactions: List<BankTransaction>,
    buildingUnits: List<BuildingUnitEntity>,
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
                shape = RoundedCornerShape(12.dp)
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
                    val unitTitle = remember(tx.unitId, buildingUnits) {
                        tx.unitId?.let { id -> buildingUnits.find { it.id == id } }
                            ?.let { "${it.unitNumber} • ${it.residentName}" }
                    }
                    TransactionCard(
                        transaction = tx,
                        unitDisplayTitle = unitTitle,
                        onClick = { onTransactionClick(tx) }
                    )
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
                shape = RoundedCornerShape(16.dp)
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
                        Text("واریز دوره: +${formatCurrency(totalDep)}", color = Color(0xFF00897B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                        Text("برداشت دوره: -${formatCurrency(totalWit)}", color = Color(0xFFE53935), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        item {
            Button(
                onClick = { onExportCsv(reportTransactions) },
                enabled = reportTransactions.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F2942))
            ) {
                Icon(Icons.Rounded.FileDownload, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("خروجی اکسل این بازه (${reportTransactions.size} سند)")
            }
        }

        item {
            Text("هزینه‌های دوره به تفکیک سرفصل", style = MaterialTheme.typography.titleSmall, color = Color(0xFFE53935))
        }
        items(expenseByCategory) { (cat, sum) ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(cat, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("${formatCurrency(sum)} ریال", color = Color(0xFFE53935), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        item {
            Text("درآمدها و واریزی‌های دوره به تفکیک سرفصل", style = MaterialTheme.typography.titleSmall, color = Color(0xFF00897B))
        }
        items(incomeByCategory) { (cat, sum) ->
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White)) {
                Row(modifier = Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(cat, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text("${formatCurrency(sum)} ریال", color = Color(0xFF00897B), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
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
    accountBankMappings: List<AccountBankMappingEntity>,
    ignoredItems: List<IgnoredItemEntity>,
    smsFromYearMonth: String,
    onSmsFromYearMonthChange: (String) -> Unit,
    fontScale: Float,
    onFontScaleChange: (Float) -> Unit,
    chartViewMode: ChartViewMode,
    onChartViewModeChange: (ChartViewMode) -> Unit,
    onAddCustomScope: (String) -> Unit,
    onChangeAccountScope: (AccountProfileEntity, String) -> Unit,
    onImportStatementRecords: (List<ImportedRecordEntity>) -> Unit,
    onDeletePayerMapping: (String) -> Unit,
    onUpdatePayerMapping: (String, PayerUnitMappingEntity) -> Unit,
    onSaveAccountBankMapping: (String, String) -> Unit,
    onDeleteAccountBankMapping: (String) -> Unit,
    onRestoreIgnored: (String) -> Unit
) {
    val context = LocalContext.current
    var newScopeInput by remember { mutableStateOf("") }
    var selectedAccountForExcel by remember(accountProfiles) {
        mutableStateOf(accountProfiles.firstOrNull()?.accountKey ?: "")
    }

    // بررسی وضعیت بهینه‌سازی مصرف باتری گوشی
    val powerManager = remember { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    val isBatteryUnrestricted = remember {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    }

    val statementPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && selectedAccountForExcel.isNotEmpty()) {
            try {
                val bankMappingsMap = accountBankMappings.associate { it.rawAccountNumber to it.bankName }
                val importResult = ExcelImporter.parseBankStatementWithHeader(
                    context = context,
                    uri = uri,
                    fallbackAccountKey = selectedAccountForExcel,
                    existingProfiles = accountProfiles,
                    accountBankMappings = bankMappingsMap,
                    existingTransactions = transactions
                )
                if (importResult.detectedHeaderAccount.isNotEmpty()) {
                    val targetBank = importResult.resolvedAccountKey.substringBefore(" - ").trim()
                    if (targetBank.isNotEmpty() && targetBank != "سایر بانک‌ها") {
                        onSaveAccountBankMapping(importResult.detectedHeaderAccount, targetBank)
                    }
                }
                val imported = importResult.records
                if (imported.isEmpty()) {
                    val accHint = if (importResult.detectedHeaderAccount.isNotEmpty()) " • شماره حساب فایل: ${importResult.detectedHeaderAccount}" else ""
                    Toast.makeText(context, "رکورد جدیدی از فایل استخراج نشد (یا قبلاً ثبت شده است)$accHint", Toast.LENGTH_LONG).show()
                } else {
                    onImportStatementRecords(imported)
                    val headerInfo = if (importResult.detectedHeaderAccount.isNotEmpty()) {
                        " • حساب متصل‌شده: ${importResult.resolvedAccountKey} (${importResult.detectedHeaderAccount})"
                    } else ""
                    Toast.makeText(context, "${imported.size} رکورد جدید اضافه و با پیامک‌ها تطبیق داده شد$headerInfo", Toast.LENGTH_LONG).show()
                }
            } catch (e: Throwable) {
                Toast.makeText(context, "خطا در پردازش فایل: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // کارت مدیریت هوشمند مصرف باتری
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.White),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.BatteryChargingFull, contentDescription = null, tint = Color(0xFF00897B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مدیریت بهینه مصرف باتری (حالت فوق کم‌مصرف)", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "برنامه بدون هیچ سرویس پس‌زمینه دائم طراحی شده و مصرف باتری آن نزدیک به صفر است. برای اطمینان از دریافت آنی پیامک‌های بانکی هنگام قفل بودن گوشی، وضعیت بهینه‌سازی را بررسی کنید:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isBatteryUnrestricted) "وضعیت: بهینه و فعال ✅" else "وضعیت: محدودیت سیستم فعال",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isBatteryUnrestricted) Color(0xFF00897B) else Color(0xFFE65100)
                        )
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                    context.startActivity(intent)
                                }
                            }
                        ) {
                            Text("تنظیمات باتری گوشی", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        item {
            var customYmInput by remember(smsFromYearMonth) {
                mutableStateOf(if (smsFromYearMonth == "همه") "" else smsFromYearMonth)
            }
            val presetPeriods = remember(transactions) {
                val months = transactions.map { it.persianMonthYear }.distinct().sortedDescending().take(6)
                listOf("همه") + months
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.DateRange, contentDescription = null, tint = Color(0xFF00796B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("شروع فراخوانی پیامک‌ها (سال و ماه)", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        text = "مشخص کنید پیامک‌های بانکی از چه سال و ماهی فراخوانی و مدیریت شوند (فعلی: $smsFromYearMonth):",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(presetPeriods) { period ->
                            FilterChip(
                                selected = smsFromYearMonth == period,
                                onClick = {
                                    onSmsFromYearMonthChange(period)
                                    customYmInput = if (period == "همه") "" else period
                                },
                                label = { Text(if (period == "همه") "همه پیامک‌ها" else "از $period", style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = customYmInput,
                            onValueChange = { customYmInput = it },
                            placeholder = { Text("سال/ماه مثلاً 1405/06", style = MaterialTheme.typography.bodySmall) },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(onClick = {
                            val chosen = customYmInput.trim().ifEmpty { "همه" }
                            onSmsFromYearMonthChange(chosen)
                        }) { Text("اعمال") }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
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

        // تنظیم حالت پیش‌فرض نمودار صفحه داشبورد (سالانه یا ماهانه)
        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.BarChart, contentDescription = null, tint = Color(0xFF0F2942))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("نمای پیش‌فرض نمودار صفحه داشبورد", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("انتخاب کنید نمودار داشبورد ابتدا سال‌ها را نمایش دهد یا تمام ماه‌ها را:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ChartViewMode.entries.forEach { mode ->
                            FilterChip(
                                selected = chartViewMode == mode,
                                onClick = { onChartViewModeChange(mode) },
                                label = { Text(mode.title, style = MaterialTheme.typography.labelSmall) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AccountBalance, contentDescription = null, tint = Color(0xFF0F2942))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مدیریت صندوق‌ها و تخصیص حساب‌های بانکی", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("صندوق‌های دلخواه بسازید و مشخص کنید هر حساب مربوط به کدام بخش است:", style = MaterialTheme.typography.bodySmall, color = Color.Gray)

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
                            Text("موجودی: ${formatCurrency(latestBal)} ریال", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00897B))
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
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.UploadFile, contentDescription = null, tint = Color(0xFF00796B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("بارگذاری صورت‌حساب بانکی (اکسل XLS/XLSX/CSV یا PDF)", style = MaterialTheme.typography.titleSmall)
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "فایل خروجی همراه بام / اینترنت‌بانک را انتخاب کنید تا رکوردهای جاافتاده و شماره کارت واریزکنندگان استخراج و مغایرت‌ها رفع شوند:",
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
                            onClick = { statementPickerLauncher.launch(arrayOf("*/*")) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                        ) {
                            Icon(Icons.Rounded.FileUpload, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("انتخاب فایل اکسل / PDF بانک و رفع مغایرت")
                        }
                    }
                }
            }
        }

        item {
            Text("مغایرت‌های مانده حساب (${discrepancies.size})", style = MaterialTheme.typography.titleSmall, color = Color(0xFFE53935))
        }
        if (discrepancies.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFFE0F2F1))) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF00897B))
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

        // بخش شناسایی و اتصال شماره حساب‌های بدون نام بانک به بانک‌های ایران
        item {
            var targetAccountToLink by remember { mutableStateOf<String?>(null) }
            val detectedUnmappedAccounts = remember(transactions) {
                transactions
                    .filter { it.bankName == "سایر بانک‌ها" && it.rawAccountNumber.isNotBlank() }
                    .groupBy { it.rawAccountNumber }
                    .map { (accNum, list) -> Triple(accNum, list.size, list.first().persianDate) }
            }

            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = Color(0xFF00796B))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("شماره حساب‌های بدون نام بانک (${detectedUnmappedAccounts.size})", style = MaterialTheme.typography.titleSmall)
                    }
                    Text(
                        text = "شماره حساب‌های زیر در پیامک‌ها بدون نام بانک شناسایی شده‌اند؛ بانک مربوط به هرکدام را انتخاب کنید تا حساب اصلی و فرعی ۱، ۲ و... به‌طور خودکار ایجاد شوند:",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.Gray
                    )

                    if (detectedUnmappedAccounts.isEmpty()) {
                        Text("تمام شماره حساب‌های پیامک‌ها به بانک‌های خود متصل هستند ✅", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00796B))
                    } else {
                        detectedUnmappedAccounts.forEach { (accNum, txCount, lastDate) ->
                            Surface(
                                color = Color(0xFFFFF8E1),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("شماره حساب: $accNum", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall, color = Color(0xFF091E3A))
                                        Text("$txCount پیامک شناسایی‌شده • آخرین: $lastDate", style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
                                    }
                                    Button(
                                        onClick = { targetAccountToLink = accNum },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Text("انتصاب به بانک", style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    if (accountBankMappings.isNotEmpty()) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        Text("اتصال‌های ثبت‌شده:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        accountBankMappings.forEach { mapping ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("حساب: ${mapping.rawAccountNumber}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                    Text("بانک: ${mapping.bankName}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF00796B))
                                }
                                IconButton(onClick = { onDeleteAccountBankMapping(mapping.rawAccountNumber) }, modifier = Modifier.size(28.dp)) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.Red, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                    }
                }
            }

            targetAccountToLink?.let { accToLink ->
                AccountToBankPickerDialog(
                    discoveredAccountNumbers = listOf(accToLink),
                    initialAccount = accToLink,
                    onDismiss = { targetAccountToLink = null },
                    onConfirm = { accNum, bank ->
                        onSaveAccountBankMapping(accNum, bank)
                        targetAccountToLink = null
                    }
                )
            }
        }

        // نمایش و ویرایش شماره کارت‌ها و حساب‌های متصل‌شده به واحدهای ساختمان
        if (payerMappings.isNotEmpty()) {
            item {
                var editingPayer by remember { mutableStateOf<PayerUnitMappingEntity?>(null) }

                Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color.White), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("کارت‌ها و حساب‌های متصل به واحدهای ساختمان (${payerMappings.size})", style = MaterialTheme.typography.titleSmall, color = Color(0xFF091E3A))
                        payerMappings.forEach { mapItem ->
                            HorizontalDivider(thickness = 0.5.dp)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(mapItem.unitTitle, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium)
                                    Text("شماره کارت/حساب: ${mapItem.payerIdentifier}", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1565C0))
                                }
                                Row {
                                    IconButton(onClick = { editingPayer = mapItem }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.Edit, contentDescription = "ویرایش", tint = Color(0xFF091E3A), modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { onDeletePayerMapping(mapItem.payerIdentifier) }, modifier = Modifier.size(32.dp)) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف", tint = Color.Red, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                editingPayer?.let { targetMap ->
                    var newPayerCard by remember { mutableStateOf(targetMap.payerIdentifier) }
                    var newUnitTitle by remember { mutableStateOf(targetMap.unitTitle) }
                    AlertDialog(
                        onDismissRequest = { editingPayer = null },
                        title = { Text("ویرایش اتصال کارت به واحد", style = MaterialTheme.typography.titleMedium) },
                        text = {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = newPayerCard,
                                    onValueChange = { newPayerCard = it },
                                    label = { Text("شماره کارت / حساب واریزکننده") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                OutlinedTextField(
                                    value = newUnitTitle,
                                    onValueChange = { newUnitTitle = it },
                                    label = { Text("عنوان واحد متصل‌شده") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        },
                        confirmButton = {
                            Button(onClick = {
                                if (newPayerCard.isNotBlank() && newUnitTitle.isNotBlank()) {
                                    onUpdatePayerMapping(targetMap.payerIdentifier, PayerUnitMappingEntity(newPayerCard.trim(), targetMap.unitId, newUnitTitle.trim()))
                                    editingPayer = null
                                }
                            }) { Text("ذخیره تغییرات") }
                        },
                        dismissButton = {
                            TextButton(onClick = { editingPayer = null }) { Text("انصراف") }
                        }
                    )
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
    unitDisplayTitle: String? = null,
    onClick: () -> Unit
) {
    val statusColor = when (transaction.type) {
        TransactionType.DEPOSIT -> Color(0xFF00897B)
        TransactionType.WITHDRAWAL -> Color(0xFFE53935)
        TransactionType.INTERNAL -> Color(0xFF1E88E5)
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
        shape = RoundedCornerShape(14.dp),
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
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (transaction.receiptImagePath.isNotEmpty()) {
                        Icon(Icons.Rounded.AttachFile, contentDescription = "سند تصویری", tint = Color(0xFF00796B), modifier = Modifier.size(16.dp))
                    }
                    Surface(
                        color = if (transaction.scope == "مدیریت ساختمان") Color(0xFFE0F2F1) else Color(0xFFE8EAF6),
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
                    Surface(color = Color(0xFFE3F2FD), shape = RoundedCornerShape(6.dp)) {
                        Text(
                            text = "واریزکننده/مقصد: ${transaction.payerIdentifier}",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0D47A1),
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
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
                    if (!unitDisplayTitle.isNullOrBlank()) {
                        Surface(color = Color(0xFF00796B), shape = RoundedCornerShape(6.dp)) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.MeetingRoom, contentDescription = null, tint = Color.White, modifier = Modifier.size(12.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = unitDisplayTitle,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
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
                            !unitDisplayTitle.isNullOrBlank() && transaction.userNote == unitDisplayTitle -> ""
                            transaction.userNote.isNotBlank() -> transaction.userNote
                            transaction.scope == "مدیریت ساختمان" && transaction.type == TransactionType.DEPOSIT && unitDisplayTitle.isNullOrBlank() ->
                                "لمس کنید تا واحد واریزکننده مشخص شود..."
                            transaction.category.isNotBlank() -> ""
                            else -> "برای دسته‌بندی یا ثبت سند لمس کنید..."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = when {
                            transaction.userNote.isNotBlank() -> Color(0xFF0F2942)
                            transaction.scope == "مدیریت ساختمان" && transaction.type == TransactionType.DEPOSIT && unitDisplayTitle.isNullOrBlank() -> Color(0xFFE65100)
                            else -> Color.Gray
                        },
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
    buildingUnits: List<BuildingUnitEntity>,
    allTransactionsForSuggestions: List<BankTransaction> = emptyList(),
    onDismiss: () -> Unit,
    onAssignAccountToBank: (String, String) -> Unit = { _, _ -> },
    onSplitBuildingExpense: (List<UnitChargeEntity>) -> Unit = {},
    onMultiUnitCreditSplit: (List<ManualBuildingEntryEntity>, List<PayerUnitMappingEntity>) -> Unit = { _, _ -> },
    onSave: (String, String, Boolean?, Int?, Boolean, Boolean, String) -> Unit,
    onMarkAsNotBankSms: (Boolean) -> Unit,
    onAddNewCategory: (String) -> Unit
) {
    val context = LocalContext.current
    var noteText by remember { mutableStateOf(transaction.userNote) }
    var selectedCategory by remember { mutableStateOf(transaction.category) }
    var selectedUnitIds by remember {
        mutableStateOf(if (transaction.unitId != null) setOf(transaction.unitId) else emptySet())
    }
    var applyToBuildingAccounting by remember { mutableStateOf(true) }
    var multiUnitSplitMode by remember { mutableIntStateOf(0) } // 0: تقسیم مساوی مبلغ کل، 1: بر اساس متراژ، 2: بر اساس نفرات، 3: مبلغ کامل برای هر واحد
    var extraCardsForUnitText by remember { mutableStateOf("") }

    var receiptPath by remember { mutableStateOf(transaction.receiptImagePath) }
    var isInternalTransfer by remember { mutableStateOf(transaction.type == TransactionType.INTERNAL) }
    var splitExpenseAmongUnits by remember { mutableStateOf(false) }
    var expenseSplitMode by remember { mutableIntStateOf(0) }
    var assignUnitToAllPayerDeposits by remember { mutableStateOf(transaction.payerIdentifier.isNotEmpty() && transaction.type == TransactionType.DEPOSIT) }
    var showQuickAddCategory by remember { mutableStateOf(false) }
    var quickNewCatName by remember { mutableStateOf("") }
    var showWrongDetectionConfirm by remember { mutableStateOf(false) }

    // شناسایی سایر کارت‌ها/حساب‌های احتمالی مرتبط با همین شخص از روی نام واریزکننده در فایل اکسل
    val suggestedRelatedCards = remember(transaction.payerIdentifier, transaction.userNote, allTransactionsForSuggestions) {
        val personHint = transaction.userNote.substringAfter("•", "").trim()
        if (personHint.length >= 3) {
            allTransactionsForSuggestions
                .filter { it.payerIdentifier.isNotBlank() && it.payerIdentifier != transaction.payerIdentifier && it.userNote.contains(personHint) }
                .map { it.payerIdentifier }
                .distinct()
        } else emptyList()
    }
    var selectedRelatedCards by remember(suggestedRelatedCards) { mutableStateOf(suggestedRelatedCards.toSet()) }

    val relevantCategories = remember(categories, transaction.scope, transaction.type) {
        val targetScope = if (transaction.scope == "مدیریت ساختمان") "مدیریت ساختمان" else "شخصی"
        val isExpense = transaction.type != TransactionType.DEPOSIT
        categories
            .filter { it.scope == targetScope }
            .sortedByDescending { it.isExpense == isExpense }
    }

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val savedPath = ImageReceiptHelper.compressAndSaveReceipt(context, uri, transaction.id)
            if (savedPath != null) {
                receiptPath = savedPath
                val kb = ImageReceiptHelper.getFileSizeKb(context, savedPath)
                Toast.makeText(context, "تصویر سند با حجم کم ($kb کیلوبایت) پیوست شد", Toast.LENGTH_SHORT).show()
            }
        }
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
                    availableCategories = relevantCategories.map { it.name }
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
                // نمایش مجزا و شفافِ «شماره کارت/حساب فرد واریزکننده (از اکسل/پیامک)» در برابر «حساب بانکی شما»
                val extractedPayerSummary = remember(transaction.userNote, transaction.rawMessage) {
                    val combined = "${transaction.userNote} | ${transaction.rawMessage}"
                    val nameMatch = Regex("""(?:نام کامل طرف مقابل|نام طرف مقابل|نام)\s*[:=\-]\s*([^،,|
)]+)""").find(combined)?.groupValues?.get(1)?.trim()
                    val bankMatch = Regex("""(?:نام بانک طرف مقابل|بانک)\s*[:=\-]\s*([^،,|
)]+)""").find(combined)?.groupValues?.get(1)?.trim()
                    listOfNotNull(
                        nameMatch?.takeIf { it.isNotEmpty() }?.let { "نام: $it" },
                        bankMatch?.takeIf { it.isNotEmpty() && it != transaction.bankName }?.let { "بانک مبدا/مقصد: $it" }
                    ).joinToString(" • ")
                }

                Surface(color = Color(0xFFE0F2F1), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.CreditCard, contentDescription = null, tint = Color(0xFF00695C), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "اطلاعات واریزکننده / فرستنده و تراکنش",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFF004D40)
                            )
                        }
                        if (extractedPayerSummary.isNotEmpty()) {
                            Text(
                                text = extractedPayerSummary,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF004D40)
                            )
                        }
                        Text(
                            text = if (transaction.payerIdentifier.isNotEmpty()) {
                                "شماره کارت / حساب واریزکننده: ${transaction.payerIdentifier}"
                            } else {
                                "شماره کارت/حساب واریزکننده در متن: ثبت نشده • سرشماره/منبع: ${transaction.sender}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = Color(0xFF0D47A1)
                        )
                        Text(
                            text = "مبلغ: ${formatCurrency(transaction.amount)} ریال (${formatCurrency(transaction.amount / 10)} تومان) • تاریخ: ${transaction.persianDate}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF00695C)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (false) {
                    Text(
                        text = "${formatCurrency(transaction.amount)} ریال (${formatCurrency(transaction.amount / 10)} تومان)",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text("تاریخ: ${transaction.persianDate}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                // متن اصلی پیامک (با لمس باز شدن در اپ پیامک گوشی)
                Surface(
                    color = Color(0xFFF1F3F4),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { openSmsInPhoneApp(context, transaction.sender) }
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text(transaction.rawMessage, style = MaterialTheme.typography.bodySmall)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = null, tint = Color(0xFF1565C0), modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "مشاهده اصل پیامک در برنامه پیامک گوشی",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1565C0)
                            )
                        }
                    }
                }

                // باکس حساب بانکی شما و تعیین بانک (فقط برای انتصاب حساب خودتان به بانک)
                if (transaction.rawAccountNumber.isNotBlank() || transaction.bankName == "سایر بانک‌ها") {
                    var showInlineBankPicker by remember { mutableStateOf(false) }
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(
                        color = if (transaction.bankName == "سایر بانک‌ها") Color(0xFFE3F2FD) else Color(0xFFF8FAFC),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "حساب شما: ${transaction.accountKey}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0D47A1)
                                )
                                if (transaction.rawAccountNumber.isNotBlank()) {
                                    Text(
                                        text = "شناسه حساب پیامک: ${transaction.rawAccountNumber}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.DarkGray
                                    )
                                }
                            }
                            Button(
                                onClick = { showInlineBankPicker = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1565C0))
                            ) {
                                Icon(Icons.Rounded.AccountBalance, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("تعیین بانک", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    if (showInlineBankPicker) {
                        val fallbackExtractedAcc = remember(transaction.rawAccountNumber, transaction.rawMessage) {
                            transaction.rawAccountNumber.ifBlank {
                                Regex("""\d[\d.\-]{8,23}""").find(IranBankSmsParser.normalizePersian(transaction.rawMessage))?.value ?: ""
                            }
                        }
                        AccountToBankPickerDialog(
                            discoveredAccountNumbers = listOfNotNull(fallbackExtractedAcc.takeIf { it.isNotBlank() }),
                            initialAccount = fallbackExtractedAcc,
                            onDismiss = { showInlineBankPicker = false },
                            onConfirm = { accNum, chosenBank ->
                                showInlineBankPicker = false
                                onAssignAccountToBank(accNum, chosenBank)
                            }
                        )
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

                // =========================================================================
                // بخش هوشمند ساختمان: فعال هم برای واریزها و هم برای انتقال بین حساب‌ها (چندواحدی + تقسیم کسر از بدهی!)
                // =========================================================================
                val isBuildingCreditOrTransfer = transaction.scope == "مدیریت ساختمان" &&
                        (transaction.type == TransactionType.DEPOSIT || transaction.type == TransactionType.INTERNAL || isInternalTransfer) &&
                        buildingUnits.isNotEmpty()

                if (isBuildingCreditOrTransfer) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Surface(
                        color = Color(0xFFF0F9FF),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                text = if (isInternalTransfer || transaction.type == TransactionType.INTERNAL)
                                    "انتقال وجه / تقسیم کسر از بدهی واحدهای انتخابی:"
                                else
                                    "نسبت دادن به واحد(های) ساختمان (بستانکاری / کسر از بدهی):",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color(0xFF00695C),
                                fontWeight = FontWeight.Bold
                            )

                            // امکان انتخاب یک یا چند واحد به‌صورت هم‌زمان
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(buildingUnits) { unit ->
                                    val isSel = selectedUnitIds.contains(unit.id)
                                    FilterChip(
                                        selected = isSel,
                                        onClick = {
                                            selectedUnitIds = if (isSel) selectedUnitIds - unit.id else selectedUnitIds + unit.id
                                            val chosenList = buildingUnits.filter { selectedUnitIds.contains(it.id) }
                                            if (chosenList.size == 1) {
                                                val u = chosenList.first()
                                                noteText = "${u.unitNumber} - ${u.residentName}"
                                                selectedCategory = "شارژ ماهیانه واحدها"
                                            } else if (chosenList.size > 1) {
                                                noteText = "تقسیم بین ${chosenList.joinToString("، ") { it.unitNumber }}"
                                                selectedCategory = "شارژ ماهیانه واحدها"
                                            }
                                        },
                                        label = { Text("${unit.unitNumber} (${unit.residentName})", style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }

                            if (selectedUnitIds.isNotEmpty()) {
                                // انتخاب اعمال در بیلان ساختمان یا فقط اتصال یادداشت
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable { applyToBuildingAccounting = !applyToBuildingAccounting },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = applyToBuildingAccounting, onCheckedChange = { applyToBuildingAccounting = it })
                                    Text(
                                        text = if (applyToBuildingAccounting) "اعمال در حساب و بیلان ساختمان (کسر از بدهی / بستانکاری)" else "فقط جهت اتصال / یادداشت (بدون تغییر بیلان مالی)",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (applyToBuildingAccounting) Color(0xFF00695C) else Color.DarkGray
                                    )
                                }

                                // اگر بیش از یک واحد انتخاب شد (یا حالت انتقال وجه بود)، نحوه تقسیم مبلغ را نشان بده
                                if (selectedUnitIds.size > 1 && applyToBuildingAccounting) {
                                    Text("نحوه تقسیم مبلغ بین ${selectedUnitIds.size} واحد انتخابی:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("تقسیم مساوی", "متراژ", "نفرات", "کل مبلغ هر واحد").forEachIndexed { idx, lbl ->
                                            FilterChip(
                                                selected = multiUnitSplitMode == idx,
                                                onClick = { multiUnitSplitMode = idx },
                                                label = { Text(lbl, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                    val perUnitPreview = if (multiUnitSplitMode == 3) transaction.amount else (transaction.amount / selectedUnitIds.size)
                                    Text(
                                        text = "سهم تقریبی هر واحد: ${formatCurrency(perUnitPreview)} ریال (${formatCurrency(perUnitPreview / 10)} تومان)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color(0xFF0D47A1),
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                // اتصال دائمی کارت فعلی + کارت‌های هم‌نام در اکسل + ثبت شماره کارت‌های دیگر همین واحد
                                if (selectedUnitIds.size == 1) {
                                    val singleUnit = buildingUnits.first { it.id == selectedUnitIds.first() }
                                    if (transaction.payerIdentifier.isNotEmpty()) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable { assignUnitToAllPayerDeposits = !assignUnitToAllPayerDeposits },
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(checked = assignUnitToAllPayerDeposits, onCheckedChange = { assignUnitToAllPayerDeposits = it })
                                            Text(
                                                text = "اتصال دائمی کارت «${transaction.payerIdentifier}» به ${singleUnit.unitNumber}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = Color(0xFF004D40)
                                            )
                                        }
                                    }

                                    // پیشنهاد کارت‌های دیگرِ همین فرد در فایل اکسل
                                    if (suggestedRelatedCards.isNotEmpty()) {
                                        Text("سایر کارت‌های این فرد در اکسل (تیک بزنید تا متصل شوند):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF1565C0))
                                        suggestedRelatedCards.forEach { relCard ->
                                            val checked = selectedRelatedCards.contains(relCard)
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.clickable {
                                                    selectedRelatedCards = if (checked) selectedRelatedCards - relCard else selectedRelatedCards + relCard
                                                }
                                            ) {
                                                Checkbox(checked = checked, onCheckedChange = {
                                                    selectedRelatedCards = if (checked) selectedRelatedCards - relCard else selectedRelatedCards + relCard
                                                })
                                                Text(relCard, style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }

                                    // افزودن شماره کارت یا شماره حساب‌های دیگر برای همین واحد
                                    OutlinedTextField(
                                        value = extraCardsForUnitText,
                                        onValueChange = { extraCardsForUnitText = it },
                                        label = { Text("افزودن شماره کارت/حساب‌های دیگر این واحد (با کاما جدا کنید)", style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                // پرسش برای برداشت‌های حساب ساختمان: از محل شارژ یا تقسیم بین واحدها؟
                if (transaction.scope == "مدیریت ساختمان" && transaction.type == TransactionType.WITHDRAWAL && !isInternalTransfer && buildingUnits.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Surface(color = Color(0xFFFFF8E1), shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(8.dp)) {
                            Text("این هزینه ساختمان از چه محلی تامین شود؟", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium, color = Color(0xFFE65100))
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { splitExpenseAmongUnits = false }) {
                                RadioButton(selected = !splitExpenseAmongUnits, onClick = { splitExpenseAmongUnits = false })
                                Text("از محل شارژ موجود در صندوق", style = MaterialTheme.typography.bodySmall)
                            }
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().clickable { splitExpenseAmongUnits = true }) {
                                RadioButton(selected = splitExpenseAmongUnits, onClick = { splitExpenseAmongUnits = true })
                                Text("تقسیم مبلغ بین واحدها (ثبت بدهکاری)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            if (splitExpenseAmongUnits) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    listOf("مساوی", "متراژ", "نفرات").forEachIndexed { idx, lbl ->
                                        FilterChip(
                                            selected = expenseSplitMode == idx,
                                            onClick = { expenseSplitMode = idx },
                                            label = { Text(lbl, style = MaterialTheme.typography.labelSmall) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isInternalTransfer) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = if (transaction.scope == "مدیریت ساختمان") "سرفصل‌های ساختمان:" else "سرفصل‌های شخصی:",
                            style = MaterialTheme.typography.titleSmall
                        )
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
                        relevantCategories.forEach { cat ->
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
                            SmartVoiceCategorizer.suggestCategory(it, relevantCategories.map { c -> c.name })?.let { sug ->
                                selectedCategory = sug
                            }
                        }
                    },
                    label = { Text("شرح سند (تایپ یا میکروفون)") },
                    placeholder = { Text("مثلاً: تعمیر آسانسور یا خرید سوپرمارکت...") },
                    trailingIcon = {
                        IconButton(onClick = startVoiceInput) {
                            Icon(Icons.Rounded.Mic, contentDescription = "ورودی صوتی", tint = Color(0xFF0F2942))
                        }
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(10.dp))
                OutlinedButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Rounded.AddAPhoto, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (receiptPath.isEmpty()) "پیوست تصویر فاکتور / رسید (فشرده‌سازی خودکار)" else "تغییر تصویر پیوست‌شده (${ImageReceiptHelper.getFileSizeKb(context, receiptPath)} KB)",
                        style = MaterialTheme.typography.labelMedium
                    )
                }

                if (receiptPath.isNotEmpty()) {
                    val bitmap = remember(receiptPath) {
                        ImageReceiptHelper.resolveReceiptFile(context, receiptPath)?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    }
                    if (bitmap != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Box(modifier = Modifier.fillMaxWidth()) {
                            Image(
                                bitmap = bitmap.asImageBitmap(),
                                contentDescription = "تصویر سند",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(10.dp))
                            )
                            IconButton(
                                onClick = {
                                    ImageReceiptHelper.deleteReceiptFile(context, receiptPath)
                                    receiptPath = ""
                                },
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .padding(4.dp)
                                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(50))
                            ) {
                                Icon(Icons.Rounded.Close, contentDescription = "حذف تصویر", tint = Color.White, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

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
                val finalNote = if (splitExpenseAmongUnits && !noteText.contains("تقسیم")) {
                    "${noteText.trim().ifEmpty { selectedCategory.ifEmpty { "هزینه ساختمان" } }} (تقسیم بین واحدها)"
                } else {
                    noteText.trim()
                }

                // ۱. اگر برداشت ساختمان بود و گزینه تقسیم بین واحدها انتخاب شد
                if (splitExpenseAmongUnits && buildingUnits.isNotEmpty() && transaction.type == TransactionType.WITHDRAWAL) {
                    val now = System.currentTimeMillis()
                    val pDate = PersianDate(now)
                    val dateStr = PersianDateFormat("Y/m/d").format(pDate)
                    val monthStr = PersianDateFormat("Y/m").format(pDate)
                    val expTitle = noteText.trim().ifEmpty { selectedCategory.ifEmpty { "هزینه ساختمان" } }
                    val charges = when (expenseSplitMode) {
                        0 -> {
                            val perUnit = transaction.amount / buildingUnits.size
                            buildingUnits.map { u -> UnitChargeEntity(unitId = u.id, amount = perUnit, title = "سهم هزینه: $expTitle", persianDate = dateStr, persianMonthYear = monthStr, timestampMillis = now) }
                        }
                        1 -> {
                            val totalArea = buildingUnits.sumOf { it.areaSqm.coerceAtLeast(1) }.toDouble()
                            buildingUnits.map { u ->
                                val share = ((u.areaSqm.coerceAtLeast(1) / totalArea) * transaction.amount).toLong()
                                UnitChargeEntity(unitId = u.id, amount = share, title = "سهم هزینه (متراژ): $expTitle", persianDate = dateStr, persianMonthYear = monthStr, timestampMillis = now)
                            }
                        }
                        else -> {
                            val totalPeople = buildingUnits.sumOf { it.residentCount.coerceAtLeast(1) }.toDouble()
                            buildingUnits.map { u ->
                                val share = ((u.residentCount.coerceAtLeast(1) / totalPeople) * transaction.amount).toLong()
                                UnitChargeEntity(unitId = u.id, amount = share, title = "سهم هزینه (نفرات): $expTitle", persianDate = dateStr, persianMonthYear = monthStr, timestampMillis = now)
                            }
                        }
                    }.filter { it.amount > 0L }
                    if (charges.isNotEmpty()) onSplitBuildingExpense(charges)
                }

                // ۲. اگر بیش از یک واحد برای واریز/انتقال انتخاب شده بود، سهم هر واحد را محاسبه و به بستانکاری/کسر بدهی آن‌ها اضافه کن
                if (selectedUnitIds.size > 1 && applyToBuildingAccounting) {
                    val targetUnits = buildingUnits.filter { selectedUnitIds.contains(it.id) }
                    val now = System.currentTimeMillis()
                    val pDate = PersianDate(now)
                    val dateTimeStr = PersianDateFormat("Y/m/d - H:i").format(pDate)
                    val monthStr = PersianDateFormat("Y/m").format(pDate)
                    val creditEntries = when (multiUnitSplitMode) {
                        0 -> {
                            val perUnit = transaction.amount / targetUnits.size
                            targetUnits.map { u -> ManualBuildingEntryEntity(entryType = "UNIT_CREDIT", title = finalNote.ifEmpty { "تقسیم انتقال/واریز بانکی" }, amount = perUnit, unitId = u.id, persianDate = dateTimeStr, persianMonthYear = monthStr, timestampMillis = now) }
                        }
                        1 -> {
                            val totalArea = targetUnits.sumOf { it.areaSqm.coerceAtLeast(1) }.toDouble()
                            targetUnits.map { u ->
                                val share = ((u.areaSqm.coerceAtLeast(1) / totalArea) * transaction.amount).toLong()
                                ManualBuildingEntryEntity(entryType = "UNIT_CREDIT", title = finalNote.ifEmpty { "تقسیم متراژی واریز" }, amount = share, unitId = u.id, persianDate = dateTimeStr, persianMonthYear = monthStr, timestampMillis = now)
                            }
                        }
                        2 -> {
                            val totalPeople = targetUnits.sumOf { it.residentCount.coerceAtLeast(1) }.toDouble()
                            targetUnits.map { u ->
                                val share = ((u.residentCount.coerceAtLeast(1) / totalPeople) * transaction.amount).toLong()
                                ManualBuildingEntryEntity(entryType = "UNIT_CREDIT", title = finalNote.ifEmpty { "تقسیم نفراتی واریز" }, amount = share, unitId = u.id, persianDate = dateTimeStr, persianMonthYear = monthStr, timestampMillis = now)
                            }
                        }
                        else -> {
                            targetUnits.map { u -> ManualBuildingEntryEntity(entryType = "UNIT_CREDIT", title = finalNote.ifEmpty { "بستانکاری انتقال بانکی" }, amount = transaction.amount, unitId = u.id, persianDate = dateTimeStr, persianMonthYear = monthStr, timestampMillis = now) }
                        }
                    }.filter { it.amount > 0L }

                    onMultiUnitCreditSplit(creditEntries, emptyList())
                    onSave(selectedCategory, finalNote, transferOverride, null, false, false, receiptPath)
                    return@Button
                }

                // ۳. اگر یک واحد انتخاب شده بود، علاوه بر ثبت اصلی، کارت‌های اضافی/هم‌نام را هم به همان واحد متصل کن
                val singleSelectedUnitId = selectedUnitIds.firstOrNull()
                if (singleSelectedUnitId != null) {
                    val unitObj = buildingUnits.first { it.id == singleSelectedUnitId }
                    val unitTitle = "${unitObj.unitNumber} - ${unitObj.residentName}"
                    val extraCardsList = extraCardsForUnitText
                        .split(",", "،", " ")
                        .map { it.trim() }
                        .filter { it.length >= 4 }
                    val allExtraMappings = (selectedRelatedCards + extraCardsList).map { card ->
                        PayerUnitMappingEntity(card, singleSelectedUnitId, unitTitle)
                    }
                    if (allExtraMappings.isNotEmpty()) {
                        onMultiUnitCreditSplit(emptyList(), allExtraMappings)
                    }
                }

                onSave(selectedCategory, finalNote, transferOverride, singleSelectedUnitId, applyToBuildingAccounting, assignUnitToAllPayerDeposits, receiptPath)
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
    onAddCategory: (String, Boolean, String) -> Unit,
    onDeleteCategory: (String) -> Unit
) {
    var newName by remember { mutableStateOf("") }
    var isExpense by remember { mutableStateOf(true) }
    var selectedScope by remember { mutableStateOf("شخصی") }

    // فیلتر کردن لیست پایین دقیقاً بر اساس دکمه «مخصوص شخصی» یا «مخصوص ساختمان»
    val filteredCategoryList = remember(categories, selectedScope) {
        categories.filter { it.scope == selectedScope }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("مدیریت سرفصل‌های مجزای شخصی و ساختمان", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = selectedScope == "شخصی",
                        onClick = { selectedScope = "شخصی" },
                        label = { Text("مخصوص شخصی", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = selectedScope == "مدیریت ساختمان",
                        onClick = { selectedScope = "مدیریت ساختمان" },
                        label = { Text("مخصوص ساختمان", style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("نام سرفصل جدید برای $selectedScope") },
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
                            onAddCategory(t, isExpense, selectedScope)
                            newName = ""
                        }
                    }) { Text("افزودن") }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text("لیست سرفصل‌های $selectedScope (${filteredCategoryList.size}):", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                LazyColumn(modifier = Modifier.heightIn(max = 210.dp)) {
                    items(filteredCategoryList) { cat ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${cat.name} • [${cat.scope}]", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
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

/**
 * ناظر پیامک سازگار با چرخه حیات (Lifecycle-Aware):
 * فقط زمانی که برنامه روی صفحه باز است فعال می‌شود و هنگام قفل شدن گوشی یا خروج از برنامه
 * به‌طور خودکار غیرفعال می‌گردد تا هیچ‌گونه مصرف باتری در پس‌زمینه نداشته باشد.
 */
@Composable
fun LifecycleAwareSmsObserver(
    hasPermission: Boolean,
    onNewBankTransaction: (BankTransaction) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnNewTransaction by rememberUpdatedState(onNewBankTransaction)

    DisposableEffect(context, lifecycleOwner, hasPermission) {
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

        var isRegistered = false
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START && !isRegistered) {
                val intentFilter = IntentFilter(Telephony.Sms.Intents.SMS_RECEIVED_ACTION).apply { priority = 999 }
                ContextCompat.registerReceiver(context, receiver, intentFilter, ContextCompat.RECEIVER_EXPORTED)
                isRegistered = true
            } else if (event == Lifecycle.Event.ON_STOP && isRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) { }
                isRegistered = false
            }
        }

        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (isRegistered) {
                try {
                    context.unregisterReceiver(receiver)
                } catch (_: Exception) { }
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
        Icon(Icons.Rounded.Security, contentDescription = null, tint = Color(0xFF0F2942), modifier = Modifier.size(56.dp))
        Spacer(modifier = Modifier.height(16.dp))
        Text("دسترسی به پیامک‌ها و اعلان‌ها", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "برای استخراج خودکار اسناد بانکی، مدیریت شارژمان ساختمان، کشف مغایرت‌ها و رسم نمودارها، مجوزهای مورد نیاز را تایید کنید.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color.Gray
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onRequestPermission, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F2942))) {
            Text("اعطای مجوزهای مورد نیاز")
        }
    }
}


@Composable
fun AccountToBankPickerDialog(
    discoveredAccountNumbers: List<String>,
    initialAccount: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String) -> Unit
) {
    var accountInput by remember { mutableStateOf(initialAccount) }
    var bankSearchQuery by remember { mutableStateOf("") }
    var selectedBank by remember { mutableStateOf(IranBankSmsParser.ALL_IRANIAN_BANKS.first()) }

    val filteredBanks = remember(bankSearchQuery) {
        val q = IranBankSmsParser.normalizePersian(bankSearchQuery.trim())
        if (q.isEmpty()) IranBankSmsParser.ALL_IRANIAN_BANKS
        else IranBankSmsParser.ALL_IRANIAN_BANKS.filter {
            IranBankSmsParser.normalizePersian(it).contains(q, ignoreCase = true)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اتصال شماره حساب به بانک", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "با اتصال این شماره حساب به بانک، تمام پیامک‌های قبلی و بعدی دارای این شماره حساب به‌صورت خودکار زیرمجموعه آن بانک ثبت می‌شوند:",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                OutlinedTextField(
                    value = accountInput,
                    onValueChange = { accountInput = it },
                    label = { Text("شماره حساب موجود در پیامک") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (discoveredAccountNumbers.size > 1) {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(discoveredAccountNumbers) { acc ->
                            FilterChip(
                                selected = accountInput == acc,
                                onClick = { accountInput = acc },
                                label = { Text(acc, style = MaterialTheme.typography.labelSmall) }
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = bankSearchQuery,
                    onValueChange = { bankSearchQuery = it },
                    placeholder = { Text("جستجو در بین تمام بانک‌های ایران...", style = MaterialTheme.typography.bodySmall) },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                LazyColumn(modifier = Modifier.heightIn(max = 220.dp)) {
                    items(filteredBanks) { bank ->
                        val isSel = selectedBank == bank
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isSel) Color(0xFFE0F2F1) else Color.Transparent)
                                .clickable { selectedBank = bank }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = bank,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSel) Color(0xFF00695C) else Color(0xFF1C2434)
                            )
                            if (isSel) {
                                Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Color(0xFF00796B), modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val cleanAcc = accountInput.trim()
                    if (cleanAcc.isNotEmpty()) {
                        onConfirm(cleanAcc, selectedBank)
                    }
                }
            ) { Text("اتصال و دسته‌بندی خودکار") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}


@Composable
fun DatabaseAuthGateScreen(
    onUnlocked: (ByteArray) -> Unit
) {
    val context = LocalContext.current
    val isConfigured = remember { DatabaseSecurityManager.isCredentialsConfigured(context) }
    var username by remember { mutableStateOf(DatabaseSecurityManager.getSavedUsername(context)) }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF071930), Color(0xFF0F2942), Color(0xFF004D40))
                )
            )
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            shape = RoundedCornerShape(22.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    color = Color(0xFF091E3A),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.size(62.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.LockPerson, contentDescription = null, tint = Color(0xFFFFB300), modifier = Modifier.size(34.dp))
                    }
                }

                Text(
                    text = "هم‌تراز • گاوصندوق مالی رمزنگاری‌شده",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color(0xFF091E3A)
                )

                Text(
                    text = if (isConfigured) {
                        "برای رمزگشایی دیتابیس و دسترسی به اسناد مالی و حساب ساختمان، نام کاربری و رمز عبور خود را وارد کنید:"
                    } else {
                        "برای محافظت کامل از دیتابیس مالی (رمزنگاری SQLCipher)، یک نام کاربری و رمز عبور اختصاصی تعیین کنید:"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.Gray
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMessage = "" },
                    label = { Text("نام کاربری") },
                    leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = "" },
                    label = { Text("رمز عبور") },
                    leadingIcon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                if (!isConfigured) {
                    OutlinedTextField(
                        value = confirmPassword,
                        onValueChange = { confirmPassword = it; errorMessage = "" },
                        label = { Text("تکرار رمز عبور") },
                        leadingIcon = { Icon(Icons.Rounded.VerifiedUser, contentDescription = null) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (errorMessage.isNotEmpty()) {
                    Text(
                        text = errorMessage,
                        color = Color(0xFFC62828),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        if (username.isBlank() || password.length < 4) {
                            errorMessage = "نام کاربری را وارد کنید (رمز عبور حداقل ۴ کاراکتر)"
                            return@Button
                        }
                        if (!isConfigured) {
                            if (password != confirmPassword) {
                                errorMessage = "رمز عبور و تکرار آن یکسان نیستند"
                                return@Button
                            }
                            val key = DatabaseSecurityManager.setupCredentials(context, username, password)
                            onUnlocked(key)
                        } else {
                            val (key, errMsg) = DatabaseSecurityManager.verifyAndDeriveKey(context, username, password)
                            if (key != null) {
                                onUnlocked(key)
                            } else {
                                errorMessage = errMsg.ifBlank { "نام کاربری یا رمز عبور اشتباه است!" }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                ) {
                    Icon(Icons.Rounded.LockOpen, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (isConfigured) "ورود و رمزگشایی دیتابیس" else "ایجاد رمزنگاری و ورود به برنامه",
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
