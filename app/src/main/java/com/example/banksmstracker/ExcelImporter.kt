package com.example.banksmstracker

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import org.apache.poi.ss.usermodel.DataFormatter
import org.apache.poi.ss.usermodel.WorkbookFactory
import saman.zamani.persiandate.PersianDate
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.math.abs

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
        val fileName = getFileName(context, uri).lowercase()
        val rows: List<List<String>> = when {
            fileName.endsWith(".pdf") -> extractRowsFromPdf(context, uri)
            fileName.endsWith(".xls") || fileName.endsWith(".xlsx") -> extractRowsFromExcel(context, uri)
            else -> {
                try {
                    extractRowsFromExcel(context, uri)
                } catch (e: Exception) {
                    extractRowsFromCsv(context, uri)
                }
            }
        }

        return processParsedRows(rows, targetAccountKey, existingTransactions)
    }

    private fun extractRowsFromExcel(context: Context, uri: Uri): List<List<String>> {
        val allRows = mutableListOf<List<String>>()
        val formatter = DataFormatter()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val workbook = WorkbookFactory.create(inputStream)
            val sheet = workbook.getSheetAt(0)
            for (row in sheet) {
                val rowValues = mutableListOf<String>()
                val lastCell = row.lastCellNum.toInt().coerceAtLeast(0)
                for (c in 0 until lastCell) {
                    val cell = row.getCell(c)
                    val text = if (cell != null) formatter.formatCellValue(cell) else ""
                    rowValues.add(IranBankSmsParser.normalizePersian(text.trim()))
                }
                if (rowValues.any { it.isNotBlank() }) {
                    allRows.add(rowValues)
                }
            }
            workbook.close()
        }
        return allRows
    }

    private fun extractRowsFromCsv(context: Context, uri: Uri): List<List<String>> {
        val allRows = mutableListOf<List<String>>()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            reader.forEachLine { rawLine ->
                val line = IranBankSmsParser.normalizePersian(rawLine.replace("\uFEFF", ""))
                val cols = splitCsvLine(line)
                if (cols.any { it.isNotBlank() }) {
                    allRows.add(cols)
                }
            }
        }
        return allRows
    }

    private fun extractRowsFromPdf(context: Context, uri: Uri): List<List<String>> {
        PDFBoxResourceLoader.init(context.applicationContext)
        val allRows = mutableListOf<List<String>>()
        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val doc = PDDocument.load(inputStream)
            val stripper = PDFTextStripper()
            val fullText = IranBankSmsParser.normalizePersian(stripper.getText(doc))
            doc.close()

            val dateRegex = Regex("""(1[34]\d{2}/\d{1,2}/\d{1,2})""")
            val numRegex = Regex("""([\d,]{4,15})""")

            fullText.lines().forEach { line ->
                val dateMatch = dateRegex.find(line)
                if (dateMatch != null) {
                    val dateStr = dateMatch.groupValues[1]
                    val numbers = numRegex.findAll(line.replace(dateStr, ""))
                        .map { it.groupValues[1].replace(",", "").toLongOrNull() ?: 0L }
                        .filter { it >= 100L }
                        .toList()

                    if (numbers.isNotEmpty()) {
                        val isDep = line.contains("واریز") || line.contains("بستانکار") || line.contains("+")
                        val amount = numbers.first()
                        val balance = if (numbers.size >= 2) numbers.last() else 0L
                        allRows.add(
                            listOf(
                                dateStr,
                                if (isDep) "واریز" else "برداشت",
                                amount.toString(),
                                line.trim(),
                                balance.toString()
                            )
                        )
                    }
                }
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

        var detectedShortAcc = ""
        for (i in 0 until minOf(10, rows.size)) {
            val rowStr = rows[i].joinToString(" ")
            val accMatch = Regex("""(?:شماره حساب|حساب)\s*[:=\-]?\s*(\d{5,18})""").find(rowStr)
            if (accMatch != null) {
                detectedShortAcc = accMatch.groupValues[1].takeLast(5)
                break
            }
        }

        val resolvedAccountKey = if (detectedShortAcc.isNotEmpty()) {
            existingTransactions.firstOrNull { it.accountKey.endsWith(detectedShortAcc) }?.accountKey
                ?: selectedAccountKey
        } else {
            selectedAccountKey
        }

        val targetBankName = resolvedAccountKey.substringBefore(" - ").trim()
        val sameBankTransactions = existingTransactions.filter {
            it.accountKey == resolvedAccountKey ||
            it.bankName == targetBankName ||
            it.bankName.contains(targetBankName.removePrefix("بانک ")) ||
            targetBankName.contains(it.bankName.removePrefix("بانک "))
        }

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
                amount = parseCleanLong(cols.getOrNull(amountCol))
                val typeStr = cols.getOrNull(typeCol)?.trim() ?: ""
                isDeposit = typeStr.contains("واریز") || typeStr.contains("بستانکار") || typeStr.contains("+")
            } else if (debitCol != -1 || creditCol != -1) {
                val deb = if (debitCol != -1) parseCleanLong(cols.getOrNull(debitCol)) else 0L
                val cred = if (creditCol != -1) parseCleanLong(cols.getOrNull(creditCol)) else 0L
                if (cred > 0) {
                    isDeposit = true
                    amount = cred
                } else {
                    isDeposit = false
                    amount = deb
                }
            } else {
                val typeStr = cols.getOrNull(1) ?: ""
                isDeposit = typeStr.contains("واریز") || typeStr.contains("بستانکار")
                amount = parseCleanLong(cols.getOrNull(2))
            }

            if (amount < 100L) continue

            val balAmt = if (balanceCol != -1) parseCleanLong(cols.getOrNull(balanceCol))
            else parseCleanLong(cols.lastOrNull())

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

            val alreadyExists = sameBankTransactions.any { existing ->
                val sameType = (existing.type == TransactionType.DEPOSIT) == isDeposit ||
                        existing.type == TransactionType.INTERNAL
                val sameAmount = existing.amount == amount
                val sameDate = existing.persianDateOnly == normalizedDate
                val sameBalance = balAmt > 0 && existing.balance != null && abs(existing.balance - balAmt) <= 10L
                (sameDate && sameAmount && sameType) || (sameDate && sameBalance && sameAmount)
            }

            if (!alreadyExists) {
                val timestamp = shamsiToMillis(normalizedDate, shortTime) + r * 100L
                newRecords.add(
                    ImportedRecordEntity(
                        recordId = "stmt_${resolvedAccountKey.hashCode()}_${normalizedDate}_${amount}_$r",
                        accountKey = resolvedAccountKey,
                        amount = amount,
                        balance = if (balAmt > 0) balAmt else null,
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

    private fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                name = cursor.getString(nameIndex) ?: ""
            }
        }
        return name
    }

    private fun splitCsvLine(line: String): List<String> {
        val result = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"' -> inQuotes = !inQuotes
                (ch == ',' || ch == ';') && !inQuotes -> {
                    result.add(sb.toString())
                    sb.clear()
                }
                else -> sb.append(ch)
            }
        }
        result.add(sb.toString())
        return result
    }

    private fun parseCleanLong(str: String?): Long {
        if (str.isNullOrBlank()) return 0L
        val clean = str.substringBefore(".").replace(Regex("""[^\d]"""), "")
        return clean.toLongOrNull() ?: 0L
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
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
