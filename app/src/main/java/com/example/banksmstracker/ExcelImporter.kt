package com.example.banksmstracker

import android.content.Context
import android.net.Uri
import android.util.Xml
import jxl.Workbook
import jxl.WorkbookSettings
import org.xmlpull.v1.XmlPullParser
import saman.zamani.persiandate.PersianDate
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

data class StatementImportResult(
    val detectedHeaderAccount: String = "",
    val detectedOwnerName: String = "",
    val resolvedAccountKey: String = "",
    val records: List<ImportedRecordEntity> = emptyList()
)

object ExcelImporter {

    private val counterCardRegex = Regex("""(?:شماره کارت طرف مقابل|شماره کارت|کارت طرف مقابل|شماره حساب طرف مقابل)\s*[:=\-]?\s*([\d*\-]{6,22})""")
    private val counterNameRegex = Regex("""(?:نام کامل طرف مقابل|نام طرف مقابل)\s*[:=\-]?\s*([^،,\n\r|]+)""")
    private val counterBankRegex = Regex("""(?:نام بانک طرف مقابل)\s*[:=\-]?\s*([^،,\n\r|]+)""")
    private val pan16CardRegex = Regex("""\b([56]\d{15})\b""")
    private val fallbackCardRegex = Regex("""([\d]{4}[*\-\s]{0,3}[\d]{4,12})""")

    fun parseBankStatementWithHeader(
        context: Context,
        uri: Uri,
        fallbackAccountKey: String,
        existingProfiles: List<AccountProfileEntity>,
        accountBankMappings: Map<String, String>,
        existingTransactions: List<BankTransaction>
    ): StatementImportResult {
        val rows = try {
            extractRowsFromStatementBytes(context, uri)
        } catch (_: Throwable) {
            emptyList()
        }
        return processParsedRows(
            rows = rows,
            fallbackAccountKey = fallbackAccountKey,
            existingProfiles = existingProfiles,
            accountBankMappings = accountBankMappings,
            existingTransactions = existingTransactions
        )
    }

    fun parseBankStatementFile(
        context: Context,
        uri: Uri,
        targetAccountKey: String,
        existingTransactions: List<BankTransaction>
    ): List<ImportedRecordEntity> {
        return parseBankStatementWithHeader(
            context = context,
            uri = uri,
            fallbackAccountKey = targetAccountKey,
            existingProfiles = emptyList(),
            accountBankMappings = emptyMap(),
            existingTransactions = existingTransactions
        ).records
    }

