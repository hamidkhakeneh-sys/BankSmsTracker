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

val ChartPalette = listOf(
    Color(0xFF1E88E5),
    Color(0xFFE53935),
    Color(0xFF43A047),
    Color(0xFFFB8C00),
    Color(0xFF8E24AA),
    Color(0xFF00ACC1),
    Color(0xFFFDD835),
    Color(0xFF6D4C41),
    Color(0xFF546E7A)
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
                text = if (isExpenseMode) "نمودار توزیع هزینه‌ها بر اساس سرفصل" else "نمودار منابع درآمد و واریزی‌ها",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (totalSum == 0L) {
                Text(
                    text = "داده‌ای برای رسم نمودار در این بخش وجود ندارد.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
                            val strokeWidth = 32.dp.toPx()
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
                                Text("$pct%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Text("جمع کل", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = formatCurrency(totalSum / 10),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("تومان", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(14.dp))

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
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
            .takeLast(6)
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
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (monthlyData.isEmpty()) {
                Text("داده‌ای برای نمایش روند ماهانه وجود ندارد.", style = MaterialTheme.typography.bodySmall)
            } else {
                val maxVal = monthlyData.maxOf { (_, list) ->
                    val dep = list.filter { it.type == TransactionType.DEPOSIT }.sumOf { it.amount }
                    val wit = list.filter { it.type == TransactionType.WITHDRAWAL }.sumOf { it.amount }
                    maxOf(dep, wit, 1L)
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(135.dp),
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
                                modifier = Modifier.height(105.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .width(14.dp)
                                        .fillMaxHeight(depRatio)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(Color(0xFF2E7D32))
                                )
                                Box(
                                    modifier = Modifier
                                        .width(14.dp)
                                        .fillMaxHeight(witRatio)
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                        .background(Color(0xFFC62828))
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(monthLabel, style = MaterialTheme.typography.labelSmall)
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
                    Text("واریز", style = MaterialTheme.typography.labelSmall)
                    Spacer(modifier = Modifier.width(16.dp))
                    Box(modifier = Modifier.size(10.dp).background(Color(0xFFC62828), CircleShape))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("برداشت", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
