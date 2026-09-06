package com.hag.al_quran.search

import android.content.Context

class SearchRepository(private val context: Context) {

    private val ayat by lazy { SearchUtils.loadAllAyat(context) }
    private val index by lazy { SearchIndex(ayat, includeBasmala = true) }
    private val surahNames by lazy { SearchUtils.loadSurahNames(context) }

    fun search(query: String, options: SearchOptions): List<SearchResultItem> {
        val q = query.trim()
        if (q.length < 2) return emptyList()

        // 1) جلب الآيات المطابقة
        val hits: List<SearchIndex.AyahItem> = when (options.mode) {

            SearchOptions.Mode.EXACT_WORD -> {
                // مطابقة تامة فقط (بدون تجذير/سوابق)
                index.searchWholeWord(q, useStemming = false)
            }

            SearchOptions.Mode.PARTIAL -> {
                // بحث جزئي سريع
                if (SearchEngine.isReady()) {
                    SearchEngine.searchPartialFast(context, q)
                } else {
                    index.searchPartial(q)
                }
            }

            SearchOptions.Mode.EXACT_WORD_WITH_PREFIXES -> {
                // مطابقة الكلمة مع السماح بالسوابق (الواو، الفاء، الباء، إلخ)
                index.searchWholeWord(q, useStemming = true)
            }
        }

        // 2) تحويل النتائج إلى SearchResultItem بنفس التركيبة الأصلية لديك تماماً
        return hits.map { a ->
            val surahName = surahNames.getOrNull(a.surah - 1) ?: "سورة ${a.surah}"

            // تمرير المتغيرات بالترتيب الأصلي بدون أسماء مرجعية لتفادي أي خطأ
            SearchResultItem(a.surah, a.ayah, a.page, surahName, a.text)
        }
    }

    /** عدّ النتائج لواجهة المستخدم */
    fun count(query: String, options: SearchOptions): Any {
        val q = query.trim()
        if (q.isEmpty()) return 0

        return when (options.mode) {
            SearchOptions.Mode.EXACT_WORD -> {
                // العد للكلمة المطابقة تماماً
                SearchUtils.countOccurrencesSmart(context, q).first
            }

            SearchOptions.Mode.PARTIAL -> {
                // العد للبحث الجزئي
                if (SearchEngine.isReady()) {
                    SearchEngine.countOccurrencesSmart(context, q)
                } else {
                    index.searchPartial(q).size
                }
            }

            SearchOptions.Mode.EXACT_WORD_WITH_PREFIXES -> {
                // العد للكلمة مع السوابق
                SearchUtils.countOccurrencesSmart(context, q).second
            }
        }
    }
}