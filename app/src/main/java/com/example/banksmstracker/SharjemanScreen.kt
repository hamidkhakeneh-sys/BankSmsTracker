package com.example.banksmstracker

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import saman.zamani.persiandate.PersianDate
import saman.zamani.persiandate.PersianDateFormat

data class UnitFinancialSummary(
    val unit: BuildingUnitEntity,
    val totalDebits: Long,
    val totalCredits: Long,
    val netBalance: Long
)

data class BuildingLedgerItem(
    val id: String,
    val title: String,
    val subtitle: String,
    val unitBadge: String,
    val payerAccountBadge: String, // نمایش شماره حساب/کارت واریزکننده در بخش ساختمان
    val amount: Long,
    val isDeposit: Boolean,
    val persianDate: String,
    val timestampMillis: Long,
    val manualEntryId: Int? = null,
    val linkedSmsTx: BankTransaction? = null
)

@Composable
fun SharjemanBuildingScreen(
    units: List<BuildingUnitEntity>,
    unitCharges: List<UnitChargeEntity>,
    manualEntries: List<ManualBuildingEntryEntity>,
    buildingTransactions: List<BankTransaction>,
    buildingCategories: List<CategoryEntity>,
    buildingName: String,
    autoMonthlyChargeEnabled: Boolean,
    onToggleAutoMonthlyCharge: (Boolean) -> Unit,
    onUpdateBuildingName: (String) -> Unit,
    onAddOrUpdateUnit: (BuildingUnitEntity) -> Unit,
    onBatchCreateUnits: (String, List<BuildingUnitEntity>) -> Unit,
    onDeleteUnit: (Int) -> Unit,
    onDeclareDebtBatch: (List<UnitChargeEntity>) -> Unit,
    onDeleteCharge: (Int) -> Unit,
    onAddManualEntry: (ManualBuildingEntryEntity) -> Unit,
    onAddManualEntriesBatch: (List<ManualBuildingEntryEntity>) -> Unit,
    onDeleteManualEntry: (Int) -> Unit,
    onSelectBankTransaction: (BankTransaction) -> Unit
) {
    val context = LocalContext.current
    var activeSubTab by remember { mutableIntStateOf(0) } // 0: واحدها و بیلان، 1: گردش صندوق ساختمان

    var showExpenseDialog by remember { mutableStateOf(false) }
    var showIncomeDialog by remember { mutableStateOf(false) }
    var showDebtDialog by remember { mutableStateOf(false) }
    var showCreditDialog by remember { mutableStateOf(false) }

    var showAddUnitDialog by remember { mutableStateOf(false) }
    var showBatchWizardDialog by remember { mutableStateOf(false) }
    var editingUnit by remember { mutableStateOf<BuildingUnitEntity?>(null) }
    var selectedUnitForDetail by remember { mutableStateOf<UnitFinancialSummary?>(null) }
    var showBuildingSettingsDialog by remember { mutableStateOf(false) }

    var ledgerSearch by remember { mutableStateOf("") }
    var ledgerTypeFilter by remember { mutableIntStateOf(0) }

    val unitMap = remember(units) { units.associateBy { it.id } }

    val unitSummaries = remember(units, unitCharges, manualEntries, buildingTransactions) {
        units.map { unit ->
            val chargesSum = unitCharges.filter { it.unitId == unit.id }.sumOf { it.amount }
            val smsPaymentsSum = buildingTransactions
                .filter { it.unitId == unit.id && it.type == TransactionType.DEPOSIT }
                .sumOf { it.amount }
            val manualCreditsSum = manualEntries
                .filter { it.entryType == "UNIT_CREDIT" && it.unitId == unit.id }
                .sumOf { it.amount }

            val initialDebit = if (unit.openingBalance < 0) -unit.openingBalance else 0L
            val initialCredit = if (unit.openingBalance > 0) unit.openingBalance else 0L

            val totalDebits = chargesSum + initialDebit
            val totalCredits = smsPaymentsSum + manualCreditsSum + initialCredit
            val net = totalCredits - totalDebits

            UnitFinancialSummary(
                unit = unit,
                totalDebits = totalDebits,
                totalCredits = totalCredits,
                netBalance = net
            )
        }
    }

    val buildingBankBalance = remember(buildingTransactions) {
        buildingTransactions
            .groupBy { it.accountKey }
            .values
            .sumOf { list -> list.firstOrNull { it.balance != null }?.balance ?: 0L }
    }
    val manualIncomeSum = remember(manualEntries) {
        manualEntries.filter { it.entryType == "INCOME" }.sumOf { it.amount }
    }
    val manualExpenseSum = remember(manualEntries) {
        manualEntries.filter { it.entryType == "EXPENSE" }.sumOf { it.amount }
    }
    val totalFundBalance = buildingBankBalance + manualIncomeSum - manualExpenseSum

    val totalUnitsDebt = remember(unitSummaries) {
        unitSummaries.filter { it.netBalance < 0 }.sumOf { -it.netBalance }
    }
    val totalUnitsCredit = remember(unitSummaries) {
        unitSummaries.filter { it.netBalance > 0 }.sumOf { it.netBalance }
    }
    val totalResidentsCount = remember(units) { units.sumOf { it.residentCount } }

    val combinedLedger = remember(buildingTransactions, manualEntries, unitMap, ledgerSearch, ledgerTypeFilter) {
        val itemsList = mutableListOf<BuildingLedgerItem>()

        buildingTransactions.forEach { tx ->
            if (tx.type == TransactionType.DEPOSIT || tx.type == TransactionType.WITHDRAWAL) {
                val unitObj = tx.unitId?.let { unitMap[it] }
                val mainTitle = when {
                    tx.type == TransactionType.DEPOSIT && unitObj != null ->
                        "واریز ${unitObj.unitNumber} • ${unitObj.residentName}"
                    tx.type == TransactionType.DEPOSIT && tx.userNote.isNotBlank() ->
                        tx.userNote
                    tx.type == TransactionType.DEPOSIT ->
                        "واریز به حساب ساختمان (لمس کنید تا واحد مشخص شود)"
                    tx.userNote.isNotBlank() -> tx.userNote
                    tx.category.isNotBlank() -> tx.category
                    else -> "برداشت از حساب ساختمان"
                }

                itemsList.add(
                    BuildingLedgerItem(
                        id = "sms_${tx.id}",
                        title = mainTitle,
                        subtitle = "${tx.accountKey}${if (tx.category.isNotBlank()) " • ${tx.category}" else ""}",
                        unitBadge = if (unitObj != null) "${unitObj.unitNumber} - ${unitObj.residentName}" else "",
                        payerAccountBadge = tx.payerIdentifier,
                        amount = tx.amount,
                        isDeposit = tx.type == TransactionType.DEPOSIT,
                        persianDate = tx.persianDate,
                        timestampMillis = tx.timestampMillis,
                        linkedSmsTx = tx
                    )
                )
            }
        }

        manualEntries.forEach { entry ->
            val unitObj = entry.unitId?.let { unitMap[it] }
            val isDep = entry.entryType == "INCOME" || entry.entryType == "UNIT_CREDIT"
            val titleText = when (entry.entryType) {
                "UNIT_CREDIT" -> if (unitObj != null) "بستانکاری ${unitObj.unitNumber} (${unitObj.residentName}) • ${entry.title}" else entry.title
                "INCOME" -> "درآمد صندوق • ${entry.title}"
                else -> entry.title
            }
            itemsList.add(
                BuildingLedgerItem(
                    id = "manual_${entry.id}",
                    title = titleText,
                    subtitle = entry.category.ifBlank { if (isDep) "ثبت دستی واریز/بستانکاری" else "هزینه نقدی صندوق" },
                    unitBadge = if (unitObj != null) "${unitObj.unitNumber} - ${unitObj.residentName}" else "",
                    payerAccountBadge = "",
                    amount = entry.amount,
                    isDeposit = isDep,
                    persianDate = entry.persianDate,
                    timestampMillis = entry.timestampMillis,
                    manualEntryId = entry.id
                )
            )
        }

        val q = ledgerSearch.trim()
        itemsList
            .filter { item ->
                val matchesType = when (ledgerTypeFilter) {
                    1 -> item.isDeposit
                    2 -> !item.isDeposit
                    else -> true
                }
                val matchesQuery = q.isEmpty() ||
                        item.title.contains(q, ignoreCase = true) ||
                        item.subtitle.contains(q, ignoreCase = true) ||
                        item.unitBadge.contains(q, ignoreCase = true) ||
                        item.payerAccountBadge.contains(q, ignoreCase = true) ||
                        item.amount.toString().contains(q)
                matchesType && matchesQuery
            }
            .sortedByDescending { it.timestampMillis }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // ۱. هدر اختصاصی «هم‌تراز ساختمان»
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF091E3A), Color(0xFF00695C))
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { showBuildingSettingsDialog = true }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "تنظیمات ساختمان", tint = Color.White)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = buildingName,
                                style = MaterialTheme.typography.titleLarge,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${units.size} واحد • $totalResidentsCount نفر",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFFB2DFDB)
                            )
                        }
                        Row {
                            IconButton(onClick = { showBatchWizardDialog = true }) {
                                Icon(Icons.Rounded.DomainAdd, contentDescription = "ساخت گروهی واحدها", tint = Color(0xFFFFD54F))
                            }
                            IconButton(onClick = {
                                editingUnit = null
                                showAddUnitDialog = true
                            }) {
                                Icon(Icons.Rounded.AddHome, contentDescription = "افزودن تک واحد", tint = Color.White)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        SharjemanActionIconButton(
                            icon = Icons.Rounded.RemoveCircleOutline,
                            label = "هزینه",
                            onClick = { showExpenseDialog = true }
                        )
                        SharjemanActionIconButton(
                            icon = Icons.Rounded.AddCircleOutline,
                            label = "درآمد",
                            onClick = { showIncomeDialog = true }
                        )
                        SharjemanActionIconButton(
                            icon = Icons.Rounded.ArrowCircleDown,
                            label = "اعلام بدهی",
                            onClick = {
                                if (units.isEmpty()) {
                                    Toast.makeText(context, "ابتدا واحدهای ساختمان را تعریف کنید", Toast.LENGTH_SHORT).show()
                                } else {
                                    showDebtDialog = true
                                }
                            }
                        )
                        SharjemanActionIconButton(
                            icon = Icons.Rounded.ArrowCircleUp,
                            label = "بستانکاری",
                            onClick = {
                                if (units.isEmpty()) {
                                    Toast.makeText(context, "ابتدا واحدهای ساختمان را تعریف کنید", Toast.LENGTH_SHORT).show()
                                } else {
                                    showCreditDialog = true
                                }
                            }
                        )
                    }
                }
            }
        }

        // ۲. کارت‌های تراز صندوق و بیلان واحدها
        item {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("موجودی کل صندوق ساختمان", style = MaterialTheme.typography.labelMedium, color = Color.Gray)
                            Text(
                                text = "حساب بانکی: ${formatCurrency(buildingBankBalance)} ریال",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF00796B)
                            )
                        }
                        Text(
                            text = "${formatCurrency(totalFundBalance)} ریال",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFF091E3A)
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text("بیلان واحدها", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(
                                text = "${formatCurrency(totalUnitsDebt)} ریال بدهکار",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE53935)
                            )
                            Text(
                                text = "${formatCurrency(totalUnitsCredit)} ریال بستانکار",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF00897B)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = activeSubTab == 0,
                        onClick = { activeSubTab = 0 },
                        label = { Text("واحدها و بیلان (${units.size})") },
                        leadingIcon = { Icon(Icons.Rounded.Apartment, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = activeSubTab == 1,
                        onClick = { activeSubTab = 1 },
                        label = { Text("گردش صندوق (${combinedLedger.size})") },
                        leadingIcon = { Icon(Icons.Rounded.SyncAlt, contentDescription = null, modifier = Modifier.size(16.dp)) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        if (activeSubTab == 0) {
            if (unitSummaries.isEmpty()) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Rounded.DomainAdd, contentDescription = null, tint = Color(0xFF00796B), modifier = Modifier.size(48.dp))
                            Text("راه‌اندازی سریع ساختمان", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = "نام ساختمان، تعداد واحدها و متراژ را وارد کنید تا تمام واحدها به‌صورت خودکار ساخته شوند و فقط نام مالک یا مستاجر را ثبت کنید:",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color.Gray
                            )
                            Button(
                                onClick = { showBatchWizardDialog = true },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ساخت گروهی و هوشمند واحدهای ساختمان")
                            }
                            OutlinedButton(
                                onClick = {
                                    editingUnit = null
                                    showAddUnitDialog = true
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("افزودن تکی یک واحد")
                            }
                        }
                    }
                }
            } else {
                items(unitSummaries, key = { it.unit.id }) { summary ->
                    val isDebtor = summary.netBalance < 0
                    val isCreditor = summary.netBalance > 0
                    val statusColor = when {
                        isDebtor -> Color(0xFFE53935)
                        isCreditor -> Color(0xFF00897B)
                        else -> Color(0xFF1565C0)
                    }
                    val statusText = when {
                        isDebtor -> "بدهکار: ${formatCurrency(-summary.netBalance)} ریال"
                        isCreditor -> "بستانکار: ${formatCurrency(summary.netBalance)} ریال"
                        else -> "تسویه کامل"
                    }

                    val specsText = buildString {
                        append(summary.unit.roleType)
                        if (summary.unit.floor.isNotBlank()) append("، طبقه ${summary.unit.floor}")
                        if (summary.unit.areaSqm > 0) append("، ${summary.unit.areaSqm} متر")
                        if (summary.unit.residentCount > 0) append("، ${summary.unit.residentCount} نفر")
                        if (summary.unit.phoneNumber.isNotBlank()) append(" • ${summary.unit.phoneNumber}")
                    }

                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable { selectedUnitForDetail = summary },
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${summary.unit.unitNumber} • ${summary.unit.residentName}",
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = specsText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }

                                Surface(
                                    modifier = Modifier.clickable { selectedUnitForDetail = summary },
                                    color = Color(0xFF091E3A),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = "جزئیات",
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "شارژ مصوب: ${formatCurrency(summary.unit.monthlyCharge)} ریال",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.DarkGray
                                )
                                Surface(
                                    color = statusColor.copy(alpha = 0.12f),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text(
                                        text = statusText,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = statusColor
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = ledgerSearch,
                        onValueChange = { ledgerSearch = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("جستجو در نام واحد، شماره کارت/حساب واریزکننده، عنوان یا مبلغ...", style = MaterialTheme.typography.bodySmall) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(selected = ledgerTypeFilter == 0, onClick = { ledgerTypeFilter = 0 }, label = { Text("همه گردش‌ها") })
                        FilterChip(selected = ledgerTypeFilter == 1, onClick = { ledgerTypeFilter = 1 }, label = { Text("واریزی‌های واحدها") })
                        FilterChip(selected = ledgerTypeFilter == 2, onClick = { ledgerTypeFilter = 2 }, label = { Text("هزینه‌ها") })
                    }
                }
            }

            if (combinedLedger.isEmpty()) {
                item {
                    Box(modifier = Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                        Text("تراکنشی در گردش صندوق ساختمان یافت نشد.", color = Color.Gray)
                    }
                }
            } else {
                items(combinedLedger, key = { it.id }) { item ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp)
                            .clickable {
                                if (item.linkedSmsTx != null) {
                                    onSelectBankTransaction(item.linkedSmsTx)
                                }
                            },
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
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (item.isDeposit && item.unitBadge.isEmpty() && item.linkedSmsTx != null) Color(0xFFE65100) else Color(0xFF1C2434)
                                    )
                                    Text(
                                        text = "${item.persianDate} • ${item.subtitle}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = Color.Gray
                                    )
                                }
                                Text(
                                    text = "${if (item.isDeposit) "+" else "-"}${formatCurrency(item.amount)} ریال",
                                    fontWeight = FontWeight.ExtraBold,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (item.isDeposit) Color(0xFF00897B) else Color(0xFFE53935)
                                )
                            }

                            // نمایش شماره حساب / کارت واریزکننده در صورت وجود + برچسب واحد
                            if (item.unitBadge.isNotEmpty() || item.payerAccountBadge.isNotEmpty() || item.manualEntryId != null) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (item.unitBadge.isNotEmpty()) {
                                            Surface(color = Color(0xFFE0F2F1), shape = RoundedCornerShape(6.dp)) {
                                                Row(
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Icon(Icons.Rounded.MeetingRoom, contentDescription = null, tint = Color(0xFF00695C), modifier = Modifier.size(13.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text(item.unitBadge, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = Color(0xFF004D40))
                                                }
                                            }
                                        }
                                        if (item.payerAccountBadge.isNotEmpty()) {
                                            Surface(color = Color(0xFFE3F2FD), shape = RoundedCornerShape(6.dp)) {
                                                Text(
                                                    text = "کارت/حساب واریزکننده: ${item.payerAccountBadge}",
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = Color(0xFF0D47A1),
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }

                                    if (item.manualEntryId != null) {
                                        IconButton(
                                            onClick = { onDeleteManualEntry(item.manualEntryId) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "حذف", tint = Color.Red, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ویزارد ساخت گروهی واحدهای ساختمان (نام ساختمان + تعداد واحد + متراژ واحدها -> تولید خودکار رکوردها)
    if (showBatchWizardDialog) {
        BatchCreateBuildingUnitsDialog(
            initialBuildingName = buildingName,
            onDismiss = { showBatchWizardDialog = false },
            onConfirmBatch = { newBName, generatedUnits ->
                onBatchCreateUnits(newBName, generatedUnits)
                showBatchWizardDialog = false
                Toast.makeText(context, "${generatedUnits.size} واحد برای «$newBName» ساخته شد", Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showExpenseDialog) {
        BuildingExpenseWithSplitDialog(
            units = units,
            categories = buildingCategories.filter { it.isExpense }.map { it.name },
            onDismiss = { showExpenseDialog = false },
            onSaveExpense = { entry, generatedUnitCharges ->
                onAddManualEntry(entry)
                if (generatedUnitCharges.isNotEmpty()) {
                    onDeclareDebtBatch(generatedUnitCharges)
                }
                showExpenseDialog = false
                val msg = if (generatedUnitCharges.isNotEmpty()) {
                    "هزینه ثبت شد و بین ${generatedUnitCharges.size} واحد تقسیم و بدهکار گردید"
                } else {
                    "هزینه از محل صندوق شارژ ثبت شد"
                }
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        )
    }

    if (showIncomeDialog) {
        ManualFundEntryDialog(
            isExpense = false,
            categories = buildingCategories.filter { !it.isExpense }.map { it.name },
            onDismiss = { showIncomeDialog = false },
            onSave = { entry ->
                onAddManualEntry(entry)
                showIncomeDialog = false
                Toast.makeText(context, "درآمد در صندوق ساختمان ثبت شد", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showDebtDialog) {
        DeclareBuildingDebtDialog(
            units = units,
            onDismiss = { showDebtDialog = false },
            onConfirmCharges = { chargesList ->
                onDeclareDebtBatch(chargesList)
                showDebtDialog = false
                Toast.makeText(context, "اعلام بدهی برای ${chargesList.size} واحد ثبت شد", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showCreditDialog) {
        UnitCreditDialog(
            units = units,
            onDismiss = { showCreditDialog = false },
            onConfirmCredits = { entriesList ->
                onAddManualEntriesBatch(entriesList)
                showCreditDialog = false
                Toast.makeText(context, "بستانکاری برای ${entriesList.size} واحد ثبت شد", Toast.LENGTH_SHORT).show()
            }
        )
    }

    if (showAddUnitDialog) {
        AddOrEditUnitDialog(
            existingUnit = editingUnit,
            onDismiss = { showAddUnitDialog = false },
            onSave = { unitEntity ->
                onAddOrUpdateUnit(unitEntity)
                showAddUnitDialog = false
            },
            onDelete = { id ->
                onDeleteUnit(id)
                showAddUnitDialog = false
            }
        )
    }

    if (showBuildingSettingsDialog) {
        var bName by remember { mutableStateOf(buildingName) }
        AlertDialog(
            onDismissRequest = { showBuildingSettingsDialog = false },
            title = { Text("تنظیمات ساختمان", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = bName,
                        onValueChange = { bName = it },
                        label = { Text("نام ساختمان (مثلاً: برج نگین / ساختمان ۱۳۳)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Surface(
                        color = Color(0xFFE0F2F1),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .padding(10.dp)
                                .clickable { onToggleAutoMonthlyCharge(!autoMonthlyChargeEnabled) },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("صدور خودکار شارژ در روز ۱ هر ماه", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                Text("در یکم هر ماه شمسی، مبلغ شارژ مصوب هر واحد به‌طور خودکار در بدهکاری آن واحد ثبت شود.", style = MaterialTheme.typography.labelSmall, color = Color.DarkGray)
                            }
                            Switch(
                                checked = autoMonthlyChargeEnabled,
                                onCheckedChange = { onToggleAutoMonthlyCharge(it) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    if (bName.isNotBlank()) onUpdateBuildingName(bName.trim())
                    showBuildingSettingsDialog = false
                }) { Text("ذخیره") }
            },
            dismissButton = {
                TextButton(onClick = { showBuildingSettingsDialog = false }) { Text("انصراف") }
            }
        )
    }

    // دیالوگ جزئیات یک واحد + نمایش شماره حساب/کارت واریزکننده در هر واریزی
    selectedUnitForDetail?.let { summary ->
        val chargesForUnit = unitCharges.filter { it.unitId == summary.unit.id }
        val smsPaymentsForUnit = buildingTransactions.filter { it.unitId == summary.unit.id && it.type == TransactionType.DEPOSIT }
        val manualCreditsForUnit = manualEntries.filter { it.entryType == "UNIT_CREDIT" && it.unitId == summary.unit.id }

        AlertDialog(
            onDismissRequest = { selectedUnitForDetail = null },
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${summary.unit.unitNumber} • ${summary.unit.residentName}", style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = "${summary.unit.roleType} | طبقه ${summary.unit.floor} | ${summary.unit.areaSqm} متر | ${summary.unit.phoneNumber}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray
                        )
                    }
                    Row {
                        IconButton(onClick = {
                            val shareText = buildString {
                                appendLine("صورت‌حساب ${summary.unit.unitNumber} (${summary.unit.residentName}) - $buildingName")
                                appendLine("جمع بدهی شارژ: ${formatCurrency(summary.totalDebits)} ریال")
                                appendLine("جمع پرداختی‌ها: ${formatCurrency(summary.totalCredits)} ریال")
                                if (summary.netBalance < 0) {
                                    appendLine("مانده بدهی قابل پرداخت: ${formatCurrency(-summary.netBalance)} ریال")
                                } else {
                                    appendLine("وضعیت حساب: بستانکار (${formatCurrency(summary.netBalance)} ریال)")
                                }
                            }
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "ارسال صورت‌حساب واحد"))
                        }) {
                            Icon(Icons.Rounded.Share, contentDescription = "اشتراک‌گذاری بیلان", tint = Color(0xFF1565C0))
                        }
                        IconButton(onClick = {
                            editingUnit = summary.unit
                            selectedUnitForDetail = null
                            showAddUnitDialog = true
                        }) {
                            Icon(Icons.Rounded.Edit, contentDescription = "ویرایش واحد", tint = Color(0xFF091E3A))
                        }
                    }
                }
            },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("بدهکاری‌های ثبت‌شده (${chargesForUnit.size}):", style = MaterialTheme.typography.labelMedium, color = Color(0xFFE53935), fontWeight = FontWeight.Bold)
                    }
                    items(chargesForUnit) { ch ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${ch.title} (${ch.persianDate})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("-${formatCurrency(ch.amount)}", color = Color(0xFFE53935), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { onDeleteCharge(ch.id) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                            }
                        }
                    }

                    item {
                        HorizontalDivider()
                        Text("واریزی‌های بانکی و بستانکاری‌های واحد:", style = MaterialTheme.typography.labelMedium, color = Color(0xFF00897B), fontWeight = FontWeight.Bold)
                    }
                    items(smsPaymentsForUnit) { pay ->
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("${pay.persianDate} (${pay.bankName})", style = MaterialTheme.typography.bodySmall)
                                Text("+${formatCurrency(pay.amount)}", color = Color(0xFF00897B), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            }
                            if (pay.payerIdentifier.isNotEmpty()) {
                                Text(
                                    text = "شماره حساب/کارت واریزکننده: ${pay.payerIdentifier}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF1565C0)
                                )
                            }
                        }
                    }
                    items(manualCreditsForUnit) { cr ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${cr.title} (${cr.persianDate})", style = MaterialTheme.typography.bodySmall)
                            Text("+${formatCurrency(cr.amount)}", color = Color(0xFF00897B), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { selectedUnitForDetail = null }) { Text("بستن") }
            }
        )
    }
}

/**
 * ویزارد ساخت گروهی واحدهای ساختمان:
 * کاربر نام ساختمان، تعداد واحدها و متراژ (یکسان یا جداگانه) را وارد می‌کند و سیستم تمام واحدها را تولید می‌کند
 * تا کاربر فقط نقش (مالک/مستاجر/خالی)، نام و شماره تماس (از دفترچه تلفن) را انتخاب کند.
 */
data class DraftUnitRow(
    val unitNumber: String,
    val floor: String,
    val areaText: String,
    val roleType: String,
    val residentName: String,
    val phoneNumber: String
)

@Composable
fun BatchCreateBuildingUnitsDialog(
    initialBuildingName: String,
    onDismiss: () -> Unit,
    onConfirmBatch: (String, List<BuildingUnitEntity>) -> Unit
) {
    val context = LocalContext.current
    var step by remember { mutableIntStateOf(1) }
    var bName by remember { mutableStateOf(initialBuildingName) }
    var unitCountText by remember { mutableStateOf("6") }
    var defaultAreaText by remember { mutableStateOf("120") }
    var defaultChargeDigits by remember { mutableStateOf("2000000") }

    var draftRows by remember { mutableStateOf<List<DraftUnitRow>>(emptyList()) }
    var activeContactRowIndex by remember { mutableIntStateOf(-1) }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null && activeContactRowIndex in draftRows.indices) {
            val (cName, cPhone) = extractContactNameAndPhone(context, contactUri)
            draftRows = draftRows.mapIndexed { idx, row ->
                if (idx == activeContactRowIndex) {
                    row.copy(
                        residentName = row.residentName.ifBlank { cName },
                        phoneNumber = cPhone.ifBlank { row.phoneNumber }
                    )
                } else row
            }
        }
    }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            contactPickerLauncher.launch(null)
        } else {
            Toast.makeText(context, "مجوز دسترسی به مخاطبین برای انتخاب شماره لازم است", Toast.LENGTH_SHORT).show()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (step == 1) "مرحله ۱: مشخصات کلی ساختمان" else "مرحله ۲: تکمیل مالک/مستاجر واحدها",
                style = MaterialTheme.typography.titleMedium
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (step == 1) {
                    OutlinedTextField(
                        value = bName,
                        onValueChange = { bName = it },
                        label = { Text("نام ساختمان (مثلاً: مجتمع باران / ساختمان ۱۳۳)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = unitCountText,
                        onValueChange = { unitCountText = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("تعداد کل واحدهای ساختمان") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = defaultAreaText,
                        onValueChange = { defaultAreaText = it.filter { c -> c.isDigit() } },
                        label = { Text("متراژ پیش‌فرض واحدها (متر مربع - قابل تغییر در مرحله بعد)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    RialAmountTextField(
                        rawDigitsValue = defaultChargeDigits,
                        onValueChange = { defaultChargeDigits = it },
                        label = "مبلغ شارژ ماهانه پیش‌فرض هر واحد"
                    )
                } else {
                    Text(
                        text = "واحدها تولید شدند. برای هر واحد وضعیت (مالک/مستاجر)، متراژ، نام و شماره تماس را وارد یا از دفترچه تلفن انتخاب کنید:",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.Gray
                    )
                    draftRows.forEachIndexed { index, row ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(row.unitNumber, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF091E3A))
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        listOf("مستاجر", "مالک", "خالی").forEach { rType ->
                                            FilterChip(
                                                selected = row.roleType == rType,
                                                onClick = {
                                                    draftRows = draftRows.mapIndexed { i, item ->
                                                        if (i == index) item.copy(roleType = rType) else item
                                                    }
                                                },
                                                label = { Text(rType, style = MaterialTheme.typography.labelSmall) }
                                            )
                                        }
                                    }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedTextField(
                                        value = row.residentName,
                                        onValueChange = { txt ->
                                            draftRows = draftRows.mapIndexed { i, item -> if (i == index) item.copy(residentName = txt) else item }
                                        },
                                        label = { Text("نام مالک/مستاجر", style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1.4f)
                                    )
                                    OutlinedTextField(
                                        value = row.areaText,
                                        onValueChange = { txt ->
                                            draftRows = draftRows.mapIndexed { i, item -> if (i == index) item.copy(areaText = txt.filter { c -> c.isDigit() }) else item }
                                        },
                                        label = { Text("متراژ", style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true,
                                        modifier = Modifier.weight(0.7f)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                    OutlinedTextField(
                                        value = row.phoneNumber,
                                        onValueChange = { txt ->
                                            draftRows = draftRows.mapIndexed { i, item -> if (i == index) item.copy(phoneNumber = txt) else item }
                                        },
                                        label = { Text("شماره تلفن", style = MaterialTheme.typography.labelSmall) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    FilledTonalIconButton(
                                        onClick = {
                                            activeContactRowIndex = index
                                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                                contactPickerLauncher.launch(null)
                                            } else {
                                                contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Rounded.Contacts, contentDescription = "انتخاب از دفترچه تلفن", tint = Color(0xFF00796B))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (step == 1) {
                Button(onClick = {
                    val count = (unitCountText.toIntOrNull() ?: 0).coerceIn(1, 100)
                    draftRows = (1..count).map { num ->
                        DraftUnitRow(
                            unitNumber = "واحد $num",
                            floor = "$num",
                            areaText = defaultAreaText,
                            roleType = "مستاجر",
                            residentName = "",
                            phoneNumber = ""
                        )
                    }
                    step = 2
                }) { Text("تولید واحدها و مرحله بعد") }
            } else {
                Button(onClick = {
                    val chargeVal = defaultChargeDigits.toLongOrNull() ?: 0L
                    val entities = draftRows.map { row ->
                        BuildingUnitEntity(
                            unitNumber = row.unitNumber,
                            residentName = row.residentName.trim().ifEmpty { row.roleType },
                            monthlyCharge = chargeVal,
                            phoneNumber = row.phoneNumber.trim(),
                            floor = row.floor,
                            areaSqm = row.areaText.toIntOrNull() ?: 0,
                            residentCount = 1,
                            roleType = row.roleType
                        )
                    }
                    onConfirmBatch(bName.trim().ifEmpty { "ساختمان من" }, entities)
                }) { Text("ثبت نهایی ${draftRows.size} واحد") }
            }
        },
        dismissButton = {
            if (step == 2) {
                TextButton(onClick = { step = 1 }) { Text("مرحله قبل") }
            } else {
                TextButton(onClick = onDismiss) { Text("انصراف") }
            }
        }
    )
}

fun extractContactNameAndPhone(context: Context, contactUri: Uri): Pair<String, String> {
    var name = ""
    var phone = ""
    try {
        context.contentResolver.query(contactUri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID)
                val nameIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                val hasPhoneIdx = cursor.getColumnIndex(ContactsContract.Contacts.HAS_PHONE_NUMBER)
                if (nameIdx != -1) name = cursor.getString(nameIdx) ?: ""
                val contactId = if (idIdx != -1) cursor.getString(idIdx) else null
                val hasPhone = if (hasPhoneIdx != -1) cursor.getInt(hasPhoneIdx) > 0 else false

                if (contactId != null && hasPhone) {
                    context.contentResolver.query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                        "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
                        arrayOf(contactId),
                        null
                    )?.use { pCursor ->
                        if (pCursor.moveToFirst()) {
                            phone = pCursor.getString(0)?.replace(" ", "")?.replace("-", "") ?: ""
                        }
                    }
                }
            }
        }
    } catch (_: Exception) { }
    return Pair(name, phone)
}

@Composable
fun SharjemanActionIconButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable { onClick() }
    ) {
        Surface(
            color = Color.White.copy(alpha = 0.16f),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.size(50.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(imageVector = icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(26.dp))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(text = label, style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ManualFundEntryDialog(
    isExpense: Boolean,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (ManualBuildingEntryEntity) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var amountDigits by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "") }
    var note by remember { mutableStateOf("") }
    var receiptPath by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val saved = ImageReceiptHelper.compressAndSaveReceipt(context, uri, "manual_${System.currentTimeMillis()}")
            if (saved != null) receiptPath = saved
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isExpense) "ثبت هزینه ساختمان" else "ثبت درآمد صندوق", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                RialAmountTextField(
                    rawDigitsValue = amountDigits,
                    onValueChange = { amountDigits = it },
                    label = "مبلغ کل"
                )
                Text("برچسب / سرفصل:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                OutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (receiptPath.isEmpty()) "اضافه کردن پیوست (تصویر فاکتور)" else "تصویر پیوست شد (${ImageReceiptHelper.getFileSizeKb(receiptPath)} KB)", style = MaterialTheme.typography.labelSmall)
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("توضیحات") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = amountDigits.toLongOrNull() ?: 0L
                if (title.isNotBlank() && amt > 0L) {
                    val now = System.currentTimeMillis()
                    val pDate = PersianDate(now)
                    onSave(
                        ManualBuildingEntryEntity(
                            entryType = if (isExpense) "EXPENSE" else "INCOME",
                            title = title.trim(),
                            amount = amt,
                            category = selectedCategory,
                            receiptImagePath = receiptPath,
                            note = note.trim(),
                            persianDate = PersianDateFormat("Y/m/d - H:i").format(pDate),
                            persianMonthYear = PersianDateFormat("Y/m").format(pDate),
                            timestampMillis = now
                        )
                    )
                }
            }) { Text("ثبت نهایی") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
fun DeclareBuildingDebtDialog(
    units: List<BuildingUnitEntity>,
    onDismiss: () -> Unit,
    onConfirmCharges: (List<UnitChargeEntity>) -> Unit
) {
    val currentMonthShamsi = remember { PersianDateFormat("Y/m").format(PersianDate()) }
    var title by remember { mutableStateOf("شارژ ماهانه $currentMonthShamsi") }
    var splitMode by remember { mutableIntStateOf(0) }
    var totalAmountDigits by remember { mutableStateOf("") }
    var selectedUnitIds by remember(units) { mutableStateOf(units.map { it.id }.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("اعلام بدهی به واحدها", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان (مثال: شارژ ثابت، تعمیر موتورخانه)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("نحوه محاسبه بدهی:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                val modes = listOf("شارژ ماهانه مصوب هر واحد", "تقسیم مساوی مبلغ کل", "تقسیم بر اساس متراژ", "تقسیم بر اساس تعداد نفرات")
                modes.forEachIndexed { idx, label ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { splitMode = idx }
                    ) {
                        RadioButton(selected = splitMode == idx, onClick = { splitMode = idx })
                        Text(label, style = MaterialTheme.typography.bodySmall)
                    }
                }

                if (splitMode != 0) {
                    RialAmountTextField(
                        rawDigitsValue = totalAmountDigits,
                        onValueChange = { totalAmountDigits = it },
                        label = "مبلغ کل برای تقسیم"
                    )
                }

                Text("واحدهای مشمول (${selectedUnitIds.size} واحد):", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                units.forEach { u ->
                    val isChecked = selectedUnitIds.contains(u.id)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedUnitIds = if (isChecked) selectedUnitIds - u.id else selectedUnitIds + u.id
                        },
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = isChecked, onCheckedChange = {
                                selectedUnitIds = if (isChecked) selectedUnitIds - u.id else selectedUnitIds + u.id
                            })
                            Text("${u.unitNumber} (${u.residentName})", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("${u.areaSqm}m² | ${u.residentCount}نفر", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val targetUnits = units.filter { selectedUnitIds.contains(it.id) }
                if (targetUnits.isEmpty()) return@Button

                val now = System.currentTimeMillis()
                val pDate = PersianDate(now)
                val dateStr = PersianDateFormat("Y/m/d").format(pDate)
                val monthStr = PersianDateFormat("Y/m").format(pDate)
                val totalAmt = totalAmountDigits.toLongOrNull() ?: 0L

                val charges = when (splitMode) {
                    0 -> targetUnits.map { u ->
                        UnitChargeEntity(unitId = u.id, amount = u.monthlyCharge, title = title.trim(), persianDate = dateStr, persianMonthYear = monthStr, timestampMillis = now)
                    }
                    1 -> {
                        val perUnit = if (totalAmt > 0) totalAmt / targetUnits.size else 0L
                        targetUnits.map { u ->
                            UnitChargeEntity(unitId = u.id, amount = perUnit, title = title.trim(), persianDate = dateStr, persianMonthYear = monthStr, timestampMillis = now)
                        }
                    }
                    2 -> {
                        val totalArea = targetUnits.sumOf { it.areaSqm.coerceAtLeast(1) }.toDouble()
                        targetUnits.map { u ->
                            val share = ((u.areaSqm.coerceAtLeast(1) / totalArea) * totalAmt).toLong()
                            UnitChargeEntity(unitId = u.id, amount = share, title = title.trim(), persianDate = dateStr, persianMonthYear = monthStr, timestampMillis = now)
                        }
                    }
                    else -> {
                        val totalPeople = targetUnits.sumOf { it.residentCount.coerceAtLeast(1) }.toDouble()
                        targetUnits.map { u ->
                            val share = ((u.residentCount.coerceAtLeast(1) / totalPeople) * totalAmt).toLong()
                            UnitChargeEntity(unitId = u.id, amount = share, title = title.trim(), persianDate = dateStr, persianMonthYear = monthStr, timestampMillis = now)
                        }
                    }
                }.filter { it.amount > 0L }

                if (charges.isNotEmpty()) onConfirmCharges(charges)
            }) { Text("ثبت اعلام بدهی") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
fun UnitCreditDialog(
    units: List<BuildingUnitEntity>,
    onDismiss: () -> Unit,
    onConfirmCredits: (List<ManualBuildingEntryEntity>) -> Unit
) {
    var title by remember { mutableStateOf("") }
    var amountPerUnitDigits by remember { mutableStateOf("") }
    var selectedUnitIds by remember { mutableStateOf<Set<Int>>(emptySet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ثبت بستانکاری برای واحدها", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان بستانکاری") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                RialAmountTextField(
                    rawDigitsValue = amountPerUnitDigits,
                    onValueChange = { amountPerUnitDigits = it },
                    label = "مبلغ برای هر واحد انتخاب‌شده"
                )
                Text("انتخاب واحدهای مشمول:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                units.forEach { u ->
                    val isChecked = selectedUnitIds.contains(u.id)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            selectedUnitIds = if (isChecked) selectedUnitIds - u.id else selectedUnitIds + u.id
                        },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = isChecked, onCheckedChange = {
                            selectedUnitIds = if (isChecked) selectedUnitIds - u.id else selectedUnitIds + u.id
                        })
                        Text("${u.unitNumber} • ${u.residentName}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = amountPerUnitDigits.toLongOrNull() ?: 0L
                if (amt > 0L && selectedUnitIds.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    val pDate = PersianDate(now)
                    val dateStr = PersianDateFormat("Y/m/d - H:i").format(pDate)
                    val monthStr = PersianDateFormat("Y/m").format(pDate)
                    val entries = selectedUnitIds.map { uId ->
                        ManualBuildingEntryEntity(
                            entryType = "UNIT_CREDIT",
                            title = title.trim().ifEmpty { "بستانکاری واحد" },
                            amount = amt,
                            unitId = uId,
                            persianDate = dateStr,
                            persianMonthYear = monthStr,
                            timestampMillis = now
                        )
                    }
                    onConfirmCredits(entries)
                }
            }) { Text("ثبت بستانکاری") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
fun AddOrEditUnitDialog(
    existingUnit: BuildingUnitEntity?,
    onDismiss: () -> Unit,
    onSave: (BuildingUnitEntity) -> Unit,
    onDelete: (Int) -> Unit
) {
    val context = LocalContext.current
    var unitNum by remember { mutableStateOf(existingUnit?.unitNumber ?: "") }
    var resident by remember { mutableStateOf(existingUnit?.residentName ?: "") }
    var chargeDigits by remember { mutableStateOf(existingUnit?.monthlyCharge?.toString() ?: "") }
    var floor by remember { mutableStateOf(existingUnit?.floor ?: "") }
    var areaText by remember { mutableStateOf(existingUnit?.areaSqm?.takeIf { it > 0 }?.toString() ?: "") }
    var countText by remember { mutableStateOf(existingUnit?.residentCount?.toString() ?: "1") }
    var roleType by remember { mutableStateOf(existingUnit?.roleType ?: "مستاجر") }
    var phone by remember { mutableStateOf(existingUnit?.phoneNumber ?: "") }
    var openingDigits by remember { mutableStateOf(existingUnit?.openingBalance?.toString() ?: "0") }

    val contactPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickContact()
    ) { contactUri ->
        if (contactUri != null) {
            val (cName, cPhone) = extractContactNameAndPhone(context, contactUri)
            if (resident.isBlank() && cName.isNotBlank()) resident = cName
            if (cPhone.isNotBlank()) phone = cPhone
        }
    }

    val contactsPermLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) contactPickerLauncher.launch(null)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (existingUnit == null) "تعریف واحد جدید" else "ویرایش مشخصات واحد", style = MaterialTheme.typography.titleMedium)
                if (existingUnit != null) {
                    IconButton(onClick = { onDelete(existingUnit.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.Red)
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = unitNum,
                    onValueChange = { unitNum = it },
                    label = { Text("نام/شماره واحد (مثلاً: واحد ۱)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = resident,
                    onValueChange = { resident = it },
                    label = { Text("نام مالک یا مستاجر") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("مستاجر", "مالک", "واحد خالی").forEach { role ->
                        FilterChip(
                            selected = roleType == role,
                            onClick = { roleType = role },
                            label = { Text(role, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = floor,
                        onValueChange = { floor = it },
                        label = { Text("طبقه") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = areaText,
                        onValueChange = { areaText = it.filter { c -> c.isDigit() } },
                        label = { Text("متراژ (متر)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it.filter { c -> c.isDigit() } },
                        label = { Text("نفرات") },
                        singleLine = true,
                        modifier = Modifier.weight(0.8f)
                    )
                }
                RialAmountTextField(
                    rawDigitsValue = chargeDigits,
                    onValueChange = { chargeDigits = it },
                    label = "مبلغ شارژ ماهانه مصوب"
                )
                RialAmountTextField(
                    rawDigitsValue = openingDigits,
                    onValueChange = { openingDigits = it },
                    label = "مانده اولیه (منفی = بدهکار، مثبت = بستانکار)",
                    allowNegative = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text("شماره تلفن") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalIconButton(
                        onClick = {
                            if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) {
                                contactPickerLauncher.launch(null)
                            } else {
                                contactsPermLauncher.launch(Manifest.permission.READ_CONTACTS)
                            }
                        }
                    ) {
                        Icon(Icons.Rounded.Contacts, contentDescription = "انتخاب از دفترچه تلفن", tint = Color(0xFF00796B))
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val u = unitNum.trim()
                val c = chargeDigits.toLongOrNull() ?: 0L
                if (u.isNotEmpty()) {
                    onSave(
                        BuildingUnitEntity(
                            id = existingUnit?.id ?: 0,
                            unitNumber = u,
                            residentName = resident.trim().ifEmpty { roleType },
                            monthlyCharge = c,
                            phoneNumber = phone.trim(),
                            openingBalance = openingDigits.toLongOrNull() ?: 0L,
                            floor = floor.trim(),
                            areaSqm = areaText.toIntOrNull() ?: 0,
                            residentCount = countText.toIntOrNull() ?: 1,
                            roleType = roleType
                        )
                    )
                }
            }) { Text("ذخیره واحد") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BuildingExpenseWithSplitDialog(
    units: List<BuildingUnitEntity>,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSaveExpense: (ManualBuildingEntryEntity, List<UnitChargeEntity>) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf("") }
    var amountDigits by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(categories.firstOrNull() ?: "") }
    var note by remember { mutableStateOf("") }
    var receiptPath by remember { mutableStateOf("") }

    var shouldSplitAmongUnits by remember { mutableStateOf(false) }
    var splitMode by remember { mutableIntStateOf(0) }
    var selectedUnitIds by remember(units) { mutableStateOf(units.map { it.id }.toSet()) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val saved = ImageReceiptHelper.compressAndSaveReceipt(context, uri, "exp_${System.currentTimeMillis()}")
            if (saved != null) receiptPath = saved
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ثبت هزینه ساختمان", style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("عنوان هزینه (مثلاً: تعمیر پمپ آب، قبض برق)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                RialAmountTextField(
                    rawDigitsValue = amountDigits,
                    onValueChange = { amountDigits = it },
                    label = "مبلغ کل هزینه"
                )

                Surface(
                    color = Color(0xFFF0F4F8),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("نحوه محاسبه و تامین این هزینه:", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, color = Color(0xFF091E3A))

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { shouldSplitAmongUnits = false }
                        ) {
                            RadioButton(selected = !shouldSplitAmongUnits, onClick = { shouldSplitAmongUnits = false })
                            Column {
                                Text("از محل صندوق شارژ موجود", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                                Text("فقط از موجودی صندوق کسر می‌شود", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { shouldSplitAmongUnits = true }
                        ) {
                            RadioButton(selected = shouldSplitAmongUnits, onClick = { shouldSplitAmongUnits = true })
                            Column {
                                Text("تقسیم مبلغ بین واحدها (ثبت بدهکاری)", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = Color(0xFF00695C))
                                Text("سهم هر واحد محاسبه و به بدهکاری اضافه می‌شود", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }

                        if (shouldSplitAmongUnits && units.isNotEmpty()) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                listOf("مساوی", "متراژ", "نفرات").forEachIndexed { idx, label ->
                                    FilterChip(
                                        selected = splitMode == idx,
                                        onClick = { splitMode = idx },
                                        label = { Text(label, style = MaterialTheme.typography.labelSmall) }
                                    )
                                }
                            }
                            units.forEach { u ->
                                val checked = selectedUnitIds.contains(u.id)
                                Row(
                                    modifier = Modifier.fillMaxWidth().clickable {
                                        selectedUnitIds = if (checked) selectedUnitIds - u.id else selectedUnitIds + u.id
                                    },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(checked = checked, onCheckedChange = {
                                        selectedUnitIds = if (checked) selectedUnitIds - u.id else selectedUnitIds + u.id
                                    })
                                    Text("${u.unitNumber} (${u.residentName})", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }

                Text("سرفصل هزینه:", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    categories.forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { selectedCategory = cat },
                            label = { Text(cat, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }

                OutlinedButton(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.AddAPhoto, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(if (receiptPath.isEmpty()) "پیوست تصویر فاکتور (کم‌حجم)" else "تصویر پیوست شد (${ImageReceiptHelper.getFileSizeKb(receiptPath)} KB)", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it },
                    label = { Text("توضیحات تکمیلی") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val amt = amountDigits.toLongOrNull() ?: 0L
                val cleanTitle = title.trim()
                if (cleanTitle.isNotEmpty() && amt > 0L) {
                    val now = System.currentTimeMillis()
                    val pDate = PersianDate(now)
                    val dateOnlyStr = PersianDateFormat("Y/m/d").format(pDate)
                    val dateTimeStr = PersianDateFormat("Y/m/d - H:i").format(pDate)
                    val monthStr = PersianDateFormat("Y/m").format(pDate)

                    val expenseEntry = ManualBuildingEntryEntity(
                        entryType = "EXPENSE",
                        title = if (shouldSplitAmongUnits) "$cleanTitle (تقسیم بین واحدها)" else "$cleanTitle (از محل شارژ)",
                        amount = amt,
                        category = selectedCategory,
                        receiptImagePath = receiptPath,
                        note = note.trim(),
                        persianDate = dateTimeStr,
                        persianMonthYear = monthStr,
                        timestampMillis = now
                    )

                    val generatedCharges = if (shouldSplitAmongUnits && selectedUnitIds.isNotEmpty()) {
                        val targetUnits = units.filter { selectedUnitIds.contains(it.id) }
                        when (splitMode) {
                            0 -> {
                                val perUnit = amt / targetUnits.size
                                targetUnits.map { u ->
                                    UnitChargeEntity(unitId = u.id, amount = perUnit, title = "سهم هزینه: $cleanTitle", persianDate = dateOnlyStr, persianMonthYear = monthStr, timestampMillis = now)
                                }
                            }
                            1 -> {
                                val totalArea = targetUnits.sumOf { it.areaSqm.coerceAtLeast(1) }.toDouble()
                                targetUnits.map { u ->
                                    val share = ((u.areaSqm.coerceAtLeast(1) / totalArea) * amt).toLong()
                                    UnitChargeEntity(unitId = u.id, amount = share, title = "سهم هزینه (متراژ): $cleanTitle", persianDate = dateOnlyStr, persianMonthYear = monthStr, timestampMillis = now)
                                }
                            }
                            else -> {
                                val totalPeople = targetUnits.sumOf { it.residentCount.coerceAtLeast(1) }.toDouble()
                                targetUnits.map { u ->
                                    val share = ((u.residentCount.coerceAtLeast(1) / totalPeople) * amt).toLong()
                                    UnitChargeEntity(unitId = u.id, amount = share, title = "سهم هزینه (نفرات): $cleanTitle", persianDate = dateOnlyStr, persianMonthYear = monthStr, timestampMillis = now)
                                }
                            }
                        }.filter { it.amount > 0L }
                    } else {
                        emptyList()
                    }

                    onSaveExpense(expenseEntry, generatedCharges)
                }
            }) { Text("ثبت هزینه") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}
