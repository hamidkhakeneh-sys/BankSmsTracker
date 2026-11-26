package com.example.banksmstracker

import android.content.Context
import android.provider.Telephony
import saman.zamani.persiandate.PersianDate
import saman.zamani.persiandate.PersianDateFormat
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

enum class TransactionType {
    DEPOSIT,
    WITHDRAWAL,
    INTERNAL,
    UNKNOWN
}

data class BankTransaction(
    val id: String,
    val sender: String,
    val bankName: String,
    val accountNumber: String,
    val accountKey: String,
    val scope: String,
    val payerIdentifier: String = "",
    val amount: Long,
    val balance: Long?,
    val type: TransactionType,
    val isAutoDetectedTransfer: Boolean = false,
    val transferPairInfo: String = "",
    val isFromExcel: Boolean = false,
    val timestampMillis: Long,
    val persianDate: String,
    val persianDateOnly: String,
    val persianMonthYear: String,
    val persianYear: String,
    val rawMessage: String,
    val userNote: String = "",
    val category: String = ""
)

data class AccountDiscrepancy(
    val accountKey: String,
    val bankName: String,
    val date: String,
    val previousBalance: Long,
    val currentBalance: Long,
    val transactionAmount: Long,
    val expectedBalance: Long,
    val difference: Long,
    val description: String
)

fun formatCurrency(amount: Long): String {
    return NumberFormat.getNumberInstance(Locale.US).format(amount)
}

object IranBankSmsParser {

    fun normalizePersian(input: String): String {
        return input
            .replace('ك', 'ک')
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ة', 'ه')
            .replace('ؤ', 'و')
            .replace('إ', 'ا')
            .replace('أ', 'ا')
            .replace('آ', 'ا')
            .replace('۰', '0').replace('۱', '1').replace('۲', '2')
            .replace('۳', '3').replace('۴', '4').replace('۵', '5')
            .replace('۶', '6').replace('۷', '7').replace('۸', '8')
            .replace('۹', '9')
            .replace('٠', '0').replace('١', '1').replace('٢', '2')
            .replace('٣', '3').replace('٤', '4').replace('٥', '5')
            .replace('٦', '6').replace('٧', '7').replace('٨', '8')
            .replace('٩', '9')
    }

    // الگوهای دقیق برای حذف پیامک‌های اطلاع‌رسانی، تبلیغات اپراتور، وام و قبوض شرکت برق/مخابرات
    private val nonTransactionKeywords = listOf(
        "رمز", "پویا", "اعتبار این رمز", "کد تایید", "کد ورود",
        "صورتحساب", "صورت حساب", "مهلت پرداخت", "قابل پرداخت", "باقی مانده هزینه",
        "شناسه قبض", "شناسه پرداخت", "ش ق:", "ش پ:", "مشترک گرامی", "درخواست انشعاب",
        "توزیع نیروی برق", "برق مشهد", "تابلو کنتور",
        "بدهی پیشین", "بدهی کل", "تخفیف", "جشنواره", "قرعه کشی", "وام فوری",
        "بسته مکالمه", "شاتل موبایل", "شماره گیری", "سرویس اعتباری", "جیب جت",
        "بازپرداخت خود را", "تسهیلات به مبلغ", "تشکیل پرونده", "طرح یاقوت",
        "هزینه پیامک ارسالی", "قطع استفاده از سرویس", "عدد 1 را"
    )

    // شرط طلایی کاربر: پیامک بانکی واقعی تقریباً همیشه شامل "مانده" یا "موجودی" حساب است
    private val balanceRegex = Regex(
        """(?:مانده|موجودی|م\.حساب|باقیمانده)\s*[:=\-]?\s*([\d,]{1,18})\s*(?:ریال|تومان)?"""
    )

    private val accountRegex = Regex(
        """(?:حساب|کارت|ش\.حساب|شماره حساب)\s*[:=\-*]?\s*([\d*\-]{3,16})"""
    )

    private val payerCardOrAccRegex = Regex(
        """(?:از کارت|از حساب|مبدا|واریز کننده|توسط)\s*[:=\-*]?\s*([\d*\-]{4,19})"""
    )

