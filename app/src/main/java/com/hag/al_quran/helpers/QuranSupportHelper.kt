// File: app/src/main/java/com/hag/al_quran/helpers/QuranSupportHelper.kt
package com.hag.al_quran.helpers

import android.app.ProgressDialog
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import com.hag.al_quran.QariAdapter
import com.hag.al_quran.QariItem
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.download.PagesDownloadService
import com.hag.al_quran.tafsir.TafsirUtils
import com.hag.al_quran.tafsir.TafsirUtils.downloadTafsirIfNeeded
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

class QuranSupportHelper(
    private val activity: QuranPageActivity,
    private val provider: MadaniPageProvider
) {

    // ===== Utils بسيطة =====
    private fun View?.show() { this?.visibility = View.VISIBLE }
    private fun View?.hide() { this?.visibility = View.GONE }
    private fun TextView?.setSafeText(s: CharSequence?) { this?.text = s ?: "" }

    // ======================= تفضيل نوع الشبكة =======================
    // نفس ملف التفضيلات الذي تستخدمه شاشة الإعدادات وخدمة التنزيل.
    private val netPrefs by lazy { activity.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private companion object { private const val PREF_NETWORK = "pref_network_type" }
    private enum class NetworkPref { WIFI_ONLY, MOBILE_ONLY, ANY }

    private fun getNetworkPref(): NetworkPref = when (netPrefs.getString(PREF_NETWORK, "WIFI_ONLY")) {
        "MOBILE_ONLY" -> NetworkPref.MOBILE_ONLY
        "ANY" -> NetworkPref.ANY
        else -> NetworkPref.WIFI_ONLY
    }
    private fun isNetworkPrefSet(): Boolean = netPrefs.contains(PREF_NETWORK)

    /** حوار يطلب نوع الشبكة ويحفظه، ثم ينفّذ onDone */
    private fun pickNetworkThen(onDone: () -> Unit) {
        val items = arrayOf("واي-فاي فقط", "البيانات فقط", "أي شبكة (واي-فاي أو بيانات)")
        var sel = when (getNetworkPref()) {
            NetworkPref.WIFI_ONLY -> 0
            NetworkPref.MOBILE_ONLY -> 1
            NetworkPref.ANY -> 2
        }
        AlertDialog.Builder(activity)
            .setTitle("نوع الشبكة للتحميل")
            .setSingleChoiceItems(items, sel) { _, w -> sel = w }
            .setPositiveButton("حفظ") { d, _ ->
                val value = when (sel) { 0 -> "WIFI_ONLY"; 1 -> "MOBILE_ONLY"; else -> "ANY" }
                netPrefs.edit().putString(PREF_NETWORK, value).apply()
                Toast.makeText(activity, "تم حفظ التفضيل: ${items[sel]}", Toast.LENGTH_SHORT).show()
                d.dismiss()
                onDone()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun showNetworkTypeDialog() {
        val items = arrayOf("واي-فاي فقط", "البيانات فقط", "أي شبكة (واي-فاي أو بيانات)")
        var sel = when (getNetworkPref()) {
            NetworkPref.WIFI_ONLY -> 0
            NetworkPref.MOBILE_ONLY -> 1
            NetworkPref.ANY -> 2
        }
        AlertDialog.Builder(activity)
            .setTitle("نوع الشبكة للتحميل")
            .setSingleChoiceItems(items, sel) { _, w -> sel = w }
            .setPositiveButton("حفظ") { d, _ ->
                val value = when (sel) { 0 -> "WIFI_ONLY"; 1 -> "MOBILE_ONLY"; else -> "ANY" }
                netPrefs.edit().putString(PREF_NETWORK, value).apply()
                Toast.makeText(activity, "تم حفظ التفضيل: ${items[sel]}", Toast.LENGTH_SHORT).show()
                d.dismiss()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    // ========= نموذج داخلي للآية =========
    data class SimpleAyah(val surah: Int, val ayah: Int)

    /** أسماء السور من assets/surahs.json (مع fallback مبسّط) */
    private val surahNamesByNumber: Map<Int, String> by lazy {
        val fallback = mapOf(1 to "الفاتحة", 2 to "البقرة", 3 to "آل عمران")
        try {
            val json = activity.assets.open("surahs.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(json)
            val map = mutableMapOf<Int, String>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val num = o.optInt("number", o.optInt("index", i + 1))
                val name = o.optString("name_ar").ifEmpty { o.optString("name") }
                if (num > 0 && name.isNotEmpty()) map[num] = name
            }
            if (map.isEmpty()) fallback else map
        } catch (_: Throwable) { fallback }
    }

    /** خريطة الصفحة -> قائمة الآيات من ayah_bounds_all.json (تدعم sura_id/aya_id أيضًا) */
    private val pageAyahsFromBounds: Map<Int, List<SimpleAyah>> by lazy {
        try {
            val text = activity.assets.open("pages/ayah_bounds_all.json")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JSONObject(text)
            val map = mutableMapOf<Int, MutableList<SimpleAyah>>()
            val keys = root.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val page = k.toIntOrNull() ?: continue
                val arr = root.optJSONArray(k) ?: continue
                val list = mutableListOf<SimpleAyah>()
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val s = o.optInt("sura_id",
                        o.optInt("surah",
                            o.optInt("sura",
                                o.optInt("s", 0))))
                    val a = o.optInt("aya_id",
                        o.optInt("ayah",
                            o.optInt("a", 0)))
                    if (s > 0 && a > 0) list += SimpleAyah(s, a)
                }
                if (list.isNotEmpty()) map[page] = list
            }
            map
        } catch (_: Throwable) { emptyMap() }
    }

    /** نطاق الآيات للصفحة: (الأولى, الأخيرة) إن توفر */
    fun getAyahRangeForPage(page: Int): Pair<SimpleAyah, SimpleAyah>? {
        pageAyahsFromBounds[page]?.let { list ->
            if (list.isNotEmpty()) return list.first() to list.last()
        }
        return null
    }

    /** رقم السورة لصفحة معيّنة (يُستخدم لإصلاح خيار "السورة") */
    fun getSurahNumberByPage(page: Int): Int {
        pageAyahsFromBounds[page]?.firstOrNull()?.let { return it.surah }
        val firstOnPage = loadAyahBoundsForPage(page).firstOrNull()
        val sNum = firstOnPage?.sura_id ?: 0
        return if (sNum > 0) sNum else 0
    }

    /** اسم السورة لصفحة معيّنة */
    fun getSurahNameByPage(page: Int): String {
        val sNum = getSurahNumberByPage(page)
        return if (sNum > 0) getSurahNameByNumber(sNum) else ""
    }
    /** alias */
    fun getSurahNameForPage(page: Int): String = getSurahNameByPage(page)

    // ===== احسب الجزء/الحزب/الربع محليًا من رقم الصفحة =====
    fun getJuzForPage(page: Int): Int {
        val p = page.coerceIn(1, 604)
        for (i in 0 until 30) {
            val start = JUZ_START_PAGES[i]
            val end = if (i == 29) 604 else JUZ_START_PAGES[i + 1] - 1
            if (p in start..end) return i + 1
        }
        return 0
    }

    fun getHizbForPage(page: Int): Int {
        val p = page.coerceIn(1, 604)
        var juzIdx = -1
        var start = 1
        var end = 604
        for (i in 0 until 30) {
            val s = JUZ_START_PAGES[i]
            val e = if (i == 29) 604 else JUZ_START_PAGES[i + 1] - 1
            if (p in s..e) { juzIdx = i; start = s; end = e; break }
        }
        if (juzIdx == -1) return 0
        val len = (end - start + 1).coerceAtLeast(1)
        val rel = (p - start).coerceAtLeast(0)
        val quarterIdxInJuz = kotlin.math.floor(rel * 8.0 / len).toInt().coerceIn(0, 7)
        val hizbIdxInJuz = quarterIdxInJuz / 4
        return (juzIdx * 2) + hizbIdxInJuz + 1
    }

    fun getQuarterForPage(page: Int): Int {
        val p = page.coerceIn(1, 604)
        var start = 1
        var end = 604
        for (i in 0 until 30) {
            val s = JUZ_START_PAGES[i]
            val e = if (i == 29) 604 else JUZ_START_PAGES[i + 1] - 1
            if (p in s..e) { start = s; end = e; break }
        }
        val len = (end - start + 1).coerceAtLeast(1)
        val rel = (p - start).coerceAtLeast(0)
        val quarterIdxInJuz = kotlin.math.floor(rel * 8.0 / len).toInt().coerceIn(0, 7)
        val quarterInHizb = (quarterIdxInJuz % 4) + 1
        return quarterInHizb
    }

    // ======================= اتصال الشبكة =======================
    private fun isConnected(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
    private fun isOnWifi(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }
    private fun isOnMobileData(): Boolean {
        val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }
    private fun shouldAllowDownload(showToast: Boolean = true): Boolean {
        if (!isConnected()) {
            if (showToast) Toast.makeText(activity, "لا يوجد اتصال بالإنترنت.", Toast.LENGTH_LONG).show()
            return false
        }
        return when (getNetworkPref()) {
            NetworkPref.WIFI_ONLY -> {
                val ok = isOnWifi()
                if (!ok && showToast) Toast.makeText(activity, "التفضيل: واي-فاي فقط.", Toast.LENGTH_LONG).show()
                ok
            }
            NetworkPref.MOBILE_ONLY -> {
                val ok = isOnMobileData()
                if (!ok && showToast) Toast.makeText(activity, "التفضيل: البيانات فقط.", Toast.LENGTH_LONG).show()
                ok
            }
            NetworkPref.ANY -> true
        }
    }

    // ========= JSON ARRAYS / CACHE =========
    private val quranArr: JSONArray by lazy {
        val jsonStr = activity.assets.open("quran.json").bufferedReader().use { it.readText() }
        JSONArray(jsonStr)
    }
    private val surahsArr: JSONArray by lazy {
        val jsonStr = activity.assets.open("surahs.json").bufferedReader().use { it.readText() }
        JSONArray(jsonStr)
    }
    private val boundsCache = HashMap<Int, List<AyahBounds>>()
    private val boundsRoot: JSONObject by lazy {
        val jsonStr = activity.assets.open("pages/ayah_bounds_all.json").bufferedReader().use { it.readText() }
        JSONObject(jsonStr)
    }

    // عدد الآيات في كل سورة (1..114)
    private val AYAH_COUNTS = intArrayOf(
        7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,
        34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,
        12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,
        11,8,3,9,5,4,5,6,3,5,4,5,4,5,6
    )

    // ======================= بانر "الآن يُتلى" =======================
    fun showAyahBanner(surah: Int, ayah: Int) {
        val text = try { getAyahTextFromJson(surah, ayah) } catch (_: Throwable) { "—" }
        (activity as? QuranPageActivity)?.showOrUpdateAyahBanner(surah, ayah, text)
    }

    fun showOrUpdateAyahBanner(surah: Int, ayah: Int, text: String) {
        (activity as? QuranPageActivity)?.showOrUpdateAyahBanner(surah, ayah, text)
    }

    fun hideAyahBanner() {
        try {
            val out: Animation = AnimationUtils.loadAnimation(activity, R.anim.slide_out_top)
            (activity as? QuranPageActivity)?.ayahBanner?.startAnimation(out)
        } catch (_: Exception) {}
        (activity as? QuranPageActivity)?.hideAyahBanner(userClose = true)
    }

    // ======================= شريط خيارات الآية =======================
    fun showAyahOptionsBar(surah: Int, ayah: Int, ayahText: String) {
        activity.toolbar.show()
        activity.audioControls.show()
        activity.ayahPreview?.text = ayahText
        activity.ayahOptionsBar.show()
        activity.ayahOptionsBar.alpha = 1f
        activity.showBarsThenAutoHide(3000)
    }

    fun showToolbarAndHideAfterDelay() { activity.showBarsThenAutoHide(3500) }

    fun hideToolbarAndBottomBar() {
        activity.toolbar.hide()
        activity.audioControls.animate()
            .translationY(activity.audioControls.height.toFloat())
            .alpha(0f)
            .setDuration(180)
            .withEndAction { activity.audioControls.hide() }
            .start()
    }
    fun showToolbarAndBottomBar() {
        activity.toolbar.show()
        activity.audioControls.apply {
            show()
            alpha = 0f
            animate().translationY(0f).alpha(1f).setDuration(200).start()
        }
    }

    // ======================= نصوص وأسماء السور =======================
    fun getAyahTextFromJson(surah: Int, ayah: Int): String {
        for (i in 0 until quranArr.length()) {
            val sObj = quranArr.getJSONObject(i)
            if (sObj.getInt("id") == surah) {
                val verses = sObj.getJSONArray("verses")
                for (j in 0 until verses.length()) {
                    val v = verses.getJSONObject(j)
                    if (v.getInt("id") == ayah) return v.getString("text")
                }
            }
        }
        return "الآية غير موجودة"
    }

    fun getSurahNameByNumber(surahNumber: Int): String {
        surahNamesByNumber[surahNumber]?.let { return it }
        for (i in 0 until surahsArr.length()) {
            val o = surahsArr.getJSONObject(i)
            if (o.optInt("number", -1) == surahNumber) return o.optString("name")
        }
        return ""
    }

    // ======================= حدود الآيات على الصفحة =======================
    data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
    data class AyahBounds(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)

    fun loadAyahBoundsForPage(page: Int): List<AyahBounds> {
        boundsCache[page]?.let { return it }
        val arr = boundsRoot.optJSONArray(page.toString()) ?: return emptyList()
        val res = mutableListOf<AyahBounds>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val segsArr = o.optJSONArray("segs") ?: JSONArray()
            val segs = mutableListOf<Seg>()
            for (j in 0 until segsArr.length()) {
                val s = segsArr.getJSONObject(j)
                segs.add(Seg(
                    x = s.optInt("x"), y = s.optInt("y"),
                    w = s.optInt("w"), h = s.optInt("h")
                ))
            }
            res.add(
                AyahBounds(
                    sura_id = o.optInt("sura_id", o.optInt("surah", 0)),
                    aya_id  = o.optInt("aya_id",  o.optInt("ayah",  0)),
                    segs = segs
                )
            )
        }
        boundsCache[page] = res
        return res
    }

    // ======================= اختيار القارئ =======================
    data class QariMini(val id: String, val name: String)
    fun showQariPicker(onPicked: (QariMini) -> Unit) {
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_qari_picker, null)
        val rv = dialogView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.qariList)

        val items: List<QariItem> = buildQariListFromArrays() // الدالة عندك أسفل الملف

        lateinit var dialog: androidx.appcompat.app.AlertDialog

        val adapter = QariAdapter(items) { sel: QariItem ->
            activity.prefs.edit()
                .putString(QuranPageActivity.KEY_QARI_ID, sel.id)
                .putString("qari_base_url", sel.baseUrl)
                .apply()

            onPicked(QariMini(sel.id, sel.name))
            Toast.makeText(activity, "تم اختيار ${sel.name}", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        rv.adapter = adapter
        rv.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(activity)

        dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(activity)
            .setTitle(activity.getString(R.string.choose_qari_title))
            .setView(dialogView)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        dialog.show()
    }
// داخل class QuranSupportHelper { ... }

    private fun buildQariListFromArrays(): List<QariItem> {
        return provider.getQaris().map { qari ->
            val base = qari.url.trim()
            QariItem(
                name    = qari.name,
                id      = qari.id,
                quality = if (provider.isWholeSurahQari(qari.id)) {
                    "MP3 • سورة كاملة"
                } else {
                    "MP3 • آية آية"
                },
                baseUrl = if (base.endsWith("/")) base else "$base/"
            )
        }
    }

    // ======================= مشاركة آية =======================
    fun shareCurrentAyah(surah: Int, ayah: Int) {
        val text = "سورة ${getSurahNameByNumber(surah)} - آية $ayah\n\n${getAyahTextFromJson(surah, ayah)}"
        activity.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, text)
        }, "مشاركة"))
    }

    // ======================= التفسير =======================
    private val tafsirList = listOf(
        "تفسير ابن كثير" to "ar-tafsir-ibn-kathir.json",
        "تفسير السعدي"   to "ar-tafsir-as-saadi.json",
        "تفسير القرطبي"  to "ar-tafsir-al-qurtubi.json"
    )
    private var selectedTafsirId = 0
    private lateinit var tafsirAlertDialog: AlertDialog

    fun showTafsirPickerDialog() {
        val names = tafsirList.map { it.first }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle("اختر نوع التفسير")
            .setItems(names) { _, which -> selectedTafsirId = which }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun showTafsirDownloadDialog() {
        val names = tafsirList.map { it.first }.toTypedArray()
        val files = tafsirList.map { it.second }
        val links = mapOf(
            "ar-tafsir-ibn-kathir.json" to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-ibn-kathir.json",
            "ar-tafsir-as-saadi.json"   to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-as-saadi.json",
            "ar-tafsir-al-qurtubi.json" to "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/ar-tafsir-al-qurtubi.json"
        )
        AlertDialog.Builder(activity)
            .setTitle("تحميل تفسير")
            .setItems(names) { _, which ->
                val file = files[which]
                val url = links[file] ?: return@setItems
                val pd = ProgressDialog(activity).apply { setMessage("جاري تحميل: ${names[which]}"); setCancelable(false); show() }
                downloadTafsirIfNeeded(activity, file, url) { ok, _ ->
                    activity.runOnUiThread {
                        pd.dismiss()
                        Toast.makeText(activity, if (ok) "تم التحميل!" else "فشل التحميل!", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    fun openTafsir(surah: Int, ayah: Int) {
        val tafsirFile = tafsirList[selectedTafsirId].second
        val url = "https://cdn.jsdelivr.net/gh/assadig3/quran-tafsir@main/$tafsirFile"
        downloadTafsirIfNeeded(activity, tafsirFile, url) { success, _ ->
            val text = if (success) TafsirUtils.getAyahTafsir(activity, surah, ayah, tafsirFile)
            else "فشل تحميل التفسير من الإنترنت."
            activity.runOnUiThread {
                showTafsirDialog(
                    "سورة ${getSurahNameByNumber(surah)}  -  آية $ayah",
                    getAyahTextFromJson(surah, ayah),
                    text ?: "لم يتم العثور على التفسير."
                )
            }
        }
    }

    private fun showTafsirDialog(title: String, ayahText: String, tafsirText: String) {
        if (::tafsirAlertDialog.isInitialized && tafsirAlertDialog.isShowing) tafsirAlertDialog.dismiss()
        val v = activity.layoutInflater.inflate(R.layout.dialog_tafsir_ayah, null)
        v.findViewById<TextView>(R.id.tafsirAyahTitle).setSafeText(title)
        v.findViewById<TextView>(R.id.tafsirAyahText).setSafeText(ayahText)
        v.findViewById<TextView>(R.id.tafsirText).setSafeText(tafsirText)
        v.findViewById<View>(R.id.btnCloseTafsir).setOnClickListener { tafsirAlertDialog.dismiss() }
        v.findViewById<View>(R.id.btnShareTafsir).setOnClickListener {
            val share = "$title\n\n$ayahText\n\nالتفسير:\n$tafsirText"
            activity.startActivity(Intent.createChooser(Intent().apply {
                action = Intent.ACTION_SEND; type = "text/plain"; putExtra(Intent.EXTRA_TEXT, share)
            }, "مشاركة التفسير"))
        }
        tafsirAlertDialog = AlertDialog.Builder(activity).create().apply { setView(v); show() }
    }
    fun getPageAyahsForPlayback(
        page: Int,
        onReady: (List<AyahBounds>) -> Unit
    ) {
        // 1) المحاولة المباشرة من الملف الكبير داخل الذاكرة
        val now = loadAyahBoundsForPage(page)
        if (now.isNotEmpty()) { onReady(now); return }

        // 2) مصدر احتياطي سريع من الخريطة المبسّطة (بدون Segs)
        val fallback = pageAyahsFromBounds[page]?.map {
            AyahBounds(
                sura_id = it.surah,   // ✅ وسوم باراميترات لتفادي أي التباس
                aya_id  = it.ayah,
                segs    = emptyList()
            )
        } ?: emptyList()
        if (fallback.isNotEmpty()) { onReady(fallback); return }

        // 3) إعادة محاولة قصيرة بعد استقرار الـ UI / رقم الصفحة
        activity.window?.decorView?.postDelayed({
            val retry1 = loadAyahBoundsForPage(page)
            if (retry1.isNotEmpty()) { onReady(retry1); return@postDelayed }

            // (اختياري) إعادة محاولة ثانية أطول قليلًا لمنع العشوائية في الأجهزة البطيئة
            activity.window?.decorView?.postDelayed({
                onReady(loadAyahBoundsForPage(page))
            }, 120)
        }, 80)
    }

    // ======================= تنزيل التلاوات =======================
    private enum class DownloadScope { PAGE, SURAH, JUZ, QURAN }

    private val JUZ_START_PAGES = intArrayOf(
        1, 22, 42, 62, 82, 102, 121, 141, 162, 182,
        201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
        402, 422, 442, 462, 482, 502, 522, 542, 562, 582
    )

    private data class DLItem(val url: String, val out: File, val surah: Int, val ayah: Int)

    private fun currentDownloadSettingsSummary(): String {
        val network = when (getNetworkPref()) {
            NetworkPref.WIFI_ONLY -> "واي-فاي فقط"
            NetworkPref.MOBILE_ONLY -> "بيانات الهاتف فقط"
            NetworkPref.ANY -> "أي شبكة"
        }
        val storage = when (netPrefs.getString("recitation_storage", "internal")) {
            "external" -> {
                if (netPrefs.getString("pref_tree_uri", null).isNullOrBlank()) {
                    "خارجي (اختر مجلدًا أولًا)"
                } else {
                    "مجلد خارجي"
                }
            }
            else -> "داخل التطبيق"
        }
        return "الشبكة: $network\nمكان الحفظ: $storage"
    }

    fun showDownloadScopeDialog(currentPage: Int, currentSurah: Int, currentQariId: String) {
        val choices = arrayOf("الصفحة الحالية", "السورة الحالية", "الجزء الحالي", "المصحف كامل")
        var selected = 0

        val density = activity.resources.displayMetrics.density
        val sidePadding = (20 * density).toInt()
        val bottomPadding = (8 * density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(sidePadding, 0, sidePadding, bottomPadding)
        }
        content.addView(TextView(activity).apply {
            text = currentDownloadSettingsSummary()
            textSize = 14f
            alpha = 0.78f
            setPadding(0, 0, 0, bottomPadding)
        })
        content.addView(RadioGroup(activity).apply {
            orientation = RadioGroup.VERTICAL
            choices.forEachIndexed { index, label ->
                addView(RadioButton(activity).apply {
                    text = label
                    tag = index
                    isChecked = index == selected
                    setOnClickListener { selected = tag as Int }
                })
            }
        })

        AlertDialog.Builder(activity)
            .setTitle("تنزيل التلاوة")
            .setView(content)
            .setPositiveButton("تحميل") { d, _ ->
                val scope = when (selected) { 1 -> DownloadScope.SURAH; 2 -> DownloadScope.JUZ; 3 -> DownloadScope.QURAN; else -> DownloadScope.PAGE }
                val qariId = currentQariId.trim().lowercase()

                // ✅ إصلاح “السورة”: استنتاج رقم السورة من الصفحة الحالية أولًا
                val surahToUse = when (scope) {
                    DownloadScope.SURAH -> {
                        val sByPage = getSurahNumberByPage(currentPage)
                        if (sByPage in 1..114) sByPage else currentSurah.coerceIn(1, 114)
                    }
                    else -> currentSurah.coerceIn(1, 114)
                }

                val start = {
                    if (scope == DownloadScope.QURAN) {
                        Toast.makeText(activity, "سيتم تنزيل التلاوة للمصحف كاملة. قد يستغرق وقتًا طويلًا.", Toast.LENGTH_LONG).show()
                    }
                    startBulkDownload(scope, currentPage, surahToUse, qariId)
                }
                d.dismiss()
                if (!isNetworkPrefSet()) pickNetworkThen { start() } else start()
            }
            .setNeutralButton("إعدادات التنزيل") { d, _ ->
                d.dismiss()
                activity.openDownloadSettingsFromDialog()
            }
            .setNegativeButton("إلغاء", null)
            .show()
    }

    private fun pageRangeForCurrentJuz(pageNow: Int): IntRange {
        var start = 1; var end = 604
        for (i in 0 until 30) {
            val s = JUZ_START_PAGES[i]
            val e = if (i == 29) 604 else JUZ_START_PAGES[i + 1] - 1
            if (pageNow in s..e) { start = s; end = e; break }
        }
        return start..end
    }

    private fun buildItemsFor(scope: DownloadScope, pageNow: Int, surahNow: Int, qariIdRaw: String): List<DLItem> {
        val qariId = qariIdRaw.trim().lowercase()
        val qari = provider.getQariById(qariId) ?: return emptyList()
        val items = ArrayList<DLItem>(6400)

        fun addAyah(s: Int, a: Int) {
            val url = provider.getAyahUrl(qari, s, a)
            val out = qariFile(qariId, s, a)
            items.add(DLItem(url, out, s, a))
        }

        when (scope) {
            DownloadScope.PAGE -> {
                for (b in loadAyahBoundsForPage(pageNow)) addAyah(b.sura_id, b.aya_id)
            }
            DownloadScope.SURAH -> {
                val count = AYAH_COUNTS.getOrNull(surahNow - 1) ?: 0
                for (a in 1..count) addAyah(surahNow, a)
            }
            DownloadScope.JUZ -> {
                for (p in pageRangeForCurrentJuz(pageNow)) {
                    for (b in loadAyahBoundsForPage(p)) addAyah(b.sura_id, b.aya_id)
                }
            }
            DownloadScope.QURAN -> {
                for (s in 1..114) {
                    val c = AYAH_COUNTS.getOrNull(s - 1) ?: continue
                    for (a in 1..c) addAyah(s, a)
                }
            }
        }
        return items.distinctBy { it.out.absolutePath }
    }

    private fun startBulkDownload(scope: DownloadScope, pageNow: Int, surahNow: Int, qariId: String) {
        val qari = provider.getQariById(qariId) ?: run {
            Toast.makeText(activity, "تعذر تحديد القارئ.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!shouldAllowDownload(showToast = true)) return
        if (netPrefs.getString("recitation_storage", "internal") == "external" &&
            netPrefs.getString("pref_tree_uri", null).isNullOrBlank()
        ) {
            Toast.makeText(activity, "اختر مجلد التخزين الخارجي من الإعدادات أولًا.", Toast.LENGTH_LONG).show()
            return
        }

        val go = {
            val intent = Intent(activity, PagesDownloadService::class.java)
                .setAction(PagesDownloadService.ACTION_START)
                .putExtra(PagesDownloadService.EXTRA_SCOPE, scope.name)
                .putExtra(PagesDownloadService.EXTRA_PAGE, pageNow)
                .putExtra(PagesDownloadService.EXTRA_SURAH, surahNow) // <-- الآن مضمون صحيح
                .putExtra(PagesDownloadService.EXTRA_QARI, qariId)
                .putExtra(PagesDownloadService.EXTRA_PARALLELISM, 2)
                .putExtra(PagesDownloadService.EXTRA_TOTAL, 0)
            ContextCompat.startForegroundService(activity, intent)
        }

        val pref = getNetworkPref()
        if (isOnMobileData() && (pref == NetworkPref.ANY || pref == NetworkPref.MOBILE_ONLY)) {
            AlertDialog.Builder(activity)
                .setTitle("تنبيه")
                .setMessage("أنت تستخدم بيانات الهاتف. قد يستهلك التحميل حجمًا كبيرًا من الباقة.\nهل تريد المتابعة؟")
                .setPositiveButton("متابعة") { _, _ -> go() }
                .setNegativeButton("إلغاء", null)
                .show()
        } else go()
    }

    // تنزيل ملف واحد (واجهوي)
    fun downloadOneAsync(message: String, url: String, out: File) {
        if (!shouldAllowDownload(showToast = true)) return
        thread { downloadWithProgress(url, out) { _, _ -> } }
    }

    // تنزيل صامت. نمنع تنزيل الملف نفسه مرتين عند تكرار تجهيز الصفحة.
    private val silentDownloadsInFlight = ConcurrentHashMap.newKeySet<String>()

    fun downloadOneSilentInBackground(url: String, out: File) {
        if (!shouldAllowDownload(showToast = false)) return
        if (out.exists() && out.length() > 1024L) return

        val key = out.absolutePath
        if (!silentDownloadsInFlight.add(key)) return

        thread(name = "AyahAudioPrefetch") {
            try {
                downloadWithProgress(url, out) { _, _ -> }
            } catch (_: Exception) {
                // التنزيل المسبق اختياري؛ سيبقى التشغيل المباشر متاحًا.
            } finally {
                silentDownloadsInFlight.remove(key)
            }
        }
    }

    // ======================= تنزيل منخفض المستوى =======================
    private fun downloadWithProgress(
        url: String,
        out: File,
        allowRangeReset: Boolean = true,
        onProgress: (Int, Boolean) -> Unit
    ): Pair<Boolean, Long> {
        if (out.exists() && out.length() > 1024L) return true to out.length()
        if (url.isBlank()) return false to 0L

        out.parentFile?.mkdirs()
        val part = File(out.parentFile, "${out.name}.part")
        var conn: HttpURLConnection? = null

        return try {
            val already = part.takeIf { it.exists() }?.length() ?: 0L
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 10_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                useCaches = false
                setRequestProperty("User-Agent", "AlQuranApp/1.0 (Android)")
                setRequestProperty("Accept-Encoding", "identity")
                if (already > 0L) setRequestProperty("Range", "bytes=$already-")
            }
            conn = connection
            connection.connect()

            val code = connection.responseCode
            if (code == 416 &&
                already > 0L && allowRangeReset && part.delete()
            ) {
                return downloadWithProgress(url, out, allowRangeReset = false, onProgress = onProgress)
            }
            if (code !in 200..299) return false to 0L

            val append = already > 0L && code == HttpURLConnection.HTTP_PARTIAL
            val initial = if (append) already else 0L
            val responseBytes = connection.contentLengthLong
            val expectedTotal = if (responseBytes > 0L) initial + responseBytes else -1L
            var downloaded = initial
            var lastEmit = downloaded

            onProgress(
                if (expectedTotal > 0L) ((downloaded * 100L) / expectedTotal).toInt() else 0,
                expectedTotal > 0L
            )

            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buf = ByteArray(32 * 1024)
                    while (true) {
                        val read = input.read(buf)
                        if (read == -1) break
                        output.write(buf, 0, read)
                        downloaded += read

                        if (expectedTotal > 0L && downloaded - lastEmit >= 128 * 1024L) {
                            val pct = ((downloaded * 100L) / expectedTotal).toInt().coerceIn(0, 100)
                            onProgress(pct, true)
                            lastEmit = downloaded
                        }
                    }
                    output.flush()
                }
            }

            if (!part.exists() || part.length() <= 1024L ||
                (expectedTotal > 0L && part.length() < expectedTotal)
            ) {
                return false to (part.takeIf { it.exists() }?.length() ?: 0L)
            }

            if (out.exists() && !out.delete()) return false to part.length()
            val committed = part.renameTo(out) || runCatching {
                part.copyTo(out, overwrite = true)
                part.delete()
                true
            }.getOrDefault(false)

            if (!committed || !out.exists() || out.length() <= 1024L) {
                return false to (out.takeIf { it.exists() }?.length() ?: 0L)
            }

            onProgress(100, expectedTotal > 0L)
            true to out.length()
        } catch (_: Exception) {
            false to (part.takeIf { it.exists() }?.length() ?: 0L)
        } finally {
            conn?.disconnect()
        }
    }

    // ======================= مسارات الحفظ + واجهات للاستخدام دون اتصال =======================
    private fun qariDir(qariIdRaw: String): File {
        val safe = qariIdRaw.trim().lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
        val appStorage = activity.getExternalFilesDir(null) ?: activity.filesDir
        val dir = File(appStorage, "recitations/$safe")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun qariFile(qariIdRaw: String, surah: Int, ayah: Int): File {
        val qariId = qariIdRaw.trim().lowercase()
        val name = "%03d%03d.mp3".format(surah, ayah)
        return File(qariDir(qariId), name)
    }

    /** ✅ مستخدمة في QuranAudioHelper: التحقق من وجود ملف الآية محليًا */
    fun hasOfflineAyahFile(qariId: String, surah: Int, ayah: Int): Boolean =
        qariFile(qariId, surah, ayah).exists() && qariFile(qariId, surah, ayah).length() > 512

    /** ✅ مستخدمة في QuranAudioHelper: إرجاع ملف الآية المحلي */
    fun getOfflineFileForAyah(qariId: String, surah: Int, ayah: Int): File =
        qariFile(qariId, surah, ayah)
}
