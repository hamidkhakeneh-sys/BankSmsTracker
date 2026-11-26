package com.example.banksmstracker

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import saman.zamani.persiandate.PersianDate
import saman.zamani.persiandate.PersianDateFormat

data class UnitFinancialSummary(
    val unit: BuildingUnitEntity,
    val totalDebits: Long,
    val totalCredits: Long,
    val netBalance: Long
)

@Composable
fun SharjemanBuildingScreen(
    units: List<BuildingUnitEntity>,
    unitCharges: List<UnitChargeEntity>,
    buildingTransactions: List<BankTransaction>,
    onAddOrUpdateUnit: (BuildingUnitEntity) -> Unit,
    onDeleteUnit: (Int) -> Unit,
    onChargeAllUnitsMonthly: (String, Long?) -> Unit,
    onChargeSingleUnit: (Int, String, Long) -> Unit,
    onDeleteCharge: (Int) -> Unit
) {
    var showAddUnitDialog by remember { mutableStateOf(false) }
    var editingUnit by remember { mutableStateOf<BuildingUnitEntity?>(null) }
    var showMonthlyBillDialog by remember { mutableStateOf(false) }
    var selectedUnitForDetail by remember { mutableStateOf<UnitFinancialSummary?>(null) }

    val unitSummaries = remember(units, unitCharges, buildingTransactions) {
        units.map { unit ->
            val chargesSum = unitCharges.filter { it.unitId == unit.id }.sumOf { it.amount }
            val paymentsSum = buildingTransactions
                .filter { it.unitId == unit.id && it.type == TransactionType.DEPOSIT }
                .sumOf { it.amount }

            val initialDebit = if (unit.openingBalance < 0) -unit.openingBalance else 0L
            val initialCredit = if (unit.openingBalance > 0) unit.openingBalance else 0L

            val totalDebits = chargesSum + initialDebit
            val totalCredits = paymentsSum + initialCredit
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
    val buildingTotalDeposits = remember(buildingTransactions) {
        buildingTransactions.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
    }
    val buildingTotalExpenses = remember(buildingTransactions) {
        buildingTransactions.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
    }
    val totalUnitsDebt = remember(unitSummaries) {
        unitSummaries.filter { it.netBalance < 0 }.sumOf { -it.netBalance }
    }
    val totalUnitsCredit = remember(unitSummaries) {
        unitSummaries.filter { it.netBalance > 0 }.sumOf { it.netBalance }
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF0F2942), Color(0xFF00695C))
                            )
                        )
                        .padding(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Apartment, contentDescription = null, tint = Color(0xFF80CBC4))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "ترازنامه مدیریت ساختمان (شارژمان)",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Column {
                                Text("موجودی حساب بانکی ساختمان:", style = MaterialTheme.typography.labelSmall, color = Color(0xFFB2DFDB))
                                Text(
                                    text = "${formatCurrency(buildingBankBalance)} ریال",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("مطالبات (بدهی واحدها):", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFCCBC))
                                Text(
                                    text = "${formatCurrency(totalUnitsDebt)} ریال",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFFFFAB91)
                                )
                            }
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = Color.White.copy(alpha = 0.2f))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("کل واریز: +${formatCurrency(buildingTotalDeposits)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFA5D6A7))
                            Text("کل هزینه: -${formatCurrency(buildingTotalExpenses)}", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEF9A9A))
                        }
                        if (totalUnitsCredit > 0) {
                            Text("پیش‌پرداخت (بستانکاری واحدها): ${formatCurrency(totalUnitsCredit)} ریال", style = MaterialTheme.typography.labelSmall, color = Color(0xFF80DEEA))
                        }
                    }
                }
            }
        }

        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        editingUnit = null
                        showAddUnitDialog = true
                    },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0F2942))
                ) {
                    Icon(Icons.Rounded.AddHome, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تعریف واحد", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = { showMonthlyBillDialog = true },
                    enabled = units.isNotEmpty(),
                    modifier = Modifier.weight(1.3f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00796B))
                ) {
                    Icon(Icons.Rounded.PostAdd, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("بدهکار کردن شارژ ماهانه", style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        item {
            Text(
                text = "وضعیت حساب واحدهای ساختمان (${units.size} واحد)",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF0F2942)
            )
        }

        if (unitSummaries.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(Icons.Rounded.DomainAdd, contentDescription = null, tint = Color(0xFF00796B), modifier = Modifier.size(44.dp))
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("هنوز واحدی تعریف نشده است", fontWeight = FontWeight.Bold)
                        Text(
                            text = "واحدهای ساختمان را تعریف کنید تا اول هر ماه شارژ را بدهکار کرده و واریزی‌های پیامکی هر واحد را به صورت خودکار بستانکار نمایید.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.Gray
                        )
                    }
                }
            }
        } else {
            items(unitSummaries, key = { it.unit.id }) { summary ->
                val isDebtor = summary.netBalance < 0
                val isCreditor = summary.netBalance > 0
                val statusColor = when {
                    isDebtor -> Color(0xFFC62828)
                    isCreditor -> Color(0xFF00796B)
                    else -> Color(0xFF1565C0)
                }
                val statusText = when {
                    isDebtor -> "بدهکار: ${formatCurrency(-summary.netBalance)} ریال"
                    isCreditor -> "بستانکار: ${formatCurrency(summary.netBalance)} ریال"
                    else -> "تسویه کامل"
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    color = Color(0xFFE0F2F1),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.MeetingRoom, contentDescription = null, tint = Color(0xFF00695C))
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text("${summary.unit.unitNumber} • ${summary.unit.residentName}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                    Text("شارژ مصوب: ${formatCurrency(summary.unit.monthlyCharge)} ریال", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            }
                            Row {
                                IconButton(onClick = {
                                    editingUnit = summary.unit
                                    showAddUnitDialog = true
                                }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "ویرایش", tint = Color.Gray, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "کل شارژ: ${formatCurrency(summary.totalDebits)} | پرداختی: ${formatCurrency(summary.totalCredits)}",
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

    if (showMonthlyBillDialog) {
        val currentMonthShamsi = remember { PersianDateFormat("Y/m").format(PersianDate()) }
        var billTitle by remember { mutableStateOf("شارژ ماهانه $currentMonthShamsi") }
        var useCustomAmount by remember { mutableStateOf(false) }
        var customAmtText by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showMonthlyBillDialog = false },
            title = { Text("صدور بدهی شارژ برای تمام واحدها", style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "با تایید این بخش، تمام ${units.size} واحد ساختمان به مبلغ شارژ ماهانه خود بدهکار می‌شوند:",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = billTitle,
                        onValueChange = { billTitle = it },
                        label = { Text("عنوان دوره (مثلاً: شارژ آذر ۱۴۰۵)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { useCustomAmount = !useCustomAmount }) {
                        Checkbox(checked = useCustomAmount, onCheckedChange = { useCustomAmount = it })
                        Text("مبلغ یکسان دلخواه برای همه واحدها", style = MaterialTheme.typography.bodySmall)
                    }
                    if (useCustomAmount) {
                        OutlinedTextField(
                            value = customAmtText,
                            onValueChange = { customAmtText = it.filter { ch -> ch.isDigit() } },
                            label = { Text("مبلغ به ریال") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    val overrideAmt = if (useCustomAmount) customAmtText.toLongOrNull() else null
                    onChargeAllUnitsMonthly(billTitle.trim().ifEmpty { "شارژ ماهانه" }, overrideAmt)
                    showMonthlyBillDialog = false
                }) { Text("ثبت بدهی همه واحدها") }
            },
            dismissButton = {
                TextButton(onClick = { showMonthlyBillDialog = false }) { Text("انصراف") }
            }
        )
    }

    selectedUnitForDetail?.let { summary ->
        val chargesForUnit = unitCharges.filter { it.unitId == summary.unit.id }
        val paymentsForUnit = buildingTransactions.filter { it.unitId == summary.unit.id && it.type == TransactionType.DEPOSIT }
        var extraChargeTitle by remember { mutableStateOf("") }
        var extraChargeAmt by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { selectedUnitForDetail = null },
            title = { Text("ریز حساب ${summary.unit.unitNumber} (${summary.unit.residentName})", style = MaterialTheme.typography.titleMedium) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 380.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Text("ثبت بدهی اختصاصی برای این واحد:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = extraChargeTitle,
                                onValueChange = { extraChargeTitle = it },
                                placeholder = { Text("بابت...", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.weight(1.2f)
                            )
                            OutlinedTextField(
                                value = extraChargeAmt,
                                onValueChange = { extraChargeAmt = it.filter { c -> c.isDigit() } },
                                placeholder = { Text("ریال", style = MaterialTheme.typography.bodySmall) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                val amt = extraChargeAmt.toLongOrNull() ?: 0L
                                if (amt > 0) {
                                    onChargeSingleUnit(summary.unit.id, extraChargeTitle.ifBlank { "بدهی متفرقه" }, amt)
                                    extraChargeTitle = ""
                                    extraChargeAmt = ""
                                }
                            }) {
                                Icon(Icons.Rounded.AddCircle, contentDescription = null, tint = Color(0xFF00796B))
                            }
                        }
                    }

                    item {
                        HorizontalDivider()
                        Text("بدهکاری‌های ثبت‌شده (${chargesForUnit.size}):", style = MaterialTheme.typography.labelMedium, color = Color(0xFFC62828))
                    }
                    items(chargesForUnit) { ch ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${ch.title} (${ch.persianDate})", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            Text("-${formatCurrency(ch.amount)}", color = Color(0xFFC62828), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { onDeleteCharge(ch.id) }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(14.dp), tint = Color.Gray)
                            }
                        }
                    }

                    item {
                        HorizontalDivider()
                        Text("واریزی‌های بستانکارشده (${paymentsForUnit.size}):", style = MaterialTheme.typography.labelMedium, color = Color(0xFF00796B))
                    }
                    items(paymentsForUnit) { pay ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${pay.persianDate} (${pay.bankName})", style = MaterialTheme.typography.bodySmall)
                            Text("+${formatCurrency(pay.amount)}", color = Color(0xFF00796B), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
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

@Composable
fun AddOrEditUnitDialog(
    existingUnit: BuildingUnitEntity?,
    onDismiss: () -> Unit,
    onSave: (BuildingUnitEntity) -> Unit,
    onDelete: (Int) -> Unit
) {
    var unitNum by remember { mutableStateOf(existingUnit?.unitNumber ?: "") }
    var resident by remember { mutableStateOf(existingUnit?.residentName ?: "") }
    var chargeText by remember { mutableStateOf(existingUnit?.monthlyCharge?.toString() ?: "") }
    var phone by remember { mutableStateOf(existingUnit?.phoneNumber ?: "") }
    var openingText by remember { mutableStateOf(existingUnit?.openingBalance?.toString() ?: "0") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(if (existingUnit == null) "تعریف واحد جدید" else "ویرایش واحد", style = MaterialTheme.typography.titleMedium)
                if (existingUnit != null) {
                    IconButton(onClick = { onDelete(existingUnit.id) }) {
                        Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = Color.Red)
                    }
                }
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = unitNum,
                    onValueChange = { unitNum = it },
                    label = { Text("شماره/نام واحد (مثلاً: واحد ۱)") },
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
                OutlinedTextField(
                    value = chargeText,
                    onValueChange = { chargeText = it.filter { c -> c.isDigit() } },
                    label = { Text("مبلغ شارژ ماهانه (ریال)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = openingText,
                    onValueChange = { openingText = it.filter { c -> c.isDigit() || c == '-' } },
                    label = { Text("مانده اولیه (منفی = بدهکار، مثبت = بستانکار)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("شماره موبایل (اختیاری)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(onClick = {
                val u = unitNum.trim()
                val c = chargeText.toLongOrNull() ?: 0L
                if (u.isNotEmpty()) {
                    onSave(
                        BuildingUnitEntity(
                            id = existingUnit?.id ?: 0,
                            unitNumber = u,
                            residentName = resident.trim().ifEmpty { "ساکن" },
                            monthlyCharge = c,
                            phoneNumber = phone.trim(),
                            openingBalance = openingText.toLongOrNull() ?: 0L
                        )
                    )
                }
            }) { Text("ذخیره واحد") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("انصراف") }
        }
    )
}