    private val transactionPatterns = listOf(
        Regex("""مبلغ(?:\s+تراکنش)?\s*[:=\-]?\s*[+\-]?\s*([\d,]{3,15})\s*(?:ریال|تومان)?"""),
        Regex("""(?:واریز|برداشت|انتقال|خرید)?\s*[:=\-]?\s*([+\-]\s*[\d,]{3,15}|[\d,]{3,15}\s*[+\-])"""),
        Regex("""([\d,]{3,15})\s*(?:ریال|تومان)\s*(?:به حساب|از حساب|واریز|برداشت)"""),
        Regex("""(?:واریز|برداشت|بستانکار|بدهکار|خرید|پرداخت|کسر از حساب)\s*(?:به|از)?\s*[:=\-]?\s*([\d,]{3,15})""")
    )

    private val depositRegex = Regex("""(?:واریز|بستانکار|افزایش|به حساب شما واریز|\+\s*[\d,]+|[\d,]+\s*\+)""")
    private val withdrawalRegex = Regex("""(?:برداشت|بدهکار|خرید|کسر|پرداخت|از حساب شما برداشت|\-\s*[\d,]+|[\d,]+\s*\-)""")

    fun parseSms(id: String, sender: String, rawBody: String, timestamp: Long): BankTransaction? {
        val body = normalizePersian(rawBody)

        // ۱. حذف پیامک‌های اطلاع‌رسانی، قبوض، اپراتورها و تبلیغات وام
        if (nonTransactionKeywords.any { body.contains(it) }) return null

        // ۲. شرط الزامی وجود "مانده" یا "موجودی" در پیامک (برای جلوگیری از ورود پیامک‌های غیرتراکنشی)
        val balanceMatch = balanceRegex.find(body) ?: return null
        val balanceAmount = balanceMatch.groupValues[1].replace(",", "").toLongOrNull()

        val accMatch = accountRegex.find(body)
        val rawAcc = accMatch?.groupValues?.get(1)?.trim() ?: "اصلی"
        val cleanAcc = if (rawAcc.length > 6) rawAcc.takeLast(5) else rawAcc

        val payerMatch = payerCardOrAccRegex.find(body)
        val rawPayer = payerMatch?.groupValues?.get(1)?.trim() ?: ""
        val cleanPayer = if (rawPayer.isNotEmpty() && rawPayer != rawAcc) rawPayer else ""

        var cleanBodyForAmount = body.replace(balanceMatch.value, "")
        if (accMatch != null) cleanBodyForAmount = cleanBodyForAmount.replace(accMatch.value, "")
        if (payerMatch != null) cleanBodyForAmount = cleanBodyForAmount.replace(payerMatch.value, "")

        var transactionAmount = 0L
        var matchedSign = ""

        for (pattern in transactionPatterns) {
            val match = pattern.find(cleanBodyForAmount)
            if (match != null) {
                val rawNumStr = match.groupValues[1]
                if (rawNumStr.contains("+")) matchedSign = "+"
                if (rawNumStr.contains("-")) matchedSign = "-"

                val cleanNum = rawNumStr.replace(Regex("""[^\d]"""), "").toLongOrNull() ?: 0L
                if (cleanNum in 100L..500_000_000_000L) {
                    transactionAmount = cleanNum
                    break
                }
            }
        }

        if (transactionAmount == 0L) return null

        val type = when {
            matchedSign == "+" || depositRegex.containsMatchIn(cleanBodyForAmount) -> TransactionType.DEPOSIT
            matchedSign == "-" || withdrawalRegex.containsMatchIn(cleanBodyForAmount) -> TransactionType.WITHDRAWAL
            else -> TransactionType.UNKNOWN
        }

        if (type == TransactionType.UNKNOWN) return null

        // ۳. تشخیص دقیق نام بانک (اصلاح باگ تشخیص اشتباه بانک تجارت به جای سامان به خاطر کلمه "سامانه پل")
        val bankName = detectBankName(sender, body)
        val accountKey = "$bankName - $cleanAcc"
        val pDate = PersianDate(timestamp)
        val shamsiDate = PersianDateFormat("Y/m/d - H:i").format(pDate)
        val shamsiDateOnly = PersianDateFormat("Y/m/d").format(pDate)
        val shamsiMonthYear = PersianDateFormat("Y/m").format(pDate)
        val shamsiYear = PersianDateFormat("Y").format(pDate)

        return BankTransaction(
            id = id,
            sender = sender,
            bankName = bankName,
            accountNumber = cleanAcc,
            accountKey = accountKey,
            scope = "شخصی",
            payerIdentifier = cleanPayer,
            amount = transactionAmount,
            balance = balanceAmount,
            type = type,
            timestampMillis = timestamp,
            persianDate = shamsiDate,
            persianDateOnly = shamsiDateOnly,
            persianMonthYear = shamsiMonthYear,
            persianYear = shamsiYear,
            rawMessage = rawBody
        )
    }

