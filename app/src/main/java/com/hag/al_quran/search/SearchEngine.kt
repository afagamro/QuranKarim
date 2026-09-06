package com.hag.al_quran.search

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * محرّك بحث فائق السرعة والدقة:
 * - تحميل الآيات مع نص مُطبّع norm مرة واحدة.
 * - فهرس ثلاثيات (Trigram Index) محسّن للذاكرة للبحث الفوري دون استهلاك موارد الجهاز.
 */
object SearchEngine {

    // بيانات الأساس
    private lateinit var items: List<SearchIndex.AyahItem>
    private lateinit var surahNames: List<String>
    private lateinit var normArray: Array<String>

    // فهرس ثلاثيات: "abc" -> [indices...]
    private lateinit var trigramIndex: HashMap<String, IntArray>

    // فهرس كلمات: token -> [indices...]
    private lateinit var invertedIndexTokens: HashMap<String, IntArray>
    private lateinit var tokensPerAyah: Array<Array<String>>

    private val ready = AtomicBoolean(false)

    fun isReady(): Boolean = ready.get()

    /** تهيئة آمنة خفيفة الذاكرة */
    fun init(context: Context) {
        if (ready.get()) return
        synchronized(this) {
            if (ready.get()) return

            // تحميل الآيات
            items = SearchUtils.loadAllAyat(context)
            surahNames = SearchUtils.loadSurahNames(context)

            normArray = Array(items.size) { i -> items[i].norm }

            // كلمات لكل آية
            tokensPerAyah = Array(items.size) { i ->
                items[i].norm.split(' ').filter { it.isNotBlank() }.toTypedArray()
            }

            // ---------------------------
            // بناء فهرس ثلاثيات Trigrams
            // ---------------------------
            val tempTri = HashMap<String, MutableList<Int>>(100_000)
            for (i in normArray.indices) {
                val s = normArray[i]
                if (s.length >= 3) {
                    val seen = HashSet<String>()
                    var k = 0
                    val last = s.length - 2
                    while (k < last) {
                        val tri = s.substring(k, k + 3)
                        if (tri.none { it.isWhitespace() }) {
                            if (seen.add(tri)) {
                                tempTri.getOrPut(tri) { ArrayList() }.add(i)
                            }
                        }
                        k++
                    }
                } else if (s.isNotBlank()) {
                    val key = "@LEN<3@$s"
                    tempTri.getOrPut(key) { ArrayList() }.add(i)
                }
            }
            trigramIndex = HashMap(tempTri.size)
            for ((k, list) in tempTri) trigramIndex[k] = list.toIntArray()

            // ---------------------------------
            // بناء فهرس الكلمات
            // ---------------------------------
            val tempTok = HashMap<String, MutableList<Int>>(40_000)
            for (i in tokensPerAyah.indices) {
                val unique = HashSet<String>()
                for (t in tokensPerAyah[i]) {
                    if (t.isNotBlank() && unique.add(t)) {
                        tempTok.getOrPut(t) { ArrayList() }.add(i)
                    }
                }
            }
            invertedIndexTokens = HashMap(tempTok.size)
            for ((k, list) in tempTok) invertedIndexTokens[k] = list.toIntArray()

            ready.set(true)
        }
    }

    fun ensureReady(context: Context) {
        if (!isReady()) init(context)
    }

    fun getItems(): List<SearchIndex.AyahItem> = items
    fun getSurahNames(): List<String> = surahNames

    // -----------------------------
    // بحث جزئي سريع ودقيق للغاية
    // -----------------------------
    fun searchPartialFast(context: Context, queryRaw: String): List<SearchIndex.AyahItem> {
        ensureReady(context)
        if (!isReady()) return emptyList()

        val q = SearchUtils.normalizeArabic(queryRaw)
        if (q.isBlank()) return emptyList()

        // 1) استعلام قصير جداً (< 3 حروف): مسح مباشر عالي السرعة
        if (q.length < 3) {
            val out = ArrayList<SearchIndex.AyahItem>()
            for (i in normArray.indices) {
                if (normArray[i].contains(q)) {
                    out.add(items[i])
                }
            }
            return out
        }

        // 2) استعلام >= 3: استخدام ثلاثيات الفهرس
        val tris = extractUniqueTrigrams(q)
        if (tris.isEmpty()) {
            return fallbackScan(q)
        }

        // إيجاد أقصر مصفوفة مرشحة لتطبيق التقاطع بأعلى كفاءة
        var smallestArr: IntArray? = null
        val arraysList = ArrayList<IntArray>(tris.size)

        for (t in tris) {
            val arr = trigramIndex[t] ?: return emptyList() // إذا لم توجد ثلاثية واحدة، فالنتيجة صفر فوراً
            arraysList.add(arr)
            if (smallestArr == null || arr.size < smallestArr.size) {
                smallestArr = arr
            }
        }

        val candidates = smallestArr ?: return emptyList()
        val out = ArrayList<SearchIndex.AyahItem>(candidates.size)

        // التحقق من المطابقة المباشرة لنص الاستعلام الكامل على المرشحين المحتملين فقط
        for (idx in candidates) {
            val normText = normArray[idx]
            var matchAllTrigrams = true

            // تأكيد تواجده في باقي مصفوفات الثلاثيات
            for (arr in arraysList) {
                if (arr !== smallestArr && !containsBinary(arr, idx)) {
                    matchAllTrigrams = false
                    break
                }
            }

            if (matchAllTrigrams && normText.contains(q)) {
                out.add(items[idx])
            }
        }

        return out
    }

    private fun containsBinary(arr: IntArray, target: Int): Boolean {
        var low = 0
        var high = arr.size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val midVal = arr[mid]
            if (midVal < target) low = mid + 1
            else if (midVal > target) high = mid - 1
            else return true
        }
        return false
    }

    private fun extractUniqueTrigrams(s: String): List<String> {
        val res = ArrayList<String>(s.length)
        val seen = HashSet<String>()
        var i = 0
        val last = s.length - 2
        while (i < last) {
            val tri = s.substring(i, i + 3)
            if (tri.none { it.isWhitespace() } && seen.add(tri)) {
                res.add(tri)
            }
            i++
        }
        if (res.isEmpty() && s.isNotBlank()) {
            res.add("@LEN<3@$s")
        }
        return res
    }

    private fun fallbackScan(q: String): List<SearchIndex.AyahItem> {
        val out = ArrayList<SearchIndex.AyahItem>()
        for (i in normArray.indices) {
            if (normArray[i].contains(q)) {
                out.add(items[i])
            }
        }
        return out
    }

    // ------------------------------------
    // عدّ الإحصاءات الذكي
    // ------------------------------------
    fun countOccurrencesSmart(context: Context, query: String): Int {
        ensureReady(context)
        if (!isReady()) return 0
        val q = SearchUtils.normalizeArabic(query)
        if (q.isBlank()) return 0

        var c = 0
        for (i in normArray.indices) {
            if (normArray[i].contains(q)) c++
        }
        return c
    }
}