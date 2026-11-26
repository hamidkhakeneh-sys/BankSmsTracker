package com.example.banksmstracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

val ChartPalette = listOf(
    Color(0xFF00897B),
    Color(0xFF1E88E5),
    Color(0xFFE53935),
    Color(0xFFFB8C00),
    Color(0xFF8E24AA),
    Color(0xFF43A047),
    Color(0xFF3949AB),
    Color(0xFF6D4C41),
    Color(0xFF546E7A)
)

enum class ChartViewMode(val title: String) {
    YEARLY_DRILLDOWN("نمای سالانه (ورود به ماه)"),
    MONTHLY_ALL("تمام ماه‌ها")
}

private val persianMonthNames = mapOf(
    "01" to "فروردین",
    "02" to "اردیبهشت",
    "03" to "خرداد",
    "04" to "تیر",
    "05" to "مرداد",
    "06" to "شهریور",
    "07" to "مهر",
    "08" to "آبان",
    "09" to "آذر",
    "10" to "دی",
    "11" to "بهمن",
    "12" to "اسفند"
)

fun formatPersianMonthShort(monthYear: String): String {
    val parts = monthYear.split("/")
    if (parts.size != 2) return monthYear
    val mName = persianMonthNames[parts[1]] ?: parts[1]
    return "$mName ${parts[0].takeLast(2)}"
}

fun formatPersianMonthOnly(monthYear: String): String {
    val parts = monthYear.split("/")
    if (parts.size != 2) return monthYear
    return persianMonthNames[parts[1]] ?: parts[1]
}