    private fun detectBankName(sender: String, normalizedBody: String): String {
        val text = normalizePersian("$sender $normalizedBody").lowercase()
        // حذف کلمه "سامانه" (مثل سامانه پل، سامانه شتاب، سامانه ساتنا) تا با "بانک سامان" اشتباه نشود!
        val cleanedText = text.replace("سامانه", "")

        // ابتدا بررسی سرشماره‌های معروف و نام صریح "بانک ..."
        return when {
            cleanedText.contains("بانک تجارت") || cleanedText.contains("tejarat") -> "بانک تجارت"
            cleanedText.contains("بانک سامان") || cleanedText.contains("saman") -> "بانک سامان"
            cleanedText.contains("بانک ملت") || cleanedText.contains("mellat") -> "بانک ملت"
            cleanedText.contains("بانک ملی") || cleanedText.contains("melli") || cleanedText.contains("bmi") -> "بانک ملی"
            cleanedText.contains("بانک پاسارگاد") || cleanedText.contains("pasargad") || cleanedText.contains("bpi") -> "بانک پاسارگاد"
            cleanedText.contains("بانک صادرات") || cleanedText.contains("saderat") -> "بانک صادرات"
            cleanedText.contains("بانک سپه") || cleanedText.contains("sepah") -> "بانک سپه"
            cleanedText.contains("بانک کشاورزی") || cleanedText.contains("keshavarzi") -> "بانک کشاورزی"
            cleanedText.contains("بانک مسکن") || cleanedText.contains("maskan") -> "بانک مسکن"
            cleanedText.contains("بانک رفاه") || cleanedText.contains("refah") -> "بانک رفاه"
            cleanedText.contains("بانک شهر") || cleanedText.contains("shahr") -> "بانک شهر"
            cleanedText.contains("بانک اینده") || cleanedText.contains("ayandeh") -> "بانک آینده"
            cleanedText.contains("بانک رسالت") || cleanedText.contains("resalat") -> "بانک رسالت"
            cleanedText.contains("بانک پارسیان") || cleanedText.contains("parsian") -> "بانک پارسیان"
            cleanedText.contains("اقتصاد نوین") || cleanedText.contains("eghtesad") -> "اقتصاد نوین"
            cleanedText.contains("بانک سینا") || cleanedText.contains("sina") -> "بانک سینا"
            cleanedText.contains("پست بانک") || cleanedText.contains("post") -> "پست بانک"
            cleanedText.contains("مهر ایران") || cleanedText.contains("mehr") -> "مهر ایران"
            cleanedText.contains("blu") || cleanedText.contains("بلو") -> "بلوبانک"
            // بررسی سرشماره پیامک بدون کلمه "بانک"
            cleanedText.contains("تجارت") -> "بانک تجارت"
            cleanedText.contains("سامان") -> "بانک سامان"
            cleanedText.contains("ملت") -> "بانک ملت"
            cleanedText.contains("ملی") -> "بانک ملی"
            cleanedText.contains("پاسارگاد") -> "بانک پاسارگاد"
            cleanedText.contains("صادرات") -> "بانک صادرات"
            cleanedText.contains("سپه") -> "بانک سپه"
            cleanedText.contains("کشاورزی") -> "بانک کشاورزی"
            cleanedText.contains("مسکن") -> "بانک مسکن"
            cleanedText.contains("رفاه") -> "بانک رفاه"
            cleanedText.contains("شهر") -> "بانک شهر"
            cleanedText.contains("اینده") -> "بانک آینده"
            cleanedText.contains("رسالت") -> "بانک رسالت"
            else -> "سایر بانک‌ها"
        }
    }

