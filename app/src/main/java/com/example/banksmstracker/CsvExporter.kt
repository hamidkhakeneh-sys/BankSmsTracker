package com.example.banksmstracker

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import saman.zamani.persiandate.PersianDate
import saman.zamani.persiandate.PersianDateFormat
import java.io.File
import java.io.FileOutputStream

object CsvExporter {

    fun exportAndShareCsv(context: Context, transactions: List<BankTransaction>) {
        if (transactions.isEmpty()) return

        val todayShamsi = PersianDateFormat("Y-m-d_H-i").format(PersianDate())
        val fileName = "Bank_Transactions_$todayShamsi.csv"

        val exportDir = File(context.cacheDir, "exports").apply {
            if (!exists()) mkdirs()
        }
        val file = File(exportDir, fileName)

        FileOutputStream(file).use { out ->
            out.write(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))

            val sb = StringBuilder()
            sb.appendLine("ردیف,تاریخ و ساعت شمسی,بانک,نوع تراکنش,مبلغ (ریال),مانده حساب (ریال),توضیحات کاربر,سرشماره,متن کامل پیامک")

            transactions.forEachIndexed { index, tx ->
                val rowNum = index + 1
                val typeText = when (tx.type) {
                    TransactionType.DEPOSIT -> "واریز (بستانکار)"
                    TransactionType.WITHDRAWAL -> "برداشت (بدهکار)"
                    TransactionType.UNKNOWN -> "نامشخص"
                }
                val balanceText = tx.balance?.toString() ?: ""

                val safeDate = escapeCsv(tx.persianDate)
                val safeBank = escapeCsv(tx.bankName)
                val safeNote = escapeCsv(tx.userNote)
                val safeSender = escapeCsv(tx.sender)
                val safeRawBody = escapeCsv(tx.rawMessage)

                sb.appendLine("$rowNum,$safeDate,$safeBank,$typeText,${tx.amount},$balanceText,$safeNote,$safeSender,$safeRawBody")
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
            putExtra(Intent.EXTRA_SUBJECT, "گزارش تراکنش‌های بانکی ($todayShamsi)")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        context.startActivity(
            Intent.createChooser(shareIntent, "ذخیره یا ارسال فایل اکسل (CSV)")
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
