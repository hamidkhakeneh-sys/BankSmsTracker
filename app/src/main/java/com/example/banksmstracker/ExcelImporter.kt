package com.example.banksmstracker

import android.content.Context
import android.net.Uri
import saman.zamani.persiandate.PersianDate
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

object ExcelImporter {

    private val counterCardRegex = Regex("""(?:شماره کارت طرف مقابل|شماره کارت|کارت طرف مقابل|شماره حساب طرف مقابل)\s*[:=\-]?\s*([\d*\-]{6,22})""")
    private val counterNameRegex = Regex("""(?:نام کامل طرف مقابل|نام طرف مقابل)\s*[:=\-]?\s*([^،,\n\r]+)""")
    private val pan16CardRegex = Regex("""\b([56]\d{15})\b""")
    private val fallbackCardRegex = Regex("""([\d]{4}[*\-\s]{0,3}[\d]{4,12})""")

    fun parseBankStatementFile(
        context: Context,
        uri: Uri,
        targetAccountKey: String,
        existingTransactions: List<BankTransaction>
    ): List<ImportedRecordEntity> {
        val rows = try {
            extractRowsFromTextOrCsv(context, uri)
        } catch (_: Throwable) {
            emptyList()
        }
        return processParsedRows(rows, targetAccountKey, existingTransactions)
    }

    private fun extractRowsFromTextOrCsv(context: Context, uri: Uri): List<List<String>> {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        if (rawBytes.isEmpty()) return emptyList()

        // تشخیص خودکار UTF-8 در برابر Windows-1256 (فارسی ویندوز در خروجی‌های بانکی)
        val decodedText = try {
            val utf8Str = String(rawBytes, Charsets.UTF_8)
            if (utf8Str.contains('\uFFFD')) {
                String(rawBytes, Charset.forName("windows-1256"))
            } else {
                utf8Str
            }
        } catch (_: Throwable) {
            String(rawBytes, Charsets.UTF_8)
        }

        val allRows = mutableListOf<List<String>>()
        BufferedReader(InputStreamReader(decodedText.byteInputStream(Charsets.UTF_8), Charsets.UTF_8)).forEachLine { rawLine ->
            val line = IranBankSmsParser.normalizePersian(rawLine.replace("\uFEFF", ""))
            val cols = splitDelimitedLine(line)
            if (cols.any { it.isNotBlank() }) {
                allRows.add(cols)
            }
        }
        return allRows
    }