    fun detectInternalTransfers(
        transactions: List<BankTransaction>,
        savedNotes: Map<String, TransactionNoteEntity>
    ): List<BankTransaction> {
        val result = transactions.toMutableList()
        val maxWindowMillis = 24 * 60 * 60 * 1000L
        val maxFeeTolerance = 60_000L
        val matchedDepositIds = mutableSetOf<String>()

        for (i in result.indices) {
            val tx = result[i]
            val override = savedNotes[tx.id]?.isInternalTransferOverride
            if (override != null) {
                if (override) {
                    result[i] = tx.copy(type = TransactionType.INTERNAL, transferPairInfo = "ثبت دستی به عنوان انتقال داخلی")
                }
                continue
            }

            if (tx.type == TransactionType.WITHDRAWAL) {
                val matchingDepositIndex = result.indexOfFirst { other ->
                    other.id != tx.id &&
                            !matchedDepositIds.contains(other.id) &&
                            other.type == TransactionType.DEPOSIT &&
                            other.accountKey != tx.accountKey &&
                            abs(other.timestampMillis - tx.timestampMillis) <= maxWindowMillis &&
                            (tx.amount - other.amount) in 0L..maxFeeTolerance &&
                            savedNotes[other.id]?.isInternalTransferOverride != false
                }

                if (matchingDepositIndex != -1) {
                    val depositTx = result[matchingDepositIndex]
                    matchedDepositIds.add(depositTx.id)

                    result[i] = tx.copy(
                        type = TransactionType.INTERNAL,
                        isAutoDetectedTransfer = true,
                        transferPairInfo = "انتقال به ${depositTx.accountKey}"
                    )
                    result[matchingDepositIndex] = depositTx.copy(
                        type = TransactionType.INTERNAL,
                        isAutoDetectedTransfer = true,
                        transferPairInfo = "انتقال از ${tx.accountKey}"
                    )
                }
            }
        }
        return result
    }

    fun findDiscrepancies(transactions: List<BankTransaction>): List<AccountDiscrepancy> {
        val discrepancies = mutableListOf<AccountDiscrepancy>()
        val groupedByAccount = transactions
            .filter { it.balance != null }
            .groupBy { it.accountKey }

        for ((accountKey, txList) in groupedByAccount) {
            val sortedAsc = txList.sortedBy { it.timestampMillis }
            for (i in 1 until sortedAsc.size) {
                val prev = sortedAsc[i - 1]
                val curr = sortedAsc[i]
                val prevBal = prev.balance ?: continue
                val currBal = curr.balance ?: continue

                val isActualDeposit = when (curr.type) {
                    TransactionType.DEPOSIT -> true
                    TransactionType.WITHDRAWAL -> false
                    TransactionType.INTERNAL -> currBal > prevBal
                    TransactionType.UNKNOWN -> continue
                }

                val expectedBal = if (isActualDeposit) prevBal + curr.amount else prevBal - curr.amount
                val diff = currBal - expectedBal

                if (abs(diff) > 100L) {
                    val reason = if (diff < 0) {
                        "احتمالاً یک برداشت به مبلغ ${formatCurrency(abs(diff))} ریال ثبت نشده است."
                    } else {
                        "احتمالاً یک واریز به مبلغ ${formatCurrency(abs(diff))} ریال ثبت نشده است."
                    }
                    discrepancies.add(
                        AccountDiscrepancy(
                            accountKey = accountKey,
                            bankName = curr.bankName,
                            date = curr.persianDate,
                            previousBalance = prevBal,
                            currentBalance = currBal,
                            transactionAmount = curr.amount,
                            expectedBalance = expectedBal,
                            difference = diff,
                            description = reason
                        )
                    )
                }
            }
        }
        return discrepancies.reversed()
    }
}

