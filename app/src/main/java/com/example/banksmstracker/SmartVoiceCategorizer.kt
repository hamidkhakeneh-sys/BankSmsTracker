package com.example.banksmstracker

object SmartVoiceCategorizer {

    private val keywordRules = listOf(
        "خودرو و سوخت" to listOf("بنزین", "گازوئیل", "سوخت", "پمپ", "تعمیرگاه", "تعویض روغن", "ماشین", "خودرو", "لوازم یدکی", "بیمه ماشین", "کارواش", "پارکینگ", "عوارض"),
        "خوراک و سوپرمارکت" to listOf("سوپر", "سوپرمارکت", "نان", "نون", "میوه", "سبزی", "گوشت", "مرغ", "پروتئین", "لبنیات", "شیر", "برنج", "خواربار", "فروشگاه", "هایپر", "افق", "کوروش", "رفاه", "جانبو"),
        "قبوض و اینترنت" to listOf("قبض", "تلفن", "موبایل", "همراه اول", "ایرانسل", "رایتل", "اینترنت", "شارژ اینترنت", "بسته", "شاتل", "مخابرات"),
        "رستوران و کافه" to listOf("رستوران", "کافه", "قهوه", "فست فود", "پیتزا", "ساندویچ", "کباب", "غذا", "ناهار", "شام", "صبحانه", "اسنپ فود"),
        "درمان و دارو" to listOf("دارو", "داروخانه", "دکتر", "پزشک", "ویزیت", "بیمارستان", "درمانگاه", "آزمایش", "دندان", "دندانپزشکی", "عینک"),
        "پوشاک و خرید" to listOf("لباس", "کفش", "پوشاک", "شلوار", "پیراهن", "مانتو", "کیف", "دیجی کالا", "خرید اینترنتی"),
        "اقساط و وام" to listOf("قسط", "اقساط", "وام", "چک", "بدهی", "اجاره"),
        "حقوق و دستمزد" to listOf("حقوق", "دستمزد", "پاداش", "کارانه", "اضافه کار", "مزایا"),
        "سود و سرمایه‌گذاری" to listOf("سود", "بورس", "سهام", "ارز", "طلا", "سکه"),
        "شارژ ماهیانه واحدها" to listOf("شارژ", "واحد", "طبقه", "مستاجر", "مالک", "همسایه"),
        "تعمیر و نگهداری آسانسور" to listOf("آسانسور", "سرویس ماهانه", "کابین", "موتور آسانسور"),
        "نظافت و سرایداری" to listOf("نظافت", "سرایدار", "نگهبان", "راه پله", "پارکینگ شویی", "زباله"),
        "قبوض مشاعات (آب/برق/گاز)" to listOf("مشاعات", "قبض برق ساختمان", "قبض آب", "قبض گاز"),
        "تاسیسات و موتورخانه" to listOf("موتورخانه", "پمپ آب", "تاسیسات", "لوله کشی", "آیفون", "درب پارکینگ", "جک", "روشنایی", "لامپ")
    )

    fun suggestCategory(spokenText: String, availableCategories: List<String>): String? {
        val cleanText = spokenText.trim()
        if (cleanText.isEmpty()) return null

        for (cat in availableCategories) {
            if (cleanText.contains(cat, ignoreCase = true)) return cat
            val words = cat.split(" ", "و", "(", ")", "/").filter { it.length >= 3 }
            if (words.any { cleanText.contains(it, ignoreCase = true) }) return cat
        }

        for ((targetCategory, keywords) in keywordRules) {
            if (keywords.any { cleanText.contains(it, ignoreCase = true) }) {
                val matched = availableCategories.find { it.contains(targetCategory) || targetCategory.contains(it) }
                if (matched != null) return matched
            }
        }
        return null
    }
}