@Composable
fun InteractiveCategoryDonutChart(
    transactions: List<BankTransaction>,
    isExpenseMode: Boolean,
    periodBadgeLabel: String? = null
) {
    val filtered = remember(transactions, isExpenseMode) {
        transactions.filter {
            if (isExpenseMode) it.type == TransactionType.WITHDRAWAL
            else it.type == TransactionType.DEPOSIT
        }
    }

    val groupedData = remember(filtered) {
        filtered.groupBy { it.category.ifBlank { "دسته‌بندی نشده" } }
            .mapValues { entry -> entry.value.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
    }

    val totalSum = remember(groupedData) { groupedData.sumOf { it.second } }
    var selectedSliceIndex by remember(periodBadgeLabel, isExpenseMode) { mutableIntStateOf(-1) }

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
                Text(
                    text = if (isExpenseMode) "توزیع هزینه‌ها بر اساس سرفصل" else "منابع درآمد و واریزی‌ها",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleSmall,
                    color = Color(0xFF0F2942)
                )
                if (periodBadgeLabel != null) {
                    Surface(
                        color = Color(0xFFE0F2F1),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = periodBadgeLabel,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF00695C)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))

            if (totalSum == 0L) {
                Text(
                    text = "داده‌ای برای رسم نمودار در این بازه وجود ندارد.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.Gray,
                    modifier = Modifier.padding(vertical = 20.dp)
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(145.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            var startAngle = -90f
                            val strokeWidth = 30.dp.toPx()
                            val radius = (size.minDimension - strokeWidth) / 2f

                            groupedData.forEachIndexed { index, (_, amount) ->
                                val sweep = (amount.toFloat() / totalSum.toFloat()) * 360f
                                val color = ChartPalette[index % ChartPalette.size]
                                val isSelected = selectedSliceIndex == index

                                drawArc(
                                    color = color,
                                    startAngle = startAngle,
                                    sweepAngle = if (sweep > 2f) sweep - 1.5f else sweep,
                                    useCenter = false,
                                    topLeft = Offset(strokeWidth / 2f, strokeWidth / 2f),
                                    size = Size(radius * 2f, radius * 2f),
                                    style = Stroke(
                                        width = if (isSelected) strokeWidth + 8.dp.toPx() else strokeWidth,
                                        cap = StrokeCap.Butt
                                    )
                                )
                                startAngle += sweep
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (selectedSliceIndex in groupedData.indices) {
                                val (catName, amt) = groupedData[selectedSliceIndex]
                                val pct = ((amt.toDouble() / totalSum.toDouble()) * 100).toInt()
                                Text(catName, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text(
                                    text = "${formatCurrency(amt / 10)} تومان",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF00695C)
                                )
                                Text("($pct%)", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            } else {
                                Text("جمع کل", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(
                                    text = formatCurrency(totalSum / 10),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("تومان", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        groupedData.take(7).forEachIndexed { index, (catName, amt) ->
                            val color = ChartPalette[index % ChartPalette.size]
                            val pct = ((amt.toDouble() / totalSum.toDouble()) * 100).toInt()
                            val isSelected = selectedSliceIndex == index

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) color.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable { selectedSliceIndex = if (isSelected) -1 else index }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(catName, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                                }
                                Text("$pct%", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = color)
                            }
                        }
                    }
                }

                // نمایش باکس مبلغ دقیق به تومان هنگام کلیک روی هر بخش نمودار
                if (selectedSliceIndex in groupedData.indices) {
                    val (selName, selAmt) = groupedData[selectedSliceIndex]
                    val selColor = ChartPalette[selectedSliceIndex % ChartPalette.size]
                    Spacer(modifier = Modifier.height(10.dp))
                    Surface(
                        color = selColor.copy(alpha = 0.12f),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "سرفصل انتخابی: $selName",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF091E3A)
                            )
                            Text(
                                text = "${formatCurrency(selAmt / 10)} تومان",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = selColor
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * نمودار سلسله‌مراتبی هوشمند (سالانه -> ماهانه -> تفکیک سرفصل و روزها):
 * ۱. حالت پیش‌فرض (نمای سالانه): ابتدا ستون‌های سال‌ها (مثلاً ۱۴۰۳، ۱۴۰۴، ۱۴۰۵) را نشان می‌دهد.
 * ۲. با کلیک روی یک سال: وارد آن سال شده و ستون‌های ۱۲ ماه همان سال را نمایش می‌دهد (به همراه دکمه بازگشت به سال‌ها).
 * ۳. با کلیک روی هر ماه: آن ماه انتخاب شده و نمودار سرفصل‌ها و تراز مالی داشبورد به تفکیک همان ماه فیلتر می‌شوند!
 * ۴. همچنین سوییچر تغییر حالت بین «نمای سالانه (ورود به ماه)» و «تمام ماه‌ها» در بالای نمودار قرار دارد.
 */
@Composable
fun HierarchicalTimeBarChart(
    allScopedTransactions: List<BankTransaction>,
    viewMode: ChartViewMode,
    onViewModeChange: (ChartViewMode) -> Unit,
    selectedYear: String?,
    onSelectYear: (String?) -> Unit,
    selectedMonth: String?,
    onSelectMonth: (String?) -> Unit
) {
    val validTransactions = remember(allScopedTransactions) {
        allScopedTransactions.filter { it.type == TransactionType.DEPOSIT || it.type == TransactionType.WITHDRAWAL }
    }

    // داده‌های سطح سالانه
    val yearlyBars = remember(validTransactions) {
        validTransactions
            .groupBy { it.persianYear }
            .toList()
            .sortedBy { it.first }
    }

    // داده‌های سطح ماهانه (یا ماه‌های سال انتخاب‌شده، یا کل ماه‌ها در حالت MONTHLY_ALL)
    val monthlyBars = remember(validTransactions, viewMode, selectedYear) {
        val baseList = if (viewMode == ChartViewMode.YEARLY_DRILLDOWN && selectedYear != null) {
            validTransactions.filter { it.persianYear == selectedYear }
        } else {
            validTransactions
        }
        baseList
            .groupBy { it.persianMonthYear }
            .toList()
            .sortedBy { it.first }
    }

    val isShowingYears = viewMode == ChartViewMode.YEARLY_DRILLDOWN && selectedYear == null

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // نوار عنوان و سوییچر تغییر حالت سالانه / ماهانه
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.BarChart, contentDescription = null, tint = Color(0xFF0F2942), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = when {
                            isShowingYears -> "نمودار عملکرد سالانه"
                            viewMode == ChartViewMode.YEARLY_DRILLDOWN && selectedYear != null -> "ماه‌های سال $selectedYear"
                            else -> "نمودار ماهانه (کل دوره)"
                        },
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleSmall,
                        color = Color(0xFF0F2942)
                    )
                }

                if (selectedYear != null || selectedMonth != null) {
                    TextButton(
                        onClick = {
                            onSelectMonth(null)
                            onSelectYear(null)
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Icon(Icons.Rounded.FilterAltOff, contentDescription = null, modifier = Modifier.size(15.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("نمایش کل", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // سوییچر انتخاب نمای سالانه یا تمام ماه‌ها
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ChartViewMode.entries.forEach { mode ->
                    FilterChip(
                        selected = viewMode == mode,
                        onClick = {
                            onViewModeChange(mode)
                            onSelectMonth(null)
                            if (mode == ChartViewMode.MONTHLY_ALL) {
                                onSelectYear(null)
                            }
                        },
                        label = { Text(mode.title, style = MaterialTheme.typography.labelSmall) },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // مسیر راهنما (Breadcrumb) و دکمه بازگشت از ماه‌های یک سال به نمای سالانه
            if (viewMode == ChartViewMode.YEARLY_DRILLDOWN && selectedYear != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Surface(
                    color = Color(0xFFF0F4F8),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "سال‌ها",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF1565C0),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable {
                                    onSelectMonth(null)
                                    onSelectYear(null)
                                }
                            )
                            Text("  /  ", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            Text(
                                text = "سال $selectedYear",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F2942)
                            )
                            if (selectedMonth != null) {
                                Text("  /  ", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                Text(
                                    text = formatPersianMonthOnly(selectedMonth),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color(0xFF00796B)
                                )
                            }
                        }

                        Surface(
                            modifier = Modifier.clickable {
                                onSelectMonth(null)
                                onSelectYear(null)
                            },
                            color = Color.White,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Rounded.ArrowForward, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFF0F2942))
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("بازگشت به سال‌ها", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.TouchApp, contentDescription = null, tint = Color(0xFF00796B), modifier = Modifier.size(14.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = when {
                        isShowingYears -> "روی ستون هر سال بزنید تا وارد ماه‌های آن سال شوید"
                        selectedMonth == null -> "روی ستون هر ماه بزنید تا آمار و سرفصل‌های همان ماه فیلتر شود"
                        else -> "در حال نمایش تفکیک ${formatPersianMonthShort(selectedMonth)} (برای لغو دوباره لمس کنید)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF00796B)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            val activeBars = if (isShowingYears) yearlyBars else monthlyBars

            if (activeBars.isEmpty()) {
                Text("داده‌ای برای رسم نمودار وجود ندارد.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
            } else {
                val maxVal = activeBars.maxOf { (_, list) ->
                    val dep = list.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
                    val wit = list.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
                    maxOf(dep, wit, 1L)
                }

                // اسکرول افقی روان تا اگر تعداد ماه‌ها زیاد بود تمام ماه‌ها با خوانایی کامل قابل لمس باشند
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .height(155.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    activeBars.forEach { (keyLabel, list) ->
                        val dep = list.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
                        val wit = list.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
                        val depRatio = (dep.toFloat() / maxVal.toFloat()).coerceIn(0.06f, 1f)
                        val witRatio = (wit.toFloat() / maxVal.toFloat()).coerceIn(0.06f, 1f)

                        val isBarSelected = if (isShowingYears) false else (selectedMonth == keyLabel)
                        val displayTitle = when {
                            isShowingYears -> "سال $keyLabel"
                            viewMode == ChartViewMode.YEARLY_DRILLDOWN -> formatPersianMonthOnly(keyLabel)
                            else -> formatPersianMonthShort(keyLabel)
                        }

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .widthIn(min = 58.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isBarSelected) Color(0xFFE0F2F1) else Color(0xFFF8FAFC))
                                .then(
                                    if (isBarSelected) Modifier.border(1.5.dp, Color(0xFF00897B), RoundedCornerShape(10.dp))
                                    else Modifier.border(0.5.dp, Color(0xFFE2E8F0), RoundedCornerShape(10.dp))
                                )
                                .clickable {
                                    if (isShowingYears) {
                                        // ورود به سال انتخاب‌شده و نمایش ماه‌های آن
                                        onSelectYear(keyLabel)
                                        onSelectMonth(null)
                                    } else {
                                        // انتخاب یا لغو انتخاب ماه برای فیلتر کردن کل داشبورد و سرفصل‌ها
                                        onSelectMonth(if (isBarSelected) null else keyLabel)
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.height(105.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(15.dp)
                                        .fillMaxHeight(depRatio)
                                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                        .background(Color(0xFF00897B))
                                )
                                Box(
                                    modifier = Modifier
                                        .width(15.dp)
                                        .fillMaxHeight(witRatio)
                                        .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                        .background(Color(0xFFE53935))
                                )
                            }
                            Spacer(modifier = Modifier.height(5.dp))
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isBarSelected || isShowingYears) FontWeight.Bold else FontWeight.Medium,
                                color = if (isBarSelected) Color(0xFF00695C) else Color(0xFF1E293B),
                                maxLines = 1
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFF00897B), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("واریز (بستانکار)", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFE53935), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("برداشت (هزینه)", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}


/**
 * نمودار و کارت تعاملی موجودی حساب‌های بانکی (تفکیک حساب‌های شخصی از مدیریت ساختمان):
 * با کلیک روی هر بانک/حساب، تمام نمودارها و تراز مالی داشبورد به همان حساب فیلتر می‌شود.
 */
@Composable
fun BankBalancesInteractiveCard(
    allTransactions: List<BankTransaction>,
    selectedAccountKey: String?,
    onSelectAccountKey: (String?) -> Unit
) {
    // محاسبه آخرین موجودی هر حساب بر اساس جدیدترین تراکنش دارای مانده
    val accountBalances = remember(allTransactions) {
        allTransactions
            .groupBy { it.accountKey }
            .mapNotNull { (accKey, txList) ->
                val latestWithBal = txList.firstOrNull { it.balance != null }
                if (latestWithBal != null) {
                    Triple(accKey, latestWithBal.scope, latestWithBal.balance ?: 0L)
                } else null
            }
            .sortedByDescending { it.third }
    }

    val personalAccounts = remember(accountBalances) { accountBalances.filter { it.second != "مدیریت ساختمان" } }
    val buildingAccounts = remember(accountBalances) { accountBalances.filter { it.second == "مدیریت ساختمان" } }

    val personalTotal = remember(personalAccounts) { personalAccounts.sumOf { it.third } }
    val buildingTotal = remember(buildingAccounts) { buildingAccounts.sumOf { it.third } }
    val maxBal = remember(accountBalances) { (accountBalances.maxOfOrNull { it.third } ?: 1L).coerceAtLeast(1L) }

    if (accountBalances.isEmpty()) return

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.AccountBalanceWallet, contentDescription = null, tint = Color(0xFF091E3A), modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("موجودی تفکیکی حساب‌ها (کلیک = فیلتر نمودار)", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall, color = Color(0xFF091E3A))
                }
                if (selectedAccountKey != null) {
                    TextButton(
                        onClick = { onSelectAccountKey(null) },
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp)
                    ) {
                        Text("همه حساب‌ها", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // خلاصه جمع موجودی شخصی در برابر موجودی ساختمان
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    color = Color(0xFFE8EAF6),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("موجودی حساب‌های شخصی", style = MaterialTheme.typography.labelSmall, color = Color(0xFF1A237E))
                        Text("${formatCurrency(personalTotal / 10)} تومان", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold, color = Color(0xFF091E3A))
                    }
                }
                Surface(
                    color = Color(0xFFE0F2F1),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("موجودی حساب ساختمان", style = MaterialTheme.typography.labelSmall, color = Color(0xFF004D40))
                        Text("${formatCurrency(buildingTotal / 10)} تومان", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.ExtraBold, color = Color(0xFF00695C))
                    }
                }
            }

            // نمودار میله‌ای افقی موجودی تک‌تک حساب‌ها
            accountBalances.forEach { (accKey, scope, bal) ->
                val isSelected = selectedAccountKey == accKey
                val isBuilding = scope == "مدیریت ساختمان"
                val barColor = if (isBuilding) Color(0xFF00897B) else Color(0xFF1E88E5)
                val ratio = (bal.toFloat() / maxBal.toFloat()).coerceIn(0.04f, 1f)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (isSelected) barColor.copy(alpha = 0.14f) else Color(0xFFF8FAFC))
                        .then(
                            if (isSelected) Modifier.border(1.5.dp, barColor, RoundedCornerShape(10.dp))
                            else Modifier
                        )
                        .clickable { onSelectAccountKey(if (isSelected) null else accKey) }
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Text(accKey, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = Color(0xFF091E3A))
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = if (isBuilding) Color(0xFFB2DFDB) else Color(0xFFC5CAE9),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = if (isBuilding) "ساختمان" else "شخصی",
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF091E3A)
                                )
                            }
                        }
                        Text(
                            text = "${formatCurrency(bal / 10)} تومان",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = barColor
                        )
                    }

                    // نوار پیشرفت نموداری موجودی
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFFE2E8F0))
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(ratio)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(4.dp))
                                .background(barColor)
                        )
                    }
                }
            }
        }
    }
}