fun readAllTransactionsFast(
    context: Context,
    savedNotes: Map<String, TransactionNoteEntity>,
    accountProfiles: Map<String, AccountProfileEntity>,
    payerMappings: Map<String, String>,
    importedRecords: List<ImportedRecordEntity>,
    ignoredSmsIds: Set<String>,
    blockedSenders: Set<String>
): Pair<List<BankTransaction>, List<AccountProfileEntity>> {
    val rawTransactions = mutableListOf<BankTransaction>()
    val discoveredAccounts = accountProfiles.toMutableMap()

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
            if (ignoredSmsIds.contains(id)) continue

            val sender = it.getString(addrIdx) ?: "نامشخص"
            if (blockedSenders.contains(sender)) continue

            val body = it.getString(bodyIdx) ?: ""
            val dateMillis = it.getLong(dateIdx)

            IranBankSmsParser.parseSms(id, sender, body, dateMillis)?.let { tx ->
                val profile = discoveredAccounts.getOrPut(tx.accountKey) {
                    AccountProfileEntity(
                        accountKey = tx.accountKey,
                        bankName = tx.bankName,
                        accountNumber = tx.accountNumber,
                        customTitle = tx.accountKey,
                        scope = "شخصی"
                    )
                }

                val savedEntity = savedNotes[id]
                val autoUnitNote = if (tx.payerIdentifier.isNotEmpty()) payerMappings[tx.payerIdentifier] ?: "" else ""
                val finalNote = savedEntity?.note?.ifBlank { autoUnitNote } ?: autoUnitNote
                val finalCategory = savedEntity?.category?.ifBlank {
                    if (autoUnitNote.isNotEmpty() && profile.scope == "مدیریت ساختمان") "شارژ ماهیانه واحدها" else ""
                } ?: if (autoUnitNote.isNotEmpty() && profile.scope == "مدیریت ساختمان") "شارژ ماهیانه واحدها" else ""

                rawTransactions.add(
                    tx.copy(
                        scope = profile.scope,
                        userNote = finalNote,
                        category = finalCategory
                    )
                )
            }
        }
    }

    for (imp in importedRecords) {
        if (ignoredSmsIds.contains(imp.recordId)) continue
        val profile = discoveredAccounts.getOrPut(imp.accountKey) {
            AccountProfileEntity(
                accountKey = imp.accountKey,
                bankName = imp.accountKey.substringBefore(" - "),
                accountNumber = imp.accountKey.substringAfter(" - ", "اکسل"),
                customTitle = imp.accountKey,
                scope = "مدیریت ساختمان"
            )
        }
        val savedEntity = savedNotes[imp.recordId]
        val autoUnitNote = if (imp.payerIdentifier.isNotEmpty()) payerMappings[imp.payerIdentifier] ?: "" else ""
        val finalNote = savedEntity?.note?.ifBlank { autoUnitNote.ifBlank { imp.description } } ?: autoUnitNote.ifBlank { imp.description }

        val dateOnly = imp.persianDate.substringBefore(" - ").trim()
        val yearOnly = dateOnly.substringBefore("/")

        rawTransactions.add(
            BankTransaction(
                id = imp.recordId,
                sender = "فایل بانکی (اکسل/PDF)",
                bankName = profile.bankName,
                accountNumber = profile.accountNumber,
                accountKey = imp.accountKey,
                scope = profile.scope,
                payerIdentifier = imp.payerIdentifier,
                amount = imp.amount,
                balance = imp.balance,
                type = if (imp.isDeposit) TransactionType.DEPOSIT else TransactionType.WITHDRAWAL,
                isFromExcel = true,
                timestampMillis = imp.timestampMillis,
                persianDate = imp.persianDate,
                persianDateOnly = dateOnly,
                persianMonthYear = imp.persianMonthYear,
                persianYear = yearOnly,
                rawMessage = "رکورد صورت‌حساب: ${imp.description}",
                userNote = finalNote,
                category = savedEntity?.category ?: if (autoUnitNote.isNotEmpty()) "شارژ ماهیانه واحدها" else ""
            )
        )
    }

    val sortedAll = rawTransactions.sortedByDescending { it.timestampMillis }
    val processedTransactions = IranBankSmsParser.detectInternalTransfers(sortedAll, savedNotes)
    return Pair(processedTransactions, discoveredAccounts.values.toList())
}
