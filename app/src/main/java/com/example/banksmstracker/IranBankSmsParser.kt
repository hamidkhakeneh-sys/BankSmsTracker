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
    val stableKey: String = "",
    val sender: String,
    val bankName: String,
    val rawAccountNumber: String = "",
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

    fun normalizePersian(input: String): String {
        val strippedBidi = input.replace(Regex("[\u200B-\u200F\u202A-\u202E\u2066-\u2069\uFEFF]"), "")
        return strippedBidi
            .replace('ك', 'ک')
            .replace('ي', 'ی')
            .replace('ى', 'ی')
            .replace('ة', 'ه')
            .replace('ؤ', 'و')
            .replace('إ', 'ا')
            .replace('أ', 'ا')
            .replace('آ', 'ا')
            .replace('٬', ',')
            .replace('،', ',')
            .replace('۰', '0').replace('۱', '1').replace('۲', '2')
            .replace('۳', '3').replace('۴', '4').replace('۵', '5')
            .replace('۶', '6').replace('۷', '7').replace('۸', '8')
            .replace('۹', '9')
            .replace('٠', '0').replace('١', '1').replace('٢', '2')
            .replace('٣', '3').replace('٤', '4').replace('٥', '5')
            .replace('٦', '6').replace('٧', '7').replace('٨', '8')
            .replace('٩', '9')
    }

    /**
     * استخراج هسته یکتای شماره حساب برای یکسان‌سازی فرمت‌های مختلف یک حساب بانکی
     * مثلاً 3608-700-3195155-1 و 361970003195155001 هر دو به 3195155 نگاشت می‌شوند
     */
    fun extractAccountCore(rawAccount: String): String {
        val digitsOnly = rawAccount.replace(Regex("""[^\d]"""), "")
        return when {
            digitsOnly.length >= 15 -> digitsOnly.drop(7).take(7).ifEmpty { digitsOnly.takeLast(7) }
            digitsOnly.length in 10..14 -> digitsOnly.takeLast(7)
            digitsOnly.length in 4..9 -> digitsOnly
            rawAccount.contains(".") -> rawAccount.trim()
            else -> "اصلی"
        }
    }

    fun buildStableTransactionKey(body: String, amount: Long, balance: Long?, timestamp: Long): String {
        val pDate = PersianDate(timestamp)
        val dayStr = PersianDateFormat("Y/m/d").format(pDate)
        val compactBody = normalizePersian(body).replace(Regex("""\s+"""), "").take(80)
        return "tx_${dayStr}_${amount}_${balance ?: 0L}_${compactBody.hashCode()}"
    }

    private val nonTransactionKeywords = listOf(
        "رمز پویا", "اعتبار این رمز", "کد تایید", "کد ورود", "کد یکبار مصرف", "otp",
        "صورتحساب", "صورت حساب", "مهلت پرداخت", "قابل پرداخت", "باقی مانده هزینه",
        "شناسه قبض", "شناسه پرداخت", "ش ق:", "ش پ:", "مشترک گرامی", "درخواست انشعاب",
        "توزیع نیروی برق", "برق مشهد", "تابلو کنتور", "قبض برق", "قبض گاز", "قبض آب",
        "بدهی پیشین", "بدهی کل", "جشنواره", "قرعه کشی", "وام فوری", "اعتبار هدیه", "تخفیف",
        "بسته مکالمه", "بسته اینترنت", "بسته های اینترنت", "بسته‌های اینترنت", "گیگ",
        "شاتل موبایل", "shatelmobile", "شماره گیری", "سرویس اعتباری", "جیب جت",
        "بازپرداخت خود را", "تسهیلات به مبلغ", "تشکیل پرونده", "طرح یاقوت",
        "هزینه پیامک ارسالی", "قطع استفاده از سرویس", "عدد 1 را", "لغو 11", "لغو عضویت",
        "elit", "پارک حاشیه", "عوارض پارک", "سفارش شما", "مرسوله", "ارسال شد",
        "کد رهگیری مرسوله", "پیک", "تخفیف ویژه", "حراج", "بن خرید", "پیشنهاد فقط برای توست"
    )

    private val bankingContextRegex = Regex(
        """(?:مانده|موجودی|م\.حساب|باقیمانده|حساب|کارت|شبا|شتاب|پایا|ساتنا|انتقال|واریز|برداشت|بستانکار|بدهکار|بانک|موسسه|سپرده|تراکنش|پوز|pos|خودپرداز|atm|رمز دوم|پین)"""
    )

    private val plateNumberRegex = Regex("""(?:پلاک\s*[:=]?\s*)?\b\d{2}\s*[\-\s]?\d{3}\s*[آ-یa-zA-Z]\s*\d{2}(?:\s*(?:ایران|ir)\s*\d{1,2})?\b""", RegexOption.IGNORE_CASE)
    private val urlOrLinkRegex = Regex("""(?:https?://|www\.|[a-z0-9\-]+\.(?:ir|com|net|org|me|shop|app)(?:/|\s|$))""", RegexOption.IGNORE_CASE)
    private val nonBankServiceRegex = Regex("""(?:سفارش(?:ت|ات)?|نهایی کنی|اتمام موجودی|توقف|پارکومتر|پارکینگ|عوارض|قبض|کد تخفیف|جشنواره|اقساط|مرسوله|بسته اینترنت|بسته مکالمه|شارژ سیم‌کارت|لغو عضویت)""")

    private val balanceRegex = Regex(
        """(?:مانده|موجودی|م\.حساب|باقیمانده)\s*[:=\-]?\s*([+\-]?[\d,]{1,18})\s*(?:ریال|تومان)?"""
    )

    private val labeledAccountRegex = Regex(
        """(?:حساب|کارت|ش\.حساب|شماره حساب)\s*[:=\-*]?\s*([\d*.\-]*\d[\d*.\-]{2,20})"""
    )

    private val standaloneAccountRegex = Regex(
        """(?m)^[\s\u200C\u200E\u200F]*(\d[\d.\-]{8,23})[\s\u200C\u200E\u200F]*$"""
    )

    private val payerCardOrAccRegex = Regex(
        """(?:از کارت|از حساب|مبدا|واریز کننده|توسط)\s*[:=\-*]?\s*([\d*.\-]{4,19})"""
    )

    private val transactionPatterns = listOf(
        Regex("""مبلغ(?:\s+تراکنش)?\s*[:=\-]?\s*([+\-]?[\d,]{3,18})\s*(ریال|تومان)?"""),
        Regex("""(?m)^\s*([+\-]?\s*[\d,]{3,18}\s*[+\-]?)\s*$"""),
        Regex("""(?:واریز|برداشت|انتقال|خرید)?\s*[:=\-]?\s*([+\-]\s*[\d,]{3,18}|[\d,]{3,18}\s*[+\-])"""),
        Regex("""([\d,]{3,18})\s*(ریال|تومان)\s*(?:به حساب|از حساب|واریز|برداشت)?"""),
        Regex("""(?:واریز|برداشت|بستانکار|بدهکار|خرید|پرداخت|کسر از حساب)\s*(?:به|از)?\s*[:=\-]?\s*([\d,]{3,18})""")
    )

    private val withdrawalCuesRegex = Regex("""(?:برداشت|بدهکار|خرید|کسر|پرداخت|از حساب شما برداشت|انتقال به|واریز به|حواله به|\-\s*[\d,]+|[\d,]+\s*\-)""")
    private val depositCuesRegex = Regex("""(?:واریز به حساب|واریز از|واریز توسط|بستانکار|افزایش موجودی|به حساب شما واریز|\+\s*[\d,]+|[\d,]+\s*\+)""")

    fun parseSms(id: String, sender: String, rawBody: String, timestamp: Long): BankTransaction? {
        val body = normalizePersian(rawBody)
        val lowerBody = body.lowercase()
        val lowerSender = normalizePersian(sender).lowercase()

        if (nonTransactionKeywords.any { lowerBody.contains(it) || lowerSender.contains(it) }) return null
        if (plateNumberRegex.containsMatchIn(body)) return null
        if (urlOrLinkRegex.containsMatchIn(body)) return null
        if (nonBankServiceRegex.containsMatchIn(body)) return null
        if (!bankingContextRegex.containsMatchIn(lowerBody) && !bankingContextRegex.containsMatchIn(lowerSender)) return null

        val balanceMatch = balanceRegex.find(body)
        val rawBalanceStr = balanceMatch?.groupValues?.get(1)?.replace(",", "")
        val balanceAmount = if (rawBalanceStr != null) {
            val isNegBal = rawBalanceStr.startsWith("-") || balanceMatch.value.contains("-")
            val absBal = rawBalanceStr.replace(Regex("""[^\d]"""), "").toLongOrNull()
            if (absBal != null) (if (isNegBal) -absBal else absBal) else null
        } else null

        val firstLine = body.lines().firstOrNull { it.trim().isNotEmpty() }?.trim() ?: ""
        val firstLineDigits = Regex("""^(\d[\d.\-]{8,23})$""").find(firstLine)?.groupValues?.get(1)
        val accMatch = labeledAccountRegex.find(body) ?: standaloneAccountRegex.find(body)
        val rawAcc = (accMatch?.groupValues?.get(1)?.trim() ?: firstLineDigits ?: "")
        val cleanAcc = extractAccountCore(rawAcc)

        val payerMatch = payerCardOrAccRegex.find(body)
        val rawPayer = payerMatch?.groupValues?.get(1)?.trim() ?: ""
        val cleanPayer = if (rawPayer.isNotEmpty() && rawPayer != rawAcc) rawPayer else ""

        var cleanBodyForAmount = body
        if (balanceMatch != null) cleanBodyForAmount = cleanBodyForAmount.replace(balanceMatch.value, "")
        if (accMatch != null) cleanBodyForAmount = cleanBodyForAmount.replace(accMatch.value, "")
        if (payerMatch != null) cleanBodyForAmount = cleanBodyForAmount.replace(payerMatch.value, "")

        var transactionAmount = 0L
        var matchedSign = ""
        var isTomanUnit = false

        for (pattern in transactionPatterns) {
            val match = pattern.find(cleanBodyForAmount)
            if (match != null) {
                val rawNumStr = match.groupValues[1]
                val unitStr = match.groupValues.getOrNull(2) ?: ""
                if (rawNumStr.contains("+")) matchedSign = "+"
                if (rawNumStr.contains("-")) matchedSign = "-"
                if (unitStr == "تومان") isTomanUnit = true

                val cleanNum = rawNumStr.replace(Regex("""[^\d]"""), "").toLongOrNull() ?: 0L
                if (cleanNum in 100L..500_000_000_000L) {
                    transactionAmount = if (isTomanUnit) cleanNum * 10L else cleanNum
                    break
                }
            }
        }

        if (transactionAmount == 0L) return null

        val hasWithdrawalCue = matchedSign == "-" || withdrawalCuesRegex.containsMatchIn(cleanBodyForAmount)
        val hasDepositCue = matchedSign == "+" || depositCuesRegex.containsMatchIn(cleanBodyForAmount)

        val type = when {
            hasWithdrawalCue && !hasDepositCue -> TransactionType.WITHDRAWAL
            hasDepositCue && !hasWithdrawalCue -> TransactionType.DEPOSIT
            matchedSign == "+" -> TransactionType.DEPOSIT
            matchedSign == "-" -> TransactionType.WITHDRAWAL
            hasWithdrawalCue -> TransactionType.WITHDRAWAL
            hasDepositCue -> TransactionType.DEPOSIT
            else -> TransactionType.UNKNOWN
        }

        if (type == TransactionType.UNKNOWN) return null

        val bankName = detectBankName(sender, body)
        // پیامک بانکی معتبر باید یا بانک شناخته‌شده داشته باشد یا شناسه حساب/کارت/شبا/انتقال بانکی همراه با مانده/علامت تراکنش داشته باشد
        val hasAccountOrCardStructure = accMatch != null || payerMatch != null ||
                body.contains("حساب") || body.contains("کارت") || body.contains("شبا") ||
                body.contains("پایا") || body.contains("ساتنا") || body.contains("شتاب")
        val hasBankingAnchor = (bankName != "سایر بانک‌ها" && (balanceAmount != null || hasAccountOrCardStructure || matchedSign.isNotEmpty())) ||
                (hasAccountOrCardStructure && (balanceAmount != null || matchedSign.isNotEmpty()))
        if (!hasBankingAnchor) return null
        val accountKey = if (cleanAcc == "اصلی") "$bankName - اصلی" else "$bankName - $cleanAcc"
        val pDate = PersianDate(timestamp)
        val shamsiDate = PersianDateFormat("Y/m/d - H:i").format(pDate)
        val shamsiDateOnly = PersianDateFormat("Y/m/d").format(pDate)
        val shamsiMonthYear = PersianDateFormat("Y/m").format(pDate)
        val shamsiYear = PersianDateFormat("Y").format(pDate)
        val stableKey = buildStableTransactionKey(body, transactionAmount, balanceAmount, timestamp)

        return BankTransaction(
            id = id,
            stableKey = stableKey,
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

        fun hasWord(token: String): Boolean {
            return Regex("""(?:^|[^آ-یa-z0-9_])""" + Regex.escape(token) + """(?:$|[^آ-یa-z0-9_])""").containsMatchIn(combined)
        }

        return when {
            bodyLower.contains("بانک قرض الحسنه رسالت") || bodyLower.contains("بانک رسالت") || hasWord("رسالت") || hasWord("resalat") -> "بانک قرض‌الحسنه رسالت"
            bodyLower.contains("بانک قرض الحسنه مهر") || bodyLower.contains("مهر ایران") || bodyLower.contains("مهرایران") || bodyLower.contains("qmehr") || hasWord("مهر") -> "بانک قرض‌الحسنه مهر ایران"
            bodyLower.contains("بانک تجارت") || hasWord("تجارت") || hasWord("tejarat") -> "بانک تجارت"
            bodyLower.contains("بانک سامان") || hasWord("سامان") || hasWord("saman") -> "بانک سامان"
            bodyLower.contains("بانک ملت") || hasWord("ملت") || hasWord("mellat") -> "بانک ملت"
            bodyLower.contains("بانک ملی") || hasWord("ملی") || hasWord("melli") || hasWord("bmi") -> "بانک ملی ایران"
            bodyLower.contains("بانک پاسارگاد") || hasWord("پاسارگاد") || hasWord("pasargad") || hasWord("bpi") -> "بانک پاسارگاد"
            bodyLower.contains("بانک صادرات") || hasWord("صادرات") || hasWord("saderat") -> "بانک صادرات ایران"
            bodyLower.contains("بانک سپه") || hasWord("سپه") || hasWord("sepah") -> "بانک سپه"
            bodyLower.contains("بانک کشاورزی") || hasWord("کشاورزی") || hasWord("keshavarzi") -> "بانک کشاورزی"
            bodyLower.contains("بانک مسکن") || hasWord("مسکن") || hasWord("maskan") -> "بانک مسکن"
            bodyLower.contains("بانک رفاه") || hasWord("رفاه") || hasWord("refah") -> "بانک رفاه کارگران"
            bodyLower.contains("بانک شهر") || hasWord("شهر") || hasWord("shahr") -> "بانک شهر"
            bodyLower.contains("بانک اینده") || hasWord("آینده") || hasWord("ayandeh") -> "بانک آینده"
            bodyLower.contains("بانک پارسیان") || hasWord("پارسیان") || hasWord("parsian") -> "بانک پارسیان"
            bodyLower.contains("اقتصاد نوین") || hasWord("eghtesad") -> "بانک اقتصاد نوین"
            bodyLower.contains("بانک سینا") || hasWord("سینا") || hasWord("sina") -> "بانک سینا"
            bodyLower.contains("پست بانک") || hasWord("پست") || hasWord("post") -> "پست بانک ایران"
            bodyLower.contains("بلوبانک") || bodyLower.contains("بلو بانک") || hasWord("بلو") || hasWord("blu") -> "بلوبانک (سامان)"
            else -> "سایر بانک‌ها"
        }
    }

    /**
     * تثبیت کلید حساب بر پایه شماره حساب واقعی (بدون جابه‌جایی برچسب‌ها با تغییر تعداد تراکنش‌ها)
     */
    fun buildStableAccountKey(bankName: String, accountNumber: String): String {
        return if (accountNumber.isBlank() || accountNumber == "اصلی") {
            "$bankName - اصلی"
        } else {
            "$bankName - $accountNumber"
        }
    }

    fun applyAccountBankMappingsInMemory(
        txList: List<BankTransaction>,
        accountBankMappings: Map<String, String>,
        existingProfiles: Map<String, AccountProfileEntity>
    ): Pair<List<BankTransaction>, List<AccountProfileEntity>> {
        val normalizedMap = accountBankMappings.mapKeys { (k, _) -> extractAccountCore(k) }
        val digitEntries = accountBankMappings.mapNotNull { (k, v) ->
            val d = k.replace(Regex("""[^\d]"""), "")
            if (d.length >= 4) d to v else null
        }
        val updatedProfiles = existingProfiles.toMutableMap()

        val updatedTxs = txList.map { tx ->
            val rawClean = tx.rawAccountNumber.trim()
            val txDigits = rawClean.replace(Regex("""[^\d]"""), "").ifEmpty { tx.accountNumber.replace(Regex("""[^\d]"""), "") }
            val core = extractAccountCore(rawClean.ifBlank { tx.accountNumber })
            val matchedBank = accountBankMappings[rawClean]
                ?: accountBankMappings[tx.accountNumber]
                ?: normalizedMap[core]
                ?: digitEntries.firstOrNull { (kDigits, _) ->
                    txDigits.isNotEmpty() && (txDigits == kDigits || txDigits.contains(kDigits) || kDigits.contains(txDigits))
                }?.second

            val finalBank = matchedBank ?: tx.bankName
            val finalAccNum = if (core != "اصلی") core else tx.accountNumber
            val finalKey = buildStableAccountKey(finalBank, finalAccNum)

            val profile = updatedProfiles.getOrPut(finalKey) {
                existingProfiles[tx.accountKey]?.copy(
                    accountKey = finalKey,
                    bankName = finalBank,
                    accountNumber = finalAccNum,
                    customTitle = finalKey
                ) ?: AccountProfileEntity(
                    accountKey = finalKey,
                    bankName = finalBank,
                    accountNumber = finalAccNum,
                    customTitle = finalKey,
                    scope = tx.scope
                )
            }

            tx.copy(
                bankName = finalBank,
                accountNumber = finalAccNum,
                accountKey = finalKey,
                scope = profile.scope
            )
        }

        return Pair(updatedTxs, updatedProfiles.values.toList())
    }

    fun detectInternalTransfers(
        transactions: List<BankTransaction>,
        savedNotes: Map<String, TransactionNoteEntity>
    ): List<BankTransaction> {
        val result = transactions.toMutableList()
        val maxWindowMillis = 2 * 60 * 60 * 1000L
        val maxFeeTolerance = 5_000L
        val matchedDepositIds = mutableSetOf<String>()

        for (i in result.indices) {
            val tx = result[i]
            val noteEntity = savedNotes[tx.stableKey] ?: savedNotes[tx.id]
            val override = noteEntity?.isInternalTransferOverride
            if (override != null) {
                if (override) {
                    result[i] = tx.copy(type = TransactionType.INTERNAL, transferPairInfo = "ثبت دستی به عنوان انتقال داخلی")
                }
                continue
            }

            if (tx.type == TransactionType.WITHDRAWAL && tx.scope != "مدیریت ساختمان") {
                val matchingDepositIndex = result.indexOfFirst { other ->
                    other.id != tx.id &&
                            !matchedDepositIds.contains(other.id) &&
                            other.type == TransactionType.DEPOSIT &&
                            other.scope != "مدیریت ساختمان" &&
                            other.accountKey != tx.accountKey &&
                            abs(other.timestampMillis - tx.timestampMillis) <= maxWindowMillis &&
                            (tx.amount - other.amount) in 0L..maxFeeTolerance &&
                            (savedNotes[other.stableKey] ?: savedNotes[other.id])?.isInternalTransferOverride != false
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
                if (ignoredSmsIds.contains(parsedTx.stableKey)) return@let

                val savedEntity = savedNotes[parsedTx.stableKey] ?: savedNotes[id]
                val autoMapping = if (parsedTx.payerIdentifier.isNotEmpty()) payerMappings[parsedTx.payerIdentifier] else null
                val resolvedUnitId = savedEntity?.unitId ?: autoMapping?.unitId
                val autoUnitNote = autoMapping?.unitTitle ?: ""
                val finalNote = savedEntity?.note?.ifBlank { autoUnitNote } ?: autoUnitNote

                rawTransactions.add(
                    parsedTx.copy(
                        unitId = resolvedUnitId,
                        receiptImagePath = savedEntity?.receiptImagePath ?: "",
                        userNote = finalNote,
                        category = savedEntity?.category ?: ""
                    )
                )
            }
        }
    }

    // نگاشت خودکار شماره حساب‌های بدون نام بانک به بانک شناخته‌شده در سایر پیامک‌ها
    val knownBankByCore = rawTransactions
        .filter { it.bankName != "سایر بانک‌ها" && it.accountNumber != "اصلی" }
        .associate { it.accountNumber to it.bankName }

    val autoResolvedTxs = rawTransactions.map { tx ->
        if (tx.bankName == "سایر بانک‌ها" && tx.accountNumber != "اصلی") {
            val resolved = knownBankByCore[tx.accountNumber]
            if (resolved != null) {
                tx.copy(
                    bankName = resolved,
                    accountKey = IranBankSmsParser.buildStableAccountKey(resolved, tx.accountNumber)
                )
            } else tx
        } else tx
    }

    // اعمال نگاشت‌های دستی کاربر و پروفایل‌های حساب
    val (mappedTxs, updatedProfilesList) = IranBankSmsParser.applyAccountBankMappingsInMemory(
        txList = autoResolvedTxs,
        accountBankMappings = accountBankMappings,
        existingProfiles = discoveredAccounts
    )

    val finalTxs = mappedTxs.map { tx ->
        val finalCat = tx.category.ifBlank {
            if (tx.unitId != null && tx.scope == "مدیریت ساختمان") "شارژ ماهیانه واحدها" else ""
        }
        tx.copy(category = finalCat)
    }.toMutableList()

    updatedProfilesList.forEach { discoveredAccounts[it.accountKey] = it }

    // تطبیق دقیق صورت‌حساب اکسل/CSV با پیامک‌ها بدون حذف ردیف‌های هم‌روز و هم‌مبلغ
    val matchedSmsIds = mutableSetOf<String>()
    for (imp in importedRecords) {
        if (ignoredSmsIds.contains(imp.recordId)) continue
        val impDateOnly = imp.persianDate.substringBefore(" - ").trim()

        val exactBalIdx = if (imp.balance != null && imp.balance != 0L) {
            finalTxs.indexOfFirst { smsTx ->
                !matchedSmsIds.contains(smsTx.id) &&
                    smsTx.accountKey == imp.accountKey &&
                    smsTx.amount == imp.amount &&
                    smsTx.persianDateOnly == impDateOnly &&
                    smsTx.balance != null && abs(smsTx.balance - imp.balance) <= 10L
            }
        } else -1

        val matchIdx = if (exactBalIdx != -1) exactBalIdx else {
            finalTxs.indexOfFirst { smsTx ->
                !matchedSmsIds.contains(smsTx.id) &&
                    smsTx.accountKey == imp.accountKey &&
                    smsTx.amount == imp.amount &&
                    smsTx.persianDateOnly == impDateOnly &&
                    ((smsTx.type == TransactionType.DEPOSIT) == imp.isDeposit)
            }
        }

        if (matchIdx != -1) {
            val existingSms = finalTxs[matchIdx]
            matchedSmsIds.add(existingSms.id)
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
            finalTxs[matchIdx] = existingSms.copy(
                payerIdentifier = mergedPayer,
                unitId = resolvedUnitId,
                userNote = mergedNote,
                category = mergedCat,
                isFromExcel = true
            )
            continue
        }

        val impBankName = imp.accountKey.substringBefore(" - ").trim()
        val profile = discoveredAccounts.getOrPut(imp.accountKey) {
            AccountProfileEntity(
                accountKey = imp.accountKey,
                bankName = impBankName,
                accountNumber = imp.accountKey.substringAfter(" - ", "صورت‌حساب"),
                customTitle = imp.accountKey,
                scope = "شخصی"
            )
        }
        val savedEntity = savedNotes[imp.recordId]
        val autoMapping = if (imp.payerIdentifier.isNotEmpty()) payerMappings[imp.payerIdentifier] else null
        val resolvedUnitId = savedEntity?.unitId ?: autoMapping?.unitId
        val autoUnitNote = autoMapping?.unitTitle ?: ""
        val finalNote = savedEntity?.note?.ifBlank { autoUnitNote.ifBlank { imp.description } } ?: autoUnitNote.ifBlank { imp.description }
        val yearOnly = impDateOnly.substringBefore("/")

        finalTxs.add(
            BankTransaction(
                id = imp.recordId,
                stableKey = imp.recordId,
                sender = "فایل صورت‌حساب بانکی",
                bankName = profile.bankName,
                accountNumber = profile.accountNumber,
                accountKey = imp.accountKey,
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
                category = savedEntity?.category ?: if (resolvedUnitId != null && profile.scope == "مدیریت ساختمان") "شارژ ماهیانه واحدها" else ""
            )
        )
    }

    val enrichedTransactions = finalTxs.map { tx ->
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
