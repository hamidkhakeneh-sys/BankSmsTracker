package com.example.banksmstracker

import android.content.Context
import android.provider.Telephony
import saman.zamani.persiandate.PersianDate
import saman.zamani.persiandate.PersianDateFormat
import java.text.NumberFormat
import java.util.Locale

enum class TransactionType {
    DEPOSIT, WITHDRAWAL, UNKNOWN
}

data class BankTransaction(
    val id: String,
    val sender: String,
    val bankName: String,
    val amount: Long,
    val balance: Long?,
    val type: TransactionType,
    val persianDate: String,
    val rawMessage: String,
    val userNote: String = ""
)

fun formatCurrency(amount: Long): String {
    return NumberFormat.getNumberInstance(Locale.US).format(amount)
}

object IranBankSmsParser {
    private fun normalizeDigits(input: String): String {
        return input
            .replace('۰', '0').replace('۱', '1').replace('۲', '2')
            .replace('۳', '3').replace('۴', '4').replace('۵', '5')
            .replace('۶', '6').replace('۷', '7').replace('۸', '8')
            .replace('۹', '9')
            .replace('٠', '0').replace('١', '1').replace('٢', '2')
            .replace('٣', '3').replace('٤', '4').replace('٥', '5')
            .replace('٦', '6').replace('٧', '7').replace('٨', '8')
            .replace('٩', '9')
    }

    private val balanceRegex = Regex(
        """(?:مانده|موجودی|م\.حساب|باقیمانده)\s*[:=\-]?\s*([\d,]+)\s*(?:ریال|تومان)?"""
    )

    private val transactionPatterns = listOf(
        Regex("""مبلغ(?:\s+تراکنش)?\s*[:=\-]?\s*[+\-]?\s*([\d,]+)\s*(?:ریال|تومان)?"""),
        Regex("""(?:واریز|برداشت|انتقال|خرید)?\s*[:=\-]?\s*([+\-]\s*[\d,]{3,}|[\d,]{3,}\s*[+\-])"""),
        Regex("""([\d,]{3,})\s*(?:ریال|تومان)\s*(?:به حساب|از حساب|واریز|برداشت)"""),
        Regex("""(?:واریز|برداشت|بستانکار|بدهکار|خرید|پرداخت|کسر از حساب)\s*(?:به|از)?\s*[:=\-]?\s*([\d,]{3,})""")
    )

    private val depositRegex = Regex("""(?:واریز|بستانکار|افزایش|به حساب شما واریز|\+\s*[\d,]+|[\d,]+\s*\+)""")
    private val withdrawalRegex = Regex("""(?:برداشت|بدهکار|خرید|کسر|پرداخت|از حساب شما برداشت|\-\s*[\d,]+|[\d,]+\s*\-)""")

    fun parseSms(id: String, sender: String, rawBody: String, timestamp: Long): BankTransaction? {
        val body = normalizeDigits(rawBody)

        val isFinancial = body.contains("حساب") || body.contains("بانک") ||
                body.contains("کارت") || body.contains("مانده") || body.contains("موجودی")
        if (!isFinancial) return null

        if (body.contains("رمز") || body.contains("پویا") || body.contains("اعتبار این رمز")) {
            return null
        }

        val balanceMatch = balanceRegex.find(body)
        val balanceAmount = balanceMatch?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull()

        val bodyWithoutBalance = if (balanceMatch != null) {
            body.replace(balanceMatch.value, "")
        } else {
            body
        }

        var transactionAmount = 0L
        var matchedSign = ""

        for (pattern in transactionPatterns) {
            val match = pattern.find(bodyWithoutBalance)
            if (match != null) {
                val rawNumStr = match.groupValues[1]
                if (rawNumStr.contains("+")) matchedSign = "+"
                if (rawNumStr.contains("-")) matchedSign = "-"

                val cleanNum = rawNumStr.replace(Regex("""[^\d]"""), "").toLongOrNull() ?: 0L
                if (cleanNum >= 100L) {
                    transactionAmount = cleanNum
                    break
                }
            }
        }

        if (transactionAmount == 0L) return null

        val type = when {
            matchedSign == "+" || depositRegex.containsMatchIn(bodyWithoutBalance) -> TransactionType.DEPOSIT
            matchedSign == "-" || withdrawalRegex.containsMatchIn(bodyWithoutBalance) -> TransactionType.WITHDRAWAL
            else -> TransactionType.UNKNOWN
        }

        if (type == TransactionType.UNKNOWN) return null

        val bankName = detectBankName(sender, body)
        val shamsiDate = PersianDateFormat("Y/m/d - H:i").format(PersianDate(timestamp))

        return BankTransaction(
            id = id,
            sender = sender,
            bankName = bankName,
            amount = transactionAmount,
            balance = balanceAmount,
            type = type,
            persianDate = shamsiDate,
            rawMessage = rawBody
        )
    }

    private fun detectBankName(sender: String, body: String): String {
        val text = "$sender $body".lowercase()
        return when {
            text.contains("blu") || text.contains("بلو") -> "بلوبانک"
            text.contains("mellat") || text.contains("ملت") -> "بانک ملت"
            text.contains("melli") || text.contains("ملی") || text.contains("bmi") -> "بانک ملی"
            text.contains("saman") || text.contains("سامان") -> "بانک سامان"
            text.contains("pasargad") || text.contains("پاسارگاد") || text.contains("bpi") -> "بانک پاسارگاد"
            text.contains("saderat") || text.contains("صادرات") -> "بانک صادرات"
            text.contains("tejarat") || text.contains("تجارت") -> "بانک تجارت"
            text.contains("sepah") || text.contains("سپه") -> "بانک سپه"
            text.contains("keshavarzi") || text.contains("کشاورزی") -> "بانک کشاورزی"
            text.contains("maskan") || text.contains("مسکن") -> "بانک مسکن"
            text.contains("refah") || text.contains("رفاه") -> "بانک رفاه"
            text.contains("shahr") || text.contains("شهر") -> "بانک شهر"
            text.contains("ayandeh") || text.contains("آینده") -> "بانک آینده"
            text.contains("resalat") || text.contains("رسالت") -> "بانک رسالت"
            else -> "سایر بانک‌ها"
        }
    }
}

fun readBankSmsList(
    context: Context,
    savedNotes: Map<String, String> = emptyMap()
): List<BankTransaction> {
    val transactions = mutableListOf<BankTransaction>()
    val cursor = context.contentResolver.query(
        Telephony.Sms.Inbox.CONTENT_URI,
        arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
        null,
        null,
        "${Telephony.Sms.DATE} DESC"
    )

    cursor?.use {
        val idIdx = it.getColumnIndex(Telephony.Sms._ID)
        val addrIdx = it.getColumnIndex(Telephony.Sms.ADDRESS)
        val bodyIdx = it.getColumnIndex(Telephony.Sms.BODY)
        val dateIdx = it.getColumnIndex(Telephony.Sms.DATE)

        while (it.moveToNext()) {
            val id = it.getString(idIdx) ?: continue
            val sender = it.getString(addrIdx) ?: "نامشخص"
            val body = it.getString(bodyIdx) ?: ""
            val dateMillis = it.getLong(dateIdx)

            IranBankSmsParser.parseSms(id, sender, body, dateMillis)?.let { tx ->
                val note = savedNotes[id] ?: ""
                transactions.add(tx.copy(userNote = note))
            }
        }
    }
    return transactions
}
