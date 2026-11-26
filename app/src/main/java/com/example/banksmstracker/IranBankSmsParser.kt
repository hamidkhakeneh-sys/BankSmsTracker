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
    val rawAccountNumber: String = "", // شماره حساب کامل و خام موجود در پیامک (مثلاً 361970003195155001)
    val accountNumber: String,
    val accountKey: String,
    val scope: String,
    val payerIdentifier: String = "",
    val unitId: Int? = null,
    val receiptImagePath: String = "",
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

    private val nonTransactionKeywords = listOf(
        "رمز", "پویا", "اعتبار این رمز", "کد تایید", "کد ورود",
        "صورتحساب", "صورت حساب", "مهلت پرداخت", "قابل پرداخت", "باقی مانده هزینه",
        "شناسه قبض", "شناسه پرداخت", "ش ق:", "ش پ:", "مشترک گرامی", "درخواست انشعاب",
        "توزیع نیروی برق", "برق مشهد", "تابلو کنتور",
        "بدهی پیشین", "بدهی کل", "تخفیف", "جشنواره", "قرعه کشی", "وام فوری",
        "بسته مکالمه", "شاتل موبایل", "شماره گیری", "سرویس اعتباری", "جیب جت",
        "بازپرداخت خود را", "تسهیلات به مبلغ", "تشکیل پرونده", "طرح یاقوت",
        "هزینه پیامک ارسالی", "قطع استفاده از سرویس", "عدد 1 را",
        "elit", "پارکینگ", "پارک حاشیه", "عوارض پارک"
    )

    private val plateNumberRegex = Regex("""\b\d{2}\s*[\-\s]\s*\d{3}\s*[آ-یa-zA-Z]\s*\d{2}\b""")

    private val balanceRegex = Regex(
        """(?:مانده|موجودی|م\.حساب|باقیمانده)\s*[:=\-]?\s*([\d,]{1,18})\s*(?:ریال|تومان)?"""
    )

    // استخراج شماره حساب دارای برچسب (حداقل یک رقم داشته باشد تا با کاراکترهای تزئینی مثل *** اشتباه نشود)
    private val labeledAccountRegex = Regex(
        """(?:حساب|کارت|ش\.حساب|شماره حساب)\s*[:=\-*]?\s*([\d*.\-]*\d[\d*.\-]{2,20})"""
    )

    // لیست جامع تمام بانک‌ها و موسسات اعتباری رسمی ایران برای انتخاب کاربر
    val ALL_IRANIAN_BANKS = listOf(
        "بانک ملی ایران",
        "بانک ملت",
        "بانک تجارت",
        "بانک صادرات ایران",
        "بانک سپه",
        "بانک کشاورزی",
        "بانک مسکن",
        "بانک رفاه کارگران",
        "بانک قرض‌الحسنه مهر ایران",
        "بانک قرض‌الحسنه رسالت",
        "بانک سامان",
        "بانک پاسارگاد",
        "بانک پارسیان",
        "بانک اقتصاد نوین",
        "بانک شهر",
        "بانک آینده",
        "بانک سینا",
        "بانک کارآفرین",
        "بانک دی",
        "بانک سرمایه",
        "بانک گردشگری",
        "بانک ایران زمین",
        "بانک خاورمیانه",
        "بانک توسعه تعاون",
        "بانک صنعت و معدن",
        "بانک توسعه صادرات",
        "پست بانک ایران",
        "بلوبانک (سامان)",
        "ویپاد (پاسارگاد)",
        "موسسه اعتباری ملل"
    )

    // استخراج شماره حساب بدون برچسب (در سطر اول یا هر سطر مستقل پیامک، حتی اگر کاراکتر مخفی RTL/LTR داشته باشد)
    private val standaloneAccountRegex = Regex(
        """(?m)^[\s\u200C\u200E\u200F]*(\d[\d.\-]{8,23})[\s\u200C\u200E\u200F]*$"""
    )

    private val payerCardOrAccRegex = Regex(
        """(?:از کارت|از حساب|مبدا|واریز کننده|توسط)\s*[:=\-*]?\s*([\d*.\-]{4,19})"""
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
        val lowerBody = body.lowercase()
        val lowerSender = normalizePersian(sender).lowercase()

        if (nonTransactionKeywords.any { lowerBody.contains(it) || lowerSender.contains(it) }) return null
        if (plateNumberRegex.containsMatchIn(body)) return null

        val balanceMatch = balanceRegex.find(body) ?: return null
        val balanceAmount = balanceMatch.groupValues[1].replace(",", "").toLongOrNull()

        val firstLine = body.lines().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: ""
        val firstLineDigits = Regex("""^(\d[\d.\-]{8,23})$""").find(firstLine)?.groupValues?.get(1)
        val accMatch = labeledAccountRegex.find(body) ?: standaloneAccountRegex.find(body)
        val rawAcc = (accMatch?.groupValues?.get(1)?.trim() ?: firstLineDigits ?: "")
        val digitsOnlyAcc = rawAcc.replace(Regex("""[^\d]"""), "")
        val cleanAcc = when {
            digitsOnlyAcc.length >= 10 -> {
                val core = if (digitsOnlyAcc.length >= 15) digitsOnlyAcc.drop(7).take(7) else digitsOnlyAcc.takeLast(7)
                core.ifEmpty { digitsOnlyAcc.takeLast(6) }
            }
            digitsOnlyAcc.length in 4..9 -> digitsOnlyAcc
            rawAcc.contains(".") -> rawAcc
            else -> "اصلی"
        }

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
            rawAccountNumber = rawAcc.ifEmpty { if (cleanAcc != "اصلی") cleanAcc else "" },
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
        val bodyLower = normalizePersian(normalizedBody).lowercase().replace("سامانه", "")
        val senderLower = normalizePersian(sender).lowercase()
        val combined = "$senderLower $bodyLower"

        return when {
            bodyLower.contains("بانک قرض الحسنه رسالت") || bodyLower.contains("بانک رسالت") -> "بانک رسالت"
            bodyLower.contains("بانک قرض الحسنه مهر") || bodyLower.contains("مهر ایران") || bodyLower.contains("مهرایران") || bodyLower.contains("qmehr") -> "مهر ایران"
            bodyLower.contains("بانک تجارت") -> "بانک تجارت"
            bodyLower.contains("بانک سامان") -> "بانک سامان"
            bodyLower.contains("بانک ملت") -> "بانک ملت"
            bodyLower.contains("بانک ملی") -> "بانک ملی"
            bodyLower.contains("بانک پاسارگاد") -> "بانک پاسارگاد"
            bodyLower.contains("بانک صادرات") -> "بانک صادرات"
            bodyLower.contains("بانک سپه") -> "بانک سپه"
            bodyLower.contains("بانک کشاورزی") -> "بانک کشاورزی"
            bodyLower.contains("بانک مسکن") -> "بانک مسکن"
            bodyLower.contains("بانک رفاه") -> "بانک رفاه"
            bodyLower.contains("بانک شهر") -> "بانک شهر"
            bodyLower.contains("بانک اینده") -> "بانک آینده"
            bodyLower.contains("بانک پارسیان") -> "بانک پارسیان"
            bodyLower.contains("اقتصاد نوین") -> "اقتصاد نوین"
            bodyLower.contains("بانک سینا") -> "بانک سینا"
            bodyLower.contains("پست بانک") -> "پست بانک"
            bodyLower.contains("بلوبانک") || bodyLower.contains("بلو بانک") -> "بلوبانک"
            combined.contains("resalat") || combined.contains("رسالت") -> "بانک رسالت"
            combined.contains("tejarat") || combined.contains("تجارت") -> "بانک تجارت"
            combined.contains("saman") || combined.contains("سامان") -> "بانک سامان"
            combined.contains("mellat") || combined.contains("ملت") -> "بانک ملت"
            combined.contains("melli") || combined.contains("bmi") || combined.contains("ملی") -> "بانک ملی"
            combined.contains("pasargad") || combined.contains("bpi") || combined.contains("پاسارگاد") -> "بانک پاسارگاد"
            combined.contains("saderat") || combined.contains("صادرات") -> "بانک صادرات"
            combined.contains("sepah") || combined.contains("سپه") -> "بانک سپه"
            combined.contains("keshavarzi") || combined.contains("کشاورزی") -> "بانک کشاورزی"
            combined.contains("maskan") || combined.contains("مسکن") -> "بانک مسکن"
            combined.contains("refah") || combined.contains("رفاه") -> "بانک رفاه"
            combined.contains("shahr") || combined.contains("شهر") -> "بانک شهر"
            combined.contains("ayandeh") || combined.contains("اینده") -> "بانک آینده"
            combined.contains("blu") || combined.contains("بلو") -> "بلوبانک"
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
    accountBankMappings: Map<String, String>,
    payerMappings: Map<String, PayerUnitMappingEntity>,
    importedRecords: List<ImportedRecordEntity>,
    ignoredSmsIds: Set<String>,
    blockedSenders: Set<String>,
    fromYearMonth: String = ""
): Pair<List<BankTransaction>, List<AccountProfileEntity>> {
    val rawTransactions = mutableListOf<BankTransaction>()
    val allInboxSms = mutableListOf<RawInboxSms>()
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
            val sender = it.getString(addrIdx) ?: "نامشخص"
            val body = it.getString(bodyIdx) ?: ""
            val dateMillis = it.getLong(dateIdx)

            if (fromYearMonth.isNotBlank() && fromYearMonth != "همه") {
                val txYm = PersianDateFormat("Y/m").format(PersianDate(dateMillis))
                if (txYm < fromYearMonth) continue
            }

            allInboxSms.add(RawInboxSms(id, sender, body, dateMillis))

            if (ignoredSmsIds.contains(id)) continue
            if (blockedSenders.contains(sender)) continue

            IranBankSmsParser.parseSms(id, sender, body, dateMillis)?.let { parsedTx ->
                // اعمال نگاشت دستی شماره حساب به بانک (در صورتی که کاربر قبلاً این شماره حساب را به بانکی متصل کرده باشد)
                val rawDigitsOnly = parsedTx.rawAccountNumber.replace(Regex("""[^\d]"""), "")
                val rawDigitsOnly = parsedTx.rawAccountNumber.replace(Regex("""[^\d]"""), "")
                val mappedBank = accountBankMappings[parsedTx.rawAccountNumber]
                    ?: accountBankMappings[parsedTx.accountNumber]
                    ?: accountBankMappings.entries.firstOrNull { (k, _) ->
                        val kDigits = k.replace(Regex("""[^\d]"""), "")
                        kDigits.isNotEmpty() && (kDigits == rawDigitsOnly || (kDigits.length >= 6 && rawDigitsOnly.contains(kDigits)))
                    }?.value
                    ?: accountBankMappings.entries.firstOrNull { (k, _) ->
                        val kDigits = k.replace(Regex("""[^\d]"""), "")
                        kDigits.isNotEmpty() && (kDigits == rawDigitsOnly || (kDigits.length >= 6 && rawDigitsOnly.contains(kDigits)))
                    }?.value
                val tx = if (mappedBank != null) {
                    val newKey = "$mappedBank - ${parsedTx.accountNumber}"
                    parsedTx.copy(bankName = mappedBank, accountKey = newKey)
                } else {
                    parsedTx
                }

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
                val autoMapping = if (tx.payerIdentifier.isNotEmpty()) payerMappings[tx.payerIdentifier] else null
                val resolvedUnitId = savedEntity?.unitId ?: autoMapping?.unitId
                val autoUnitNote = autoMapping?.unitTitle ?: ""
                val finalNote = savedEntity?.note?.ifBlank { autoUnitNote } ?: autoUnitNote
                val finalCategory = savedEntity?.category?.ifBlank {
                    if (resolvedUnitId != null && profile.scope == "مدیریت ساختمان") "شارژ ماهیانه واحدها" else ""
                } ?: if (resolvedUnitId != null && profile.scope == "مدیریت ساختمان") "شارژ ماهیانه واحدها" else ""

                rawTransactions.add(
                    tx.copy(
                        scope = profile.scope,
                        unitId = resolvedUnitId,
                        receiptImagePath = savedEntity?.receiptImagePath ?: "",
                        userNote = finalNote,
                        category = finalCategory
                    )
                )
            }
        }
    }

    // ۱. نگاشت هوشمند شماره حساب‌های بدون عنوان بانک به نام بانک شناخته‌شده در سایر پیامک‌ها
    val knownBankByAccountNum = rawTransactions
        .filter { it.bankName != "سایر بانک‌ها" && it.accountNumber != "اصلی" }
        .associate { it.accountNumber to it.bankName }

    for (i in rawTransactions.indices) {
        val tx = rawTransactions[i]
        if (tx.bankName == "سایر بانک‌ها" && tx.accountNumber != "اصلی") {
            val resolvedBank = knownBankByAccountNum[tx.accountNumber]
            if (resolvedBank != null) {
                val fixedAccountKey = "$resolvedBank - ${tx.accountNumber}"
                rawTransactions[i] = tx.copy(
                    bankName = resolvedBank,
                    accountKey = fixedAccountKey
                )
            }
        }
    }

    // ۲. نام‌گذاری خودکار چند حساب در یک بانک به صورت «اصلی»، «فرعی ۱»، «فرعی ۲» بر اساس بیشترین تعداد تراکنش
    val accountsGroupedByBank = rawTransactions.groupBy { it.bankName }
    val renamedAccountKeyMap = mutableMapOf<String, String>()

    for ((bName, txListOfBank) in accountsGroupedByBank) {
        val distinctAccsSortedByActivity = txListOfBank
            .groupBy { it.accountNumber }
            .toList()
            .sortedByDescending { it.second.size } // پرگردش‌ترین حساب = اصلی

        if (distinctAccsSortedByActivity.size == 1) {
            val (accNum, _) = distinctAccsSortedByActivity[0]
            val oldKey = "$bName - $accNum"
            val newKey = if (accNum == "اصلی") "$bName - اصلی" else "$bName - اصلی ($accNum)"
            renamedAccountKeyMap[oldKey] = newKey
        } else {
            distinctAccsSortedByActivity.forEachIndexed { idx, (accNum, _) ->
                val oldKey = "$bName - $accNum"
                val roleLabel = if (idx == 0) "اصلی" else "فرعی $idx"
                val newKey = if (accNum == "اصلی") "$bName - $roleLabel" else "$bName - $roleLabel ($accNum)"
                renamedAccountKeyMap[oldKey] = newKey
            }
        }
    }

    discoveredAccounts.clear()
    for (i in rawTransactions.indices) {
        val tx = rawTransactions[i]
        val finalKey = renamedAccountKeyMap[tx.accountKey] ?: tx.accountKey
        // حفظ تنظیمات قبلی کاربر (مثل اختصاص به مدیریت ساختمان) حتی اگر پسوند حساب به «اصلی / فرعی ۱» تغییر کرده باشد
        val existingProf = accountProfiles[finalKey]
            ?: accountProfiles.values.find { it.bankName == tx.bankName && it.accountNumber == tx.accountNumber }
            ?: AccountProfileEntity(
                accountKey = finalKey,
                bankName = tx.bankName,
                accountNumber = tx.accountNumber,
                customTitle = finalKey,
                scope = tx.scope
            )
        val syncedProfile = existingProf.copy(accountKey = finalKey, customTitle = finalKey)
        discoveredAccounts[finalKey] = syncedProfile

        rawTransactions[i] = tx.copy(
            accountKey = finalKey,
            scope = syncedProfile.scope
        )
    }

    for (imp in importedRecords) {
        if (ignoredSmsIds.contains(imp.recordId)) continue
        val impBankName = imp.accountKey.substringBefore(" - ").trim()
        val impDateOnly = imp.persianDate.substringBefore(" - ").trim()

        val matchingSmsIndex = rawTransactions.indexOfFirst { smsTx ->
            val sameBank = smsTx.accountKey == imp.accountKey || smsTx.bankName == impBankName
            val sameType = (smsTx.type == TransactionType.DEPOSIT) == imp.isDeposit
            val sameAmount = smsTx.amount == imp.amount
            val sameDate = smsTx.persianDateOnly == impDateOnly
            sameBank && sameType && sameAmount && sameDate
        }

        if (matchingSmsIndex != -1) {
            val existingSms = rawTransactions[matchingSmsIndex]
            val mergedPayer = imp.payerIdentifier.ifBlank { existingSms.payerIdentifier }
            val autoMapping = if (mergedPayer.isNotEmpty()) payerMappings[mergedPayer] else null
            val resolvedUnitId = existingSms.unitId ?: autoMapping?.unitId
            val autoUnit = autoMapping?.unitTitle ?: ""
            val mergedNote = when {
                existingSms.userNote.isNotBlank() && existingSms.userNote != "انتقال وجه بانکی" && existingSms.userNote != "خرید با کارت (کارتخوان)" -> existingSms.userNote
                autoUnit.isNotBlank() -> autoUnit
                imp.description.isNotBlank() -> imp.description
                else -> existingSms.userNote
            }
            val mergedCat = existingSms.category.ifBlank {
                if (resolvedUnitId != null && existingSms.scope == "مدیریت ساختمان") "شارژ ماهیانه واحدها" else ""
            }
            rawTransactions[matchingSmsIndex] = existingSms.copy(
                payerIdentifier = mergedPayer,
                unitId = resolvedUnitId,
                userNote = mergedNote,
                category = mergedCat,
                isFromExcel = true
            )
            continue
        }

        val targetAccKey = discoveredAccounts.keys.firstOrNull { it.startsWith("$impBankName - ") && !it.endsWith("اصلی") }
            ?: imp.accountKey

        val profile = discoveredAccounts.getOrPut(targetAccKey) {
            AccountProfileEntity(
                accountKey = targetAccKey,
                bankName = impBankName,
                accountNumber = targetAccKey.substringAfter(" - ", "اکسل"),
                customTitle = targetAccKey,
                scope = "مدیریت ساختمان"
            )
        }
        val savedEntity = savedNotes[imp.recordId]
        val autoMapping = if (imp.payerIdentifier.isNotEmpty()) payerMappings[imp.payerIdentifier] else null
        val resolvedUnitId = savedEntity?.unitId ?: autoMapping?.unitId
        val autoUnitNote = autoMapping?.unitTitle ?: ""
        val finalNote = savedEntity?.note?.ifBlank { autoUnitNote.ifBlank { imp.description } } ?: autoUnitNote.ifBlank { imp.description }
        val yearOnly = impDateOnly.substringBefore("/")

        rawTransactions.add(
            BankTransaction(
                id = imp.recordId,
                sender = "فایل بانکی (اکسل/PDF)",
                bankName = profile.bankName,
                accountNumber = profile.accountNumber,
                accountKey = targetAccKey,
                scope = profile.scope,
                payerIdentifier = imp.payerIdentifier,
                unitId = resolvedUnitId,
                receiptImagePath = savedEntity?.receiptImagePath ?: "",
                amount = imp.amount,
                balance = imp.balance,
                type = if (imp.isDeposit) TransactionType.DEPOSIT else TransactionType.WITHDRAWAL,
                isFromExcel = true,
                timestampMillis = imp.timestampMillis,
                persianDate = imp.persianDate,
                persianDateOnly = impDateOnly,
                persianMonthYear = imp.persianMonthYear,
                persianYear = yearOnly,
                rawMessage = "رکورد صورت‌حساب: ${imp.description}",
                userNote = finalNote,
                category = savedEntity?.category ?: if (resolvedUnitId != null) "شارژ ماهیانه واحدها" else ""
            )
        )
    }

    val enrichedTransactions = rawTransactions.map { tx ->
        if (tx.userNote.isBlank()) {
            val inferred = AutoTransactionDescriber.inferTransactionDetails(tx, allInboxSms)
            if (inferred != null) {
                tx.copy(
                    userNote = inferred.autoNote,
                    category = tx.category.ifBlank { inferred.suggestedCategory }
                )
            } else {
                tx
            }
        } else {
            tx
        }
    }

    val sortedAll = enrichedTransactions.sortedByDescending { it.timestampMillis }
    val processedTransactions = IranBankSmsParser.detectInternalTransfers(sortedAll, savedNotes)
    return Pair(processedTransactions, discoveredAccounts.values.toList())
}
