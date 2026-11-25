package com.example.banksmstracker

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.sp

val ChartPalette = listOf(
    Color(0xFF1E88E5), // آبی
    Color(0xFFE53935), // قرمز
    Color(0xFF43A047), // سبز
    Color(0xFFFB8C00), // نارنجی
    Color(0xFF8E24AA), // بنفش
    Color(0xFF00ACC1), // فیروزه‌ای
    Color(0xFFFDD835), // زرد
    Color(0xFF6D4C41), // قهوه‌ای
    Color(0xFF546E7A)  // خاکستری آبی
)

@Composable
fun InteractiveCategoryDonutChart(
    transactions: List<BankTransaction>,
    isExpenseMode: Boolean
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
    var selectedSliceIndex by remember { mutableIntStateOf(-1) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (isExpenseMode) "نمودار توزیع هزینه‌ها بر اساس دسته‌بندی" else "نمودار منابع درآمد و واریزی‌ها",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (totalSum == 0L) {
                Text(
                    text = "داده‌ای برای رسم نمودار در این بخش وجود ندارد.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp)
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // نمودار حلقه‌ای پویا
                    Box(
                        modifier = Modifier.size(150.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            var startAngle = -90f
                            val strokeWidth = 34.dp.toPx()
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

                        // نمایش جزئیات بخش انتخاب‌شده در مرکز حلقه
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (selectedSliceIndex in groupedData.indices) {
                                val (catName, amt) = groupedData[selectedSliceIndex]
                                val pct = ((amt.toDouble() / totalSum.toDouble()) * 100).toInt()
                                Text(catName, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                                Text("$pct%", fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("جمع کل", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = formatCurrency(totalSum / 10),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("تومان", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    // راهنمای تعاملی دسته‌ها (با لمس هر ردیف، اسلایس مربوطه در نمودار برجسته می‌شود)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        groupedData.take(6).forEachIndexed { index, (catName, amt) ->
                            val color = ChartPalette[index % ChartPalette.size]
                            val pct = ((amt.toDouble() / totalSum.toDouble()) * 100).toInt()
                            val isSelected = selectedSliceIndex == index

                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(if (isSelected) color.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        selectedSliceIndex = if (isSelected) -1 else index
                                    }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Box(
                                        modifier = Modifier
                                            .size(10.dp)
                                            .clip(CircleShape)
                                            .background(color)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(catName, fontSize = 12.sp, maxLines = 1)
                                }
                                Text("$pct%", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = color)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun MonthlyComparisonBarChart(transactions: List<BankTransaction>) {
    val monthlyData = remember(transactions) {
        transactions
            .filter { it.type == TransactionType.DEPOSIT || it.type == TransactionType.WITHDRAWAL }
            .groupBy { it.persianMonthYear }
            .toList()
            .sortedBy { it.first }
            .takeLast(6) // ۶ ماه اخیر
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "روند ماهانه واریز و برداشت (۶ ماه اخیر)",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (monthlyData.isEmpty()) {
                Text("داده‌ای برای نمایش روند ماهانه وجود ندارد.", fontSize = 13.sp)
            } else {
                val maxVal = monthlyData.maxOf { (_, list) ->
                    val dep = list.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
                    val wit = list.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
                    maxOf(dep, wit, 1L)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.Bottom
                ) {
                    monthlyData.forEach { (monthLabel, list) ->
                        val dep = list.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
                        val wit = list.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
                        val depRatio = (dep.toFloat() / maxVal.toFloat()).coerceIn(0.05f, 1f)
                        val witRatio = (wit.toFloat() / maxVal.toFloat()).coerceIn(0.05f, 1f)

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.height(110.dp)
                            ) {
                                // ستون واریز (سبز)
                                Box(
                                    modifier = Modifier
                                        .width(14.dp)
                                        .fillMaxHeight(depRatio)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(Color(0xFF2E7D32))
                                )
                                // ستون برداشت (قرمز)
                                Box(
                                    modifier = Modifier
                                        .width(14.dp)
                                        .fillMaxHeight(witRatio)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(Color(0xFFC62828))
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(monthLabel, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFF2E7D32), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("واریز", fontSize = 12.sp)
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFC62828), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("برداشت", fontSize = 12.sp)
                }
            }
        }
    }
}
