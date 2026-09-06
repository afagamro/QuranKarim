package com.hag.al_quran.search

/**
 * خيارات وإعدادات محرك البحث لضمان التحكم الكامل بالدقة والسرعة
 */
data class SearchOptions(
    val mode: Mode = Mode.PARTIAL,
    val exactTashkeel: Boolean = false, // يعمل مع EXACT_WORD لعدم إهمال التشكيل
    val allowPrefixes: Boolean = true,  // يسمح بالسوابق (و/ف/ب/ك/ل/س/ت) عند البحث بكلمة كاملة
    val normalizeLetterVariations: Boolean = true, // توحيد (أ/إ/آ/ٱ)، (ة/ه)، (ى/ي)
    val debounceMs: Long = 200L         // زمن التأخير المستحسن للبحث الفوري
) {
    enum class Mode {
        /**
         * بحث جزئي داخل النص (أكثر استخداماً ومرونة للعبارات والآيات)
         */
        PARTIAL,

        /**
         * مطابقة كلمة كاملة فقط بدون سوابق أو زوائد
         */
        EXACT_WORD,

        /**
         * مطابقة كلمة كاملة مع السماح بسوابق المعاني والربط (و/ف/ب/ك/ل/س/ت)
         */
        EXACT_WORD_WITH_PREFIXES
    }
}