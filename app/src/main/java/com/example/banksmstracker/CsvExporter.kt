package com.example.banksmstracker

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import saman.zamani.persiandate.PersianDate
import saman.zamani.persiandate.PersianDateFormat
import java.io.File
import java.io.FileOutputStream

object CsvExporter {

    fun exportAndShareCsv(context: Context, transactions: List<BankTransaction>, scopeTitle: String) {
        if (transactions.isEmpty()) return

        val todayShamsi = PersianDateFormat("Y-m-d_H-i").format(PersianDate())
        val fileName = "Accounting_Report_${scopeTitle}_$todayShamsi.csv"

        val exportDir = File(context.cacheDir, "exports").apply {
            if (!exists()) mkdirs()
        }
        val file = File(exportDir, fileName)

        FileOutputStream(file).use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

            val sb = StringBuilder()
            sb.appendLine("ردیف,صندوق,تاریخ و ساعت شمسی,بانک و حساب,نوع تراکنش,دسته‌بندی,مبلغ (ریال),مانده حساب (ریال),شناسه واریزکننده,توضیحات / واحد,منبع")

            transactions.forEachIndexed { index, tx ->
                val rowNum = index + 1
                val typeText = when (tx.type) {
                    TransactionType.DEPOSIT -> "واریز (بستانکار)"
                    TransactionType.WITHDRAWAL -> "برداشت (بدهکار)"
                    TransactionType.INTERNAL -> "انتقال بین حساب‌ها"
                    TransactionType.UNKNOWN -> "نامشخص"
                }
                val balanceText = tx.balance?.toString() ?: ""
                val sourceText = if (tx.isFromExcel) "اکسل بانکی" else "پیامک"

                val safeScope = escapeCsv(tx.scope)
                val safeDate = escapeCsv(tx.persianDate)
                val safeAccount = escapeCsv(tx.accountKey)
                val safeCategory = escapeCsv(tx.category)
                val safePayer = escapeCsv(tx.payerIdentifier)
                val safeNote = escapeCsv(tx.userNote.ifEmpty { tx.transferPairInfo })

                sb.appendLine("$rowNum,$safeScope,$safeDate,$safeAccount,$typeText,$safeCategory,${tx.amount},$balanceText,$safePayer,$safeNote,$sourceText")
            }

            out.write(sb.toString().toByteArray(Charsets.UTF_8))
        }

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "گزارش حسابداری ($scopeTitle - $todayShamsi)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(shareIntent, "ذخیره یا ارسال گزارش اکسل (CSV)")
        )
    }

    private fun escapeCsv(value: String): String {
        val cleaned = value
            .replace("\r\n", " ")
            .replace("\n", " ")
            .replace("\"", "\"\"")
        return "\"$cleaned\""
    }
}