    private fun processParsedRows(
        rows: List<List<String>>,
        selectedAccountKey: String,
        existingTransactions: List<BankTransaction>
    ): List<ImportedRecordEntity> {
        val newRecords = mutableListOf<ImportedRecordEntity>()

        var headerRowIndex = -1
        var dateCol = -1
        var timeCol = -1
        var typeCol = -1
        var amountCol = -1
        var debitCol = -1
        var creditCol = -1
        var descCol = -1
        var detailsCol = -1
        var extraInfoCol = -1
        var balanceCol = -1

        for (i in rows.indices) {
            val cols = rows[i]
            val hasDateHeader = cols.any { it.trim() == "تاریخ" || it.contains("تاریخ تراکنش") }
            val hasAmountOrDebitHeader = cols.any {
                it.trim() == "مبلغ" || it.contains("بدهکار") || it.contains("بستانکار") || it.contains("واریز")
            }
            if (hasDateHeader && hasAmountOrDebitHeader) {
                headerRowIndex = i
                cols.forEachIndexed { idx, col ->
                    val c = col.trim()
                    when {
                        c == "تاریخ" || c.contains("تاریخ") -> if (dateCol == -1) dateCol = idx
                        c == "زمان" || c.contains("ساعت") -> timeCol = idx
                        c == "نوع" || c.contains("نوع تراکنش") -> typeCol = idx
                        c == "مبلغ" || c.contains("مبلغ تراکنش") -> amountCol = idx
                        c.contains("بدهکار") || c.contains("برداشت") -> debitCol = idx
                        c.contains("بستانکار") || c.contains("واریز") -> creditCol = idx
                        c == "شرح" -> descCol = idx
                        c == "توضیحات" -> detailsCol = idx
                        c.contains("اطلاعات تکمیلی") -> extraInfoCol = idx
                        c == "مانده" || c.contains("موجودی") -> balanceCol = idx
                    }
                }
                break
            }
        }

        val startRow = if (headerRowIndex != -1) headerRowIndex + 1 else 0
        val existingRecordIds = existingTransactions.map { it.id }.toSet()

        for (r in startRow until rows.size) {
            val cols = rows[r]
            if (cols.size < 3) continue

            val rawDate = if (dateCol != -1) cols.getOrNull(dateCol)?.trim() ?: ""
            else cols.firstOrNull { it.matches(Regex(""".*1[34]\d{2}/\d{1,2}/\d{1,2}.*""")) }?.trim() ?: ""

            if (!rawDate.contains("/")) continue
            val normalizedDate = normalizeShamsiDate(rawDate)
            val monthYear = normalizedDate.take(7)

            val rawTime = if (timeCol != -1) cols.getOrNull(timeCol)?.trim() ?: "12:00" else "12:00"
            val shortTime = if (rawTime.length >= 5) rawTime.take(5) else "12:00"

            var isDeposit = false
            var amount = 0L

            if (amountCol != -1 && typeCol != -1) {
                val signedAmt = parseSignedLong(cols.getOrNull(amountCol))
                amount = kotlin.math.abs(signedAmt)
                val typeStr = cols.getOrNull(typeCol)?.trim() ?: ""
                isDeposit = typeStr.contains("واریز") || typeStr.contains("بستانکار") || typeStr.contains("+") || signedAmt > 0 && !typeStr.contains("برداشت") && !typeStr.contains("بدهکار")
            } else if (debitCol != -1 || creditCol != -1) {
                val deb = if (debitCol != -1) kotlin.math.abs(parseSignedLong(cols.getOrNull(debitCol))) else 0L
                val cred = if (creditCol != -1) kotlin.math.abs(parseSignedLong(cols.getOrNull(creditCol))) else 0L
                if (cred > 0) {
                    isDeposit = true
                    amount = cred
                } else {
                    isDeposit = false
                    amount = deb
                }
            } else {
                val typeStr = cols.getOrNull(1) ?: ""
                val signedAmt = parseSignedLong(cols.getOrNull(2))
                isDeposit = typeStr.contains("واریز") || typeStr.contains("بستانکار")
                amount = kotlin.math.abs(signedAmt)
            }

            if (amount < 100L) continue

            val balAmt = if (balanceCol != -1) parseSignedLong(cols.getOrNull(balanceCol))
            else parseSignedLong(cols.lastOrNull())

            val descPart = if (descCol != -1) cols.getOrNull(descCol)?.trim() ?: "" else cols.getOrNull(3)?.trim() ?: ""
            val detailsPart = if (detailsCol != -1) cols.getOrNull(detailsCol)?.trim() ?: "" else ""
            val extraPart = if (extraInfoCol != -1) cols.getOrNull(extraInfoCol)?.trim() ?: "" else ""

            val combinedFullText = listOf(descPart, detailsPart, extraPart).filter { it.isNotBlank() }.joinToString(" | ")

            val counterCard = counterCardRegex.find(combinedFullText)?.groupValues?.get(1)?.trim()
                ?: pan16CardRegex.find(combinedFullText)?.groupValues?.get(1)?.trim()
                ?: fallbackCardRegex.find(combinedFullText)?.groupValues?.get(1)?.trim()
                ?: ""
            val counterName = counterNameRegex.find(combinedFullText)?.groupValues?.get(1)?.trim() ?: ""

            val cleanNote = when {
                counterName.isNotEmpty() && descPart.isNotEmpty() -> "$descPart • $counterName"
                counterName.isNotEmpty() -> counterName
                descPart.isNotEmpty() -> descPart
                else -> combinedFullText.take(60)
            }

            val recId = "stmt_${selectedAccountKey.hashCode()}_${normalizedDate}_${shortTime}_${amount}_${balAmt}_$r"
            if (!existingRecordIds.contains(recId)) {
                val timestamp = shamsiToMillis(normalizedDate, shortTime) + r * 100L
                newRecords.add(
                    ImportedRecordEntity(
                        recordId = recId,
                        accountKey = selectedAccountKey,
                        amount = amount,
                        balance = if (balAmt != 0L) balAmt else null,
                        isDeposit = isDeposit,
                        timestampMillis = timestamp,
                        persianDate = "$normalizedDate - $shortTime",
                        persianMonthYear = monthYear,
                        description = cleanNote,
                        payerIdentifier = counterCard
                    )
                )
            }
        }
        return newRecords
    }

    private fun splitDelimitedLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                (ch == ',' || ch == ';' || ch == '\t') && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        result.add(sb.toString())
        return result
    }

    private fun parseSignedLong(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        val trimmed = str.trim()
        val isNegative = trimmed.startsWith("-") || (trimmed.startsWith("(") && trimmed.endsWith(")"))
        val clean = trimmed.substringBefore(".").replace(Regex("""[^\d]"""), "")
        val absVal = clean.toLongOrNull() ?: 0L
        return if (isNegative) -absVal else absVal
    }

    private fun normalizeShamsiDate(raw: String): String {
        val datePart = Regex("""(1[34]\d{2}/\d{1,2}/\d{1,2})""").find(raw)?.groupValues?.get(1) ?: raw
        val parts = datePart.split("/")
        if (parts.size != 3) return datePart
        val y = if (parts[0].length == 2) "14${parts[0]}" else parts[0]
        val m = parts[1].padStart(2, '0')
        val d = parts[2].padStart(2, '0')
        return "$y/$m/$d"
    }

    private fun shamsiToMillis(shamsiDate: String, timeStr: String): Long {
        return try {
            val parts = shamsiDate.split("/")
            val timeParts = timeStr.split(":")
            val pDate = PersianDate()
            pDate.shYear = parts[0].toInt()
            pDate.shMonth = parts[1].toInt()
            pDate.shDay = parts[2].toInt()
            pDate.hour = timeParts.getOrNull(0)?.toIntOrNull() ?: 12
            pDate.minute = timeParts.getOrNull(1)?.toIntOrNull() ?: 0
            pDate.time
        } catch (_: Throwable) {
            System.currentTimeMillis()
        }
    }
}