    private fun extractRowsFromStatementBytes(context: Context, uri: Uri): List<List<String>> {
        val rawBytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return emptyList()
        if (rawBytes.size < 4) return emptyList()

        // ۱. تشخیص فرمت باینری اکسل ۹۷-۲۰۰۳ (OLE2 BIFF8 با هدر D0 CF 11 E0 - خروجی همراه بام و بانک ملی)
        val isOle2Xls = (rawBytes[0].toInt() and 0xFF) == 0xD0 &&
                (rawBytes[1].toInt() and 0xFF) == 0xCF &&
                (rawBytes[2].toInt() and 0xFF) == 0x11 &&
                (rawBytes[3].toInt() and 0xFF) == 0xE0
        if (isOle2Xls) {
            val xlsRows = extractRowsFromBinaryXls(rawBytes)
            if (xlsRows.isNotEmpty()) return xlsRows
        }

        // ۲. تشخیص فرمت اکسل مدرن XLSX (فایل ZIP با هدر 50 4B 03 04) با تجزیه‌گر بومی اندروید XmlPullParser
        val isZipXlsx = (rawBytes[0].toInt() and 0xFF) == 0x50 &&
                (rawBytes[1].toInt() and 0xFF) == 0x4B &&
                (rawBytes[2].toInt() and 0xFF) == 0x03 &&
                (rawBytes[3].toInt() and 0xFF) == 0x04
        if (isZipXlsx) {
            val xlsxRows = extractRowsFromXlsxNative(rawBytes)
            if (xlsxRows.isNotEmpty()) return xlsxRows
        }

        // ۳. رمزگشایی متنی UTF-8 یا Windows-1256 برای جدول‌های HTML و فایل‌های CSV
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

        val lowerSample = decodedText.take(2048).lowercase()
        if (lowerSample.contains("<table") || lowerSample.contains("<tr") || lowerSample.contains("<html")) {
            val htmlRows = extractRowsFromHtmlTable(decodedText)
            if (htmlRows.isNotEmpty()) return htmlRows
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

    /**
     * خواندن مستقیم فایل‌های باینری .xls (مثل خروجی همراه بام بانک ملی) با JExcelAPI
     */
    private fun extractRowsFromBinaryXls(rawBytes: ByteArray): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var workbook: Workbook? = null
        try {
            val settings = WorkbookSettings().apply {
                suppressWarnings = true
                encoding = "UTF-8"
            }
            workbook = Workbook.getWorkbook(ByteArrayInputStream(rawBytes), settings)
            if (workbook.numberOfSheets > 0) {
                val sheet = workbook.getSheet(0)
                for (r in 0 until sheet.rows) {
                    val rowCells = mutableListOf<String>()
                    for (c in 0 until sheet.columns) {
                        val content = sheet.getCell(c, r).contents ?: ""
                        rowCells.add(IranBankSmsParser.normalizePersian(content.trim()))
                    }
                    if (rowCells.any { it.isNotBlank() }) {
                        rows.add(rowCells)
                    }
                }
            }
        } catch (_: Throwable) {
        } finally {
            try {
                workbook?.close()
            } catch (_: Throwable) { }
        }
        return rows
    }

    /**
     * خواندن بومی فایل‌های .xlsx بدون وابستگی به POI یا java.awt با استفاده از ZipInputStream و XmlPullParser اندروید
     */
    private fun extractRowsFromXlsxNative(rawBytes: ByteArray): List<List<String>> {
        val sharedStrings = mutableListOf<String>()
        var sheetXmlBytes: ByteArray? = null

        ZipInputStream(ByteArrayInputStream(rawBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                when (entry.name) {
                    "xl/sharedStrings.xml" -> {
                        val xmlBytes = zis.readBytes()
                        sharedStrings.addAll(parseSharedStringsXml(xmlBytes))
                    }
                    "xl/worksheets/sheet1.xml" -> {
                        sheetXmlBytes = zis.readBytes()
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }

        val sheetBytes = sheetXmlBytes ?: return emptyList()
        return parseWorksheetXml(sheetBytes, sharedStrings)
    }

    private fun parseSharedStringsXml(xmlBytes: ByteArray): List<String> {
        val list = mutableListOf<String>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
        var event = parser.eventType
        var inSi = false
        val sb = StringBuilder()
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "si") {
                        inSi = true
                        sb.clear()
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inSi) sb.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "si") {
                        list.add(IranBankSmsParser.normalizePersian(sb.toString().trim()))
                        inSi = false
                    }
                }
            }
            event = parser.next()
        }
        return list
    }

    private fun parseWorksheetXml(xmlBytes: ByteArray, sharedStrings: List<String>): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val parser = Xml.newPullParser()
        parser.setInput(ByteArrayInputStream(xmlBytes), "UTF-8")
        var event = parser.eventType
        var currentRow = mutableMapOf<Int, String>()
        var currentColIdx = 0
        var cellType = ""
        var inValue = false
        val valBuilder = StringBuilder()

        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "row" -> currentRow = mutableMapOf()
                        "c" -> {
                            cellType = parser.getAttributeValue(null, "t") ?: ""
                            val ref = parser.getAttributeValue(null, "r") ?: ""
                            currentColIdx = columnRefToIndex(ref, currentRow.size)
                            valBuilder.clear()
                        }
                        "v", "t" -> {
                            inValue = true
                            valBuilder.clear()
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    if (inValue) valBuilder.append(parser.text ?: "")
                }
                XmlPullParser.END_TAG -> {
                    when (parser.name) {
                        "v", "t" -> inValue = false
                        "c" -> {
                            val rawVal = valBuilder.toString().trim()
                            val resolved = if (cellType == "s") {
                                val idx = rawVal.toIntOrNull()
                                if (idx != null && idx in sharedStrings.indices) sharedStrings[idx] else rawVal
                            } else {
                                IranBankSmsParser.normalizePersian(rawVal)
                            }
                            currentRow[currentColIdx] = resolved
                        }
                        "row" -> {
                            if (currentRow.isNotEmpty()) {
                                val maxCol = currentRow.keys.maxOrNull() ?: 0
                                val rowList = (0..maxCol).map { currentRow[it] ?: "" }
                                if (rowList.any { it.isNotBlank() }) {
                                    rows.add(rowList)
                                }
                            }
                        }
                    }
                }
            }
            event = parser.next()
        }
        return rows
    }

    private fun columnRefToIndex(cellRef: String, fallback: Int): Int {
        val letters = cellRef.takeWhile { it.isLetter() }.uppercase()
        if (letters.isEmpty()) return fallback
        var col = 0
        for (ch in letters) {
            col = col * 26 + (ch.code - 'A'.code + 1)
        }
        return (col - 1).coerceAtLeast(0)
    }

    private fun extractRowsFromHtmlTable(html: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val trRegex = Regex("""<tr[^>]*>(.*?)</tr>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val tdRegex = Regex("""<t[dh][^>]*>(.*?)</t[dh]>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        val tagStripRegex = Regex("""<[^>]+>""")

        for (trMatch in trRegex.findAll(html)) {
            val rowCells = mutableListOf<String>()
            for (tdMatch in tdRegex.findAll(trMatch.groupValues[1])) {
                val cellText = IranBankSmsParser.normalizePersian(
                    tdMatch.groupValues[1]
                        .replace(tagStripRegex, " ")
                        .replace("&nbsp;", " ")
                        .replace("&zwnj;", "")
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                )
                rowCells.add(cellText)
            }
            if (rowCells.any { it.isNotBlank() }) {
                rows.add(rowCells)
            }
        }
        return rows
    }

    private fun processParsedRows(
        rows: List<List<String>>,
        fallbackAccountKey: String,
        existingProfiles: List<AccountProfileEntity>,
        accountBankMappings: Map<String, String>,
        existingTransactions: List<BankTransaction>
    ): StatementImportResult {
        val newRecords = mutableListOf<ImportedRecordEntity>()

        var detectedHeaderAccount = ""
        var detectedOwnerName = ""
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
            if (headerRowIndex == -1 && i < 15) {
                for (cIdx in cols.indices) {
                    val cell = cols[cIdx].trim()
                    if (cell.contains("شماره حساب")) {
                        val inlineDigits = Regex("""(\d{6,20})""").find(cell)?.groupValues?.get(1)
                        val nextCellDigits = cols.getOrNull(cIdx + 1)?.replace(Regex("""[^\d]"""), "")
                        detectedHeaderAccount = when {
                            !inlineDigits.isNullOrEmpty() -> inlineDigits
                            !nextCellDigits.isNullOrEmpty() && nextCellDigits.length >= 6 -> nextCellDigits
                            else -> detectedHeaderAccount
                        }
                    }
                    if (cell.contains("نام و نام خانوادگی") || cell.contains("صاحب حساب")) {
                        val nextVal = cols.getOrNull(cIdx + 1)?.trim() ?: ""
                        if (nextVal.isNotEmpty() && !nextVal.contains(":")) {
                            detectedOwnerName = nextVal
                        }
                    }
                }
            }

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

        val resolvedAccountKey = resolveTargetAccountKey(
            detectedHeaderAccount = detectedHeaderAccount,
            fallbackAccountKey = fallbackAccountKey,
            existingProfiles = existingProfiles,
            accountBankMappings = accountBankMappings
        )

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
                isDeposit = typeStr.contains("واریز") || typeStr.contains("بستانکار") || typeStr.contains("+") || (signedAmt > 0 && !typeStr.contains("برداشت") && !typeStr.contains("بدهکار"))
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
            val counterBank = counterBankRegex.find(combinedFullText)?.groupValues?.get(1)?.trim() ?: ""

            val payerSummary = buildList {
                if (counterName.isNotEmpty()) add("نام: $counterName")
                if (counterCard.isNotEmpty()) add("کارت/حساب: $counterCard")
                if (counterBank.isNotEmpty()) add("بانک: $counterBank")
            }.joinToString(" • ")

            val cleanNote = when {
                payerSummary.isNotEmpty() && descPart.isNotEmpty() -> "$descPart ($payerSummary)"
                payerSummary.isNotEmpty() -> payerSummary
                descPart.isNotEmpty() -> descPart
                else -> combinedFullText.take(80)
            }

            val recId = "stmt_${resolvedAccountKey.hashCode()}_${normalizedDate}_${shortTime}_${amount}_${balAmt}_$r"
            if (!existingRecordIds.contains(recId)) {
                val timestamp = shamsiToMillis(normalizedDate, shortTime) + r * 100L
                newRecords.add(
                    ImportedRecordEntity(
                        recordId = recId,
                        accountKey = resolvedAccountKey,
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
        return StatementImportResult(
            detectedHeaderAccount = detectedHeaderAccount,
            detectedOwnerName = detectedOwnerName,
            resolvedAccountKey = resolvedAccountKey,
            records = newRecords
        )
    }

    private fun resolveTargetAccountKey(
        detectedHeaderAccount: String,
        fallbackAccountKey: String,
        existingProfiles: List<AccountProfileEntity>,
        accountBankMappings: Map<String, String>
    ): String {
        if (detectedHeaderAccount.isBlank()) return fallbackAccountKey
        val headerCore = IranBankSmsParser.extractAccountCore(detectedHeaderAccount)

        val matchedProfile = existingProfiles.firstOrNull { prof ->
            val pDigits = prof.accountNumber.replace(Regex("""[^\d]"""), "")
            pDigits.isNotEmpty() && (
                IranBankSmsParser.isAccountDigitsMatch(detectedHeaderAccount, pDigits) ||
                IranBankSmsParser.isAccountDigitsMatch(headerCore, pDigits)
            )
        }
        if (matchedProfile != null) return matchedProfile.accountKey

        val mappedBank = accountBankMappings[detectedHeaderAccount]
            ?: accountBankMappings[headerCore]
            ?: accountBankMappings.entries.firstOrNull { (k, _) ->
                val kd = k.replace(Regex("""[^\d]"""), "")
                kd.length >= 5 && (detectedHeaderAccount.contains(kd) || kd.contains(headerCore))
            }?.value

        if (mappedBank != null) {
            return IranBankSmsParser.buildStableAccountKey(mappedBank, headerCore)
        }

        val fallbackBank = fallbackAccountKey.substringBefore(" - ").trim()
        if (fallbackBank.isNotEmpty() && fallbackBank != "سایر بانک‌ها") {
            return IranBankSmsParser.buildStableAccountKey(fallbackBank, headerCore)
        }

        return fallbackAccountKey
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
