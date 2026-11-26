package com.example.banksmstracker

import kotlin.math.abs

data class RawInboxSms(
    val id: String,
    val sender: String,
    val body: String,
    val timestampMillis: Long
)

data class AutoDescriptionResult(
    val autoNote: String,
    val suggestedCategory: String
)

object AutoTransactionDescriber {

    private val knownMerchants = listOf(
        Triple(listOf("digikala", "دیجی کالا", "دیجی‌کالا"), "خرید از سایت دیجی‌کالا", "پوشاک و خرید"),
        Triple(listOf("snappfood", "اسنپ فود", "اسنپ‌فود"), "سفارش غذا از اسنپ‌فود", "رستوران و کافه"),
        Triple(listOf("snapp", "اسنپ"), "پرداخت در سامانه اسنپ", "حمل‌ونقل"),
        Triple(listOf("tapsi", "تپسی"), "پرداخت در سامانه تپسی", "حمل‌ونقل"),
        Triple(listOf("torob", "ترب"), "خرید از سایت ترب", "پوشاک و خرید"),
        Triple(listOf("basalam", "باسلام"), "خرید از سایت باسلام", "پوشاک و خرید"),
        Triple(listOf("alibaba", "علی بابا", "علی‌بابا"), "خرید بلیط از علی‌بابا", "حمل‌ونقل"),
        Triple(listOf("mrbilit", "مستر بلیط"), "خرید بلیط از مستربلیط", "حمل‌ونقل"),
        Triple(listOf("flightio", "فلایتیو"), "خرید بلیط از فلایتیو", "حمل‌ونقل"),
        Triple(listOf("okala", "اکالا"), "خرید سوپرمارکتی از اکالا", "خوراک و سوپرمارکت"),
        Triple(listOf("shatel", "شاتل"), "پرداخت اینترنت شاتل", "قبوض و اینترنت"),
        Triple(listOf("asiatech", "آسیاتک"), "پرداخت اینترنت آسیاتک", "قبوض و اینترنت"),
        Triple(listOf("irancell", "ایرانسل"), "خرید شارژ / بسته ایرانسل", "قبوض و اینترنت"),
        Triple(listOf("mci", "همراه اول", "ewano", "اوانو"), "پرداخت قبض / بسته همراه اول", "قبوض و اینترنت"),
        Triple(listOf("rightel", "رایتل"), "خرید شارژ / بسته رایتل", "قبوض و اینترنت"),
        Triple(listOf("ap.ir", "asanpardakht", "آسان پرداخت"), "پرداخت از طریق آپ (آسان‌پرداخت)", "قبوض و اینترنت"),
        Triple(listOf("bazaar", "کافه بازار"), "خرید از کافه بازار", "پوشاک و خرید"),
        Triple(listOf("myket", "مایکت"), "خرید از مایکت", "پوشاک و خرید"),
        Triple(listOf("fidibo", "فیدیبو"), "خرید کتاب از فیدیبو", "پوشاک و خرید"),
        Triple(listOf("taaghche", "طاقچه"), "خرید کتاب از طاقچه", "پوشاک و خرید"),
        Triple(listOf("filimo", "فیلیمو"), "خرید اشتراک فیلیمو", "رستوران و کافه"),
        Triple(listOf("namava", "نماوا"), "خرید اشتراک نماوا", "رستوران و کافه"),
        Triple(listOf("divar", "دیوار"), "پرداخت آگهی در دیوار", "سایر موارد"),
        Triple(listOf("sheypoor", "شیپور"), "پرداخت آگهی در شیپور", "سایر موارد")
    )

    private val urlRegex = Regex("""(?:https?://)?(?:www\.)?([a-zA-Z0-9\-]+\.(?:ir|com|net|org|co|shop|online))""", RegexOption.IGNORE_CASE)
    private val destinationCardOrAccRegex = Regex("""(?:به کارت|به حساب|مقصد|شماره کارت طرف مقابل)\s*[:=\-*]?\s*([\d*\-]{4,19})""")
    private val counterPartyNameRegex = Regex("""(?:نام کامل طرف مقابل|نام طرف مقابل|به نام)\s*[:=\-]?\s*([^،,\n\r]+)""")

