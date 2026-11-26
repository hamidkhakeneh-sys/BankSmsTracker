package com.example.banksmstracker

import android.content.Context
import android.net.Uri
import saman.zamani.persiandate.PersianDate
import java.io.BufferedReader
import java.io.InputStreamReader

object ExcelImporter {

    private val cardOrAccRegex = Regex("""([\d]{4}[*\-\s]{1,6}[\d]{4}|[\d]{6,16})""")

    fun parseBankStatementCsv(
        context: Context,
        uri: Uri,
        targetAccountKey: String,
        existingTransactions: List<BankTransaction>
    ): List<ImportedRecordEntity> {
        val newRecords = mutableListOf<ImportedRecordEntity>()
        val existingSignatures = existingTransactions
            .filter { it.accountKey == targetAccountKey }
            .map { "${it.persianDateOnly}_${it.amount}_${it.type == TransactionType.DEPOSIT}" }
            .toSet()

        context.contentResolver.openInputStream(uri)?.use { inputStream ->
            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            var lineIndex = 0
            var dateCol = -1
            var descCol = -1
            var debitCol = -1
            var creditCol = -1
            var balanceCol = -1

            reader.forEachLine { rawLine ->
                val line = IranBankSmsParser.normalizePersian(rawLine.replace("\uFEFF", ""))
                val cols = splitCsvLine(line)
                if (cols.size < 3) return@forEachLine

                if (lineIndex == 0 || dateCol == -1) {
                    cols.forEachIndexed { idx, col ->
                        val c = col.trim()
                        if (c.contains("تاریخ")) dateCol = idx
                        if (c.contains("شرح") || c.contains("توضیح") || c.contains("بابت")) descCol = idx
                        if (c.contains("بدهکار") || c.contains("برداشت")) debitCol = idx
                        if (c.contains("بستانکار") || c.contains("واریز")) creditCol = idx
                        if (c.contains("مانده") || c.contains("موجودی")) balanceCol = idx
                    }
                    lineIndex++
                    if (dateCol != -1) return@forEachLine
                }

                val dIdx = if (dateCol != -1) dateCol else 0
                val descIdx = if (descCol != -1) descCol else 1
                val debIdx = if (debitCol != -1) debitCol else 2
                val credIdx = if (creditCol != -1) creditCol else 3
                val balIdx = if (balanceCol != -1) balanceCol else 4

                val rawDate = cols.getOrNull(dIdx)?.trim() ?: ""
                if (!rawDate.contains("/")) return@forEachLine

                val normalizedDate = normalizeShamsiDate(rawDate)
                val monthYear = normalizedDate.take(7)

                val desc = cols.getOrNull(descIdx)?.trim() ?: ""
                val debitAmt = parseCleanLong(cols.getOrNull(debIdx))
                val creditAmt = parseCleanLong(cols.getOrNull(credIdx))
                val balAmt = if (balIdx < cols.size) parseCleanLong(cols.getOrNull(balIdx)) else 0L

                val isDeposit = creditAmt > 0
                val amount = if (isDeposit) creditAmt else debitAmt
                if (amount < 100L) return@forEachLine

                val signature = "${normalizedDate}_${amount}_$isDeposit"
                if (!existingSignatures.contains(signature)) {
                    val payerId = cardOrAccRegex.find(desc)?.groupValues?.get(1)?.trim() ?: ""
                    val timestamp = shamsiToMillis(normalizedDate) + lineIndex * 1000L

                    newRecords.add(
                        ImportedRecordEntity(
                            recordId = "excel_${targetAccountKey.hashCode()}_${normalizedDate}_${amount}_$lineIndex",
                            accountKey = targetAccountKey,
                            amount = amount,
                            balance = if (balAmt > 0) balAmt else null,
                            isDeposit = isDeposit,
                            timestampMillis = timestamp,
                            persianDate = "$normalizedDate - 12:00",
                            persianMonthYear = monthYear,
                            description = desc,
                            payerIdentifier = payerId
                        )
                    )
                }
                lineIndex++
            }
        }
        return newRecords
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
        return str.replace(Regex("""[^\d]"""), "").toLongOrNull() ?: 0L
    }

    private fun normalizeShamsiDate(raw: String): String {
        val datePart = raw.substringBefore(" ").trim()
        val parts = datePart.split("/")
        if (parts.size != 3) return datePart
        val y = if (parts[0].length == 2) "14${parts[0]}" else parts[0]
        val m = parts[1].padStart(2, '0')
        val d = parts[2].padStart(2, '0')
        return "$y/$m/$d"
    }

    private fun shamsiToMillis(shamsiDate: String): Long {
        return try {
            val parts = shamsiDate.split("/")
            val pDate = PersianDate()
            pDate.shYear = parts[0].toInt()
            pDate.shMonth = parts[1].toInt()
            pDate.shDay = parts[2].toInt()
            pDate.time
        } catch (e: Exception) {
            System.currentTimeMillis()
        }
    }
}