    fun inferTransactionDetails(
        tx: BankTransaction,
        allInboxSms: List<RawInboxSms>
    ): AutoDescriptionResult? {
        val normBody = IranBankSmsParser.normalizePersian(tx.rawMessage)
        val lowerBody = normBody.lowercase()

        val windowMillis = 5 * 60 * 1000L
        val nearbySmsList = allInboxSms.filter { sms ->
            sms.id != tx.id &&
            abs(sms.timestampMillis - tx.timestampMillis) <= windowMillis &&
            !sms.body.contains("رمز پویا") &&
            !sms.body.contains("رمز دوم")
        }

        val combinedContext = buildString {
            append(normBody)
            append(" ")
            nearbySmsList.forEach {
                append(IranBankSmsParser.normalizePersian(it.sender))
                append(" ")
                append(IranBankSmsParser.normalizePersian(it.body))
                append(" ")
            }
        }
        val lowerCombined = combinedContext.lowercase()

        for ((keywords, title, category) in knownMerchants) {
            if (keywords.any { lowerCombined.contains(it) }) {
                return AutoDescriptionResult(
                    autoNote = title,
                    suggestedCategory = if (tx.type == TransactionType.WITHDRAWAL) category else ""
                )
            }
        }

        val urlMatch = urlRegex.find(combinedContext)
        if (urlMatch != null && tx.type == TransactionType.WITHDRAWAL) {
            val domain = urlMatch.groupValues[1].lowercase()
            val isBankDomain = listOf("bmi.ir", "mellat", "saderat", "tejarat", "sepah", "saman", "bpi.ir", "shaparak").any { domain.contains(it) }
            if (!isBankDomain) {
                return AutoDescriptionResult(
                    autoNote = "خرید اینترنتی از سایت $domain",
                    suggestedCategory = "پوشاک و خرید"
                )
            }
        }

        if (tx.type == TransactionType.WITHDRAWAL) {
            when {
                normBody.contains("قبض برق") || lowerCombined.contains("توزیع نیروی برق") ->
                    return AutoDescriptionResult("پرداخت قبض برق", if (tx.scope == "مدیریت ساختمان") "قبوض مشاعات (آب/برق/گاز)" else "قبوض و اینترنت")
                normBody.contains("قبض اب") || normBody.contains("قبض آب") || lowerCombined.contains("آبفا") ->
                    return AutoDescriptionResult("پرداخت قبض آب", if (tx.scope == "مدیریت ساختمان") "قبوض مشاعات (آب/برق/گاز)" else "قبوض و اینترنت")
                normBody.contains("قبض گاز") || lowerCombined.contains("شرکت گاز") ->
                    return AutoDescriptionResult("پرداخت قبض گاز", if (tx.scope == "مدیریت ساختمان") "قبوض مشاعات (آب/برق/گاز)" else "قبوض و اینترنت")
                normBody.contains("قبض تلفن") || normBody.contains("قبض موبایل") || normBody.contains("مخابرات") ->
                    return AutoDescriptionResult("پرداخت قبض تلفن / موبایل", "قبوض و اینترنت")
                normBody.contains("پرداخت قبض") || normBody.contains("شناسه قبض") ->
                    return AutoDescriptionResult("پرداخت قبض", "قبوض و اینترنت")
            }
        }

        val isTransfer = normBody.contains("انتقال") || normBody.contains("پایا") ||
                normBody.contains("ساتنا") || normBody.contains("سامانه پل") ||
                normBody.contains("کارت به کارت") || normBody.contains("انتقالی")

        if (isTransfer) {
            val destMatch = destinationCardOrAccRegex.find(normBody)?.groupValues?.get(1)?.trim() ?: tx.payerIdentifier
            val personName = counterPartyNameRegex.find(normBody)?.groupValues?.get(1)?.trim() ?: ""

            val transferLabel = when {
                tx.type == TransactionType.WITHDRAWAL && personName.isNotEmpty() -> "انتقال به $personName"
                tx.type == TransactionType.WITHDRAWAL && destMatch.isNotEmpty() -> "انتقال به حساب/کارت $destMatch"
                tx.type == TransactionType.WITHDRAWAL -> "انتقال وجه (پایا / کارت به کارت)"
                tx.type == TransactionType.DEPOSIT && personName.isNotEmpty() -> "واریز از طرف $personName"
                tx.type == TransactionType.DEPOSIT && destMatch.isNotEmpty() -> "واریز از حساب/کارت $destMatch"
                else -> "انتقال وجه بانکی"
            }

            return AutoDescriptionResult(
                autoNote = transferLabel,
                suggestedCategory = ""
            )
        }

        if (tx.type == TransactionType.WITHDRAWAL && (normBody.contains("خرید") || lowerBody.contains("pos"))) {
            return AutoDescriptionResult(
                autoNote = "خرید با کارت (کارتخوان)",
                suggestedCategory = ""
            )
        }

        return null
    }
}
