// File: app/src/main/java/com/hag/al_quran/download/PagesDownloadService.kt
package com.hag.al_quran.download

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.audio.Qari
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.roundToLong

class PagesDownloadService : Service() {

    companion object {
        const val ACTION_START  = "com.hag.al_quran.PAGES_DL_START"
        const val ACTION_PAUSE  = "com.hag.al_quran.PAGES_DL_PAUSE"
        const val ACTION_RESUME = "com.hag.al_quran.PAGES_DL_RESUME"
        const val ACTION_CANCEL = "com.hag.al_quran.PAGES_DL_CANCEL"
        const val ACTION_FILES_CHANGED = "com.hag.al_quran.RECITATION_FILES_CHANGED"
        const val EXTRA_CHANGED_QARI = "extra_changed_qari"

        // ===== تخزين خارجي عبر SAF =====
        private const val KEY_STORAGE_MODE = "recitation_storage"   // "internal" | "external"
        private const val PREF_TREE_URI    = "pref_tree_uri"        // Uri لسجل المجلد الذي اختاره المستخدم

        // تفضيل نوع الشبكة: "WIFI_ONLY" | "MOBILE_ONLY" | "ANY"
        private const val PREF_NETWORK = "pref_network_type"

        const val EXTRA_SCOPE  = "extra_scope"   // "PAGE", "SURAH", "JUZ", "QURAN"
        const val EXTRA_PAGE   = "extra_page"
        const val EXTRA_SURAH  = "extra_surah"
        const val EXTRA_QARI   = "extra_qari"    // qari id string
        const val EXTRA_PARALLELISM = "extra_parallelism"
        const val EXTRA_TOTAL  = "extra_total"   // optional

        private const val CHANNEL_ID = "pages_download_channel"
        private const val NOTIF_ID   = 77221
    }

    private enum class DownloadScope { PAGE, SURAH, JUZ, QURAN }

    // ===== state =====
    private val isPaused = AtomicBoolean(false)
    private val isCancelled = AtomicBoolean(false)
    private val isRunning = AtomicBoolean(false)
    private val pauseLock = Object()

    private var parallelism = 2
    private var totalItems = 0

    // cache for bounds JSON
    private var boundsRoot: JSONObject? = null

    // فهرس واحد لمجلد SAF، لتجنب listFiles() مع كل آية.
    private val externalLock = Any()
    private var externalQariDirCache: DocumentFile? = null
    private var externalQariIdCache: String = ""
    private val externalFilesByName = HashMap<String, DocumentFile>()

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    // Shared prefs موحّد (يجب أن يطابق ما تستخدمه شاشة الإعدادات)
    private val settings by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }
    private val externalIndexPrefs by lazy {
        getSharedPreferences("external_audio_index_v1", Context.MODE_PRIVATE)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
        // إشعار مبدئي ليصبح Foreground
        startForeground(
            NOTIF_ID,
            buildNotification(paused = false, progress = 0, total = 1, etaText = "…")
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (!isRunning.compareAndSet(false, true)) return START_NOT_STICKY

                val scopeStr = intent.getStringExtra(EXTRA_SCOPE) ?: "PAGE"
                val scope = runCatching { DownloadScope.valueOf(scopeStr) }
                    .getOrElse { DownloadScope.PAGE }
                val page = intent.getIntExtra(EXTRA_PAGE, 1)
                val surah = intent.getIntExtra(EXTRA_SURAH, 1)
                val qariId = intent.getStringExtra(EXTRA_QARI) ?: "fares"
                parallelism = intent.getIntExtra(EXTRA_PARALLELISM, 2).coerceIn(1, 3)

                isPaused.set(false)
                isCancelled.set(false)

                startDownload(scope, page, surah, qariId)
            }
            ACTION_PAUSE -> {
                isPaused.set(true)
                updateNotification(paused = true)
            }
            ACTION_RESUME -> {
                isPaused.set(false)
                synchronized(pauseLock) { pauseLock.notifyAll() }
                updateNotification(paused = false)
            }
            ACTION_CANCEL -> {
                isCancelled.set(true)
                synchronized(pauseLock) { pauseLock.notifyAll() }
                // سيتم إيقاف الخدمة بعد أن تلاحظ الحلقة حالة الإلغاء
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        runCatching { httpClient.dispatcher.cancelAll() }
        super.onDestroy()
        runCatching { NotificationManagerCompat.from(this).cancel(NOTIF_ID) }
    }

    // ====== التحميل الرئيسي (تلاوات) ======
    private fun startDownload(scope: DownloadScope, pageNow: Int, surahNow: Int, qariId: String) {
        thread(name = "RecitationsDownloadThread") {
            try {
            // 0) فحص الاتصال العام أولاً
            if (!isConnected()) {
                updateNotification(paused = true, progress = 0, total = 1, etaText = "لا يوجد اتصال بالإنترنت")
                SystemClock.sleep(1200)
                stopSelf()
                return@thread
            }

            // 1) احترام تفضيل نوع الشبكة من نفس SharedPreferences
            if (!canDownloadOnCurrentNetwork()) {
                val pref = settings.getString(PREF_NETWORK, "WIFI_ONLY") ?: "WIFI_ONLY"
                val reason = when (pref) {
                    "WIFI_ONLY"   -> "التفضيل: واي-فاي فقط"
                    "MOBILE_ONLY" -> "التفضيل: البيانات فقط"
                    else          -> "الاتصال غير متاح"
                }
                updateNotification(paused = true, progress = 0, total = 1, etaText = reason)
                SystemClock.sleep(1200)
                stopSelf()
                return@thread
            }

            // 2) مزوّد وروابط القارئ
            val provider = MadaniPageProvider(applicationContext)
            val qari: Qari? = provider.getQariById(qariId)
            if (qari == null) {
                updateNotification(paused = true, progress = 0, total = 1, etaText = "القارئ غير معروف")
                SystemClock.sleep(800)
                stopSelf()
                return@thread
            }

            if (isExternalStorageSelected() && !prepareExternalTarget(qari.id)) {
                updateNotification(
                    paused = true,
                    progress = 0,
                    total = 1,
                    etaText = "تعذر الوصول إلى مجلد الحفظ الخارجي"
                )
                SystemClock.sleep(1200)
                stopSelf()
                return@thread
            }

            // 3) بناء قائمة العناصر
            val items = buildUrlsForScope(provider, qari, scope, pageNow, surahNow)
            if (items.isEmpty()) {
                updateNotification(paused = true, progress = 0, total = 1, etaText = "لا ملفات")
                SystemClock.sleep(800)
                stopSelf()
                return@thread
            }

            totalItems = items.size
            updateNotification(paused = false, progress = 0, total = totalItems, etaText = "—")

            // 4) تنزيل متوازٍ بعدد محدود حتى لا يضغط على الخادم أو الهاتف.
            val startMs = SystemClock.elapsedRealtime()
            var done = 0
            var completed = 0
            val executor = Executors.newFixedThreadPool(parallelism)
            val completion = ExecutorCompletionService<Boolean>(executor)

            items.forEach { (url, out) ->
                completion.submit(Callable {
                    var success = false
                    if (waitUntilResumed()) {
                        for (attempt in 0 until 3) {
                            if (!waitUntilResumed()) break
                            success = downloadSingle(url, out)
                            if (success) break
                            if (attempt < 2) SystemClock.sleep((attempt + 1) * 500L)
                        }
                    }
                    success
                })
            }

            try {
                while (completed < totalItems && !isCancelled.get()) {
                    val success = runCatching { completion.take().get() }.getOrDefault(false)
                    completed++
                    if (success) done++

                    val elapsedSec = max(
                        1L,
                        ((SystemClock.elapsedRealtime() - startMs) / 1000f).roundToLong()
                    )
                    val rate = completed.toFloat() / elapsedSec.toFloat()
                    val remaining = (totalItems - completed).coerceAtLeast(0)
                    val etaSec = if (rate > 0f) (remaining / rate).roundToLong() else Long.MAX_VALUE
                    val etaText = if (etaSec == Long.MAX_VALUE) "…" else formatEta(etaSec)

                    updateNotification(
                        paused = isPaused.get(),
                        progress = completed,
                        total = totalItems,
                        etaText = etaText
                    )
                }
            } finally {
                executor.shutdownNow()
            }

            // 5) إنهاء
            val failed = (totalItems - done).coerceAtLeast(0)
            val finalText = when {
                isCancelled.get() -> "أُلغي"
                failed == 0 -> "تم"
                else -> "تعذر تنزيل $failed ملف"
            }
            updateNotification(
                paused = false,
                progress = completed,
                total = totalItems,
                etaText = finalText
            )
            if (done > 0) {
                sendBroadcast(
                    Intent(ACTION_FILES_CHANGED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_CHANGED_QARI, qariId)
                )
            }
            SystemClock.sleep(1200)
            stopSelf()
            } finally {
                isRunning.set(false)
            }
        }
    }

    private fun waitUntilResumed(): Boolean {
        synchronized(pauseLock) {
            while (isPaused.get() && !isCancelled.get() && !Thread.currentThread().isInterrupted) {
                try {
                    pauseLock.wait(500)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return !isCancelled.get() && !Thread.currentThread().isInterrupted
    }

    // ======= بناء روابط التلاوات =======
    private fun buildUrlsForScope(
        provider: MadaniPageProvider,
        qari: Qari,
        scope: DownloadScope,
        pageNow: Int,
        surahNow: Int
    ): List<Pair<String, File>> {
        val qariId = qari.id.trim().lowercase()
        val pairs = ArrayList<Pair<String, File>>(4096)
        val wholeSurahSource = provider.isWholeSurahQari(qari)

        fun addAyah(s: Int, a: Int) {
            val url = provider.getAyahUrl(qari.id, s, a) ?: return
            val out = qariFile(qariId, s, a)
            pairs.add(url to out)
        }

        fun addSurah(s: Int) {
            val url = provider.getSurahUrl(qari.id, s) ?: return
            // الآية 000 محجوزة داخل التطبيق لتسجيل السورة الكامل.
            pairs.add(url to qariFile(qariId, s, 0))
        }

        when (scope) {
            DownloadScope.PAGE -> {
                val bounds = loadBoundsForPage(pageNow)
                if (wholeSurahSource) {
                    bounds.map { it.sura_id }.distinct().forEach(::addSurah)
                } else {
                    for (b in bounds) addAyah(b.sura_id, b.aya_id)
                }
            }
            DownloadScope.SURAH -> {
                if (wholeSurahSource) {
                    addSurah(surahNow)
                } else {
                    val counts = AYAH_COUNTS.getOrNull(surahNow - 1) ?: 0
                    for (a in 1..counts) addAyah(surahNow, a)
                }
            }
            DownloadScope.JUZ -> {
                val range = pageRangeForCurrentJuz(pageNow)
                if (wholeSurahSource) {
                    range.flatMap { p -> loadBoundsForPage(p).map { it.sura_id } }
                        .distinct()
                        .forEach(::addSurah)
                } else {
                    for (p in range) {
                        val bounds = loadBoundsForPage(p)
                        for (b in bounds) addAyah(b.sura_id, b.aya_id)
                    }
                }
            }
            DownloadScope.QURAN -> {
                if (wholeSurahSource) {
                    for (s in 1..114) addSurah(s)
                } else {
                    for (s in 1..114) {
                        val c = AYAH_COUNTS.getOrNull(s - 1) ?: continue
                        for (a in 1..c) addAyah(s, a)
                    }
                }
            }
        }

        return pairs
            .filter { (_, file) ->
                if (isExternalStorageSelected()) {
                    // إن كان الخارجي ناقصًا نُبقي العنصر؛ قد يكون الداخلي جاهزًا للنسخ فقط.
                    !isExternalFileAvailable(file.name)
                } else {
                    !file.exists() || file.length() <= 1024L
                }
            }
            .distinctBy { it.second.absolutePath }
    }

    // ====== bounds (من الأصول) ======
    private data class Seg(val x: Int, val y: Int, val w: Int, val h: Int)
    private data class AyahBounds(val sura_id: Int, val aya_id: Int, val segs: List<Seg>)

    private fun loadBoundsForPage(page: Int): List<AyahBounds> {
        return try {
            if (boundsRoot == null) {
                val jsonStr = applicationContext.assets
                    .open("pages/ayah_bounds_all.json")
                    .bufferedReader().use { it.readText() }
                boundsRoot = JSONObject(jsonStr)
            }
            val arr = boundsRoot?.optJSONArray(page.toString()) ?: return emptyList()
            val res = ArrayList<AyahBounds>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val segsArr = o.getJSONArray("segs")
                val segs = ArrayList<Seg>(segsArr.length())
                for (j in 0 until segsArr.length()) {
                    val s = segsArr.getJSONObject(j)
                    segs.add(Seg(s.getInt("x"), s.getInt("y"), s.getInt("w"), s.getInt("h")))
                }
                res.add(AyahBounds(o.getInt("sura_id"), o.getInt("aya_id"), segs))
            }
            res
        } catch (e: Exception) {
            android.util.Log.e("PagesDownloadSvc", "Failed to load bounds", e)
            emptyList()
        }
    }

    // ======= تنزيل ملف واحد (آمن) =======
    private fun downloadSingle(urlStr: String, outFile: File): Boolean {
        for (candidate in downloadCandidates(urlStr)) {
            if (downloadFromUrl(candidate, outFile)) return true
        }
        return false
    }

    /**
     * لا نعتمد على مضيف واحد؛ بعض الشبكات تحجب EveryAyah أو تقطع اتصالاته.
     * المرايا تستخدم نفس أسماء ملفات SSSAAA، لذلك يبقى الاستكمال صالحًا.
     */
    private fun downloadCandidates(primaryUrl: String): List<String> {
        if (primaryUrl.isBlank()) return emptyList()
        val candidates = linkedSetOf(primaryUrl)
        val marker = "/data/"
        if (primaryUrl.contains("everyayah.com") && primaryUrl.contains(marker)) {
            val relative = primaryUrl.substringAfter(marker)
            val folder = relative.substringBefore('/')
            val fileName = relative.substringAfterLast('/')

            // نسخة متحققة ومستقلة للسديس، ثم المرايا العامة لبقية القراء.
            if (folder == "Abdurrahmaan_As-Sudais_64kbps") {
                candidates += "https://huggingface.co/datasets/maqra-project/abdurrahman-as-sudais-64kbps/resolve/main/$fileName"
            }
            candidates += "https://audio.qurankareem.co/$relative"
            candidates += "https://streaming.quranonline.net/quran/$relative"
            candidates += "https://www.everyayah.com/data/$relative"
        }
        return candidates.toList()
    }

    private fun downloadFromUrl(urlStr: String, outFile: File): Boolean {
        if (urlStr.isBlank()) return false
        if (isExternalStorageSelected() && isExternalFileAvailable(outFile.name)) return true

        if (outFile.exists() && outFile.length() > 1024L) {
            return copyToExternalAndMaybeDeleteSrc(outFile)
        }

        outFile.parentFile?.mkdirs()
        val part = File(outFile.parentFile, outFile.name + ".part")

        return try {
            val already = part.takeIf { it.exists() }?.length() ?: 0L
            val request = Request.Builder()
                .url(urlStr)
                .header("User-Agent", "Mozilla/5.0 (Android) QuranKarim/1.0")
                .header("Accept-Encoding", "identity")
                .header("Cache-Control", "no-cache")
                .apply { if (already > 0L) header("Range", "bytes=$already-") }
                .build()

            httpClient.newCall(request).execute().use { response ->
                if (response.code == 416 && already > 0L) {
                    runCatching { part.delete() }
                    return downloadFromUrl(urlStr, outFile)
                }
                if (!response.isSuccessful) return false
                val contentType = response.header("Content-Type").orEmpty().lowercase()
                if (contentType.startsWith("text/") || contentType.contains("html")) return false

                val append = already > 0L && response.code == 206
                val initial = if (append) already else 0L
                val body = response.body ?: return false
                val responseBytes = body.contentLength()
                val expectedTotal = if (responseBytes > 0L) initial + responseBytes else -1L

                body.byteStream().use { input ->
                    FileOutputStream(part, append).use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (!waitUntilResumed()) return false
                            val read = input.read(buf)
                            if (read == -1) break
                            output.write(buf, 0, read)
                        }
                        output.flush()
                    }
                }

                if (!part.exists() || part.length() <= 1024L ||
                    (expectedTotal > 0L && part.length() < expectedTotal)
                ) return false
            }

            if (outFile.exists() && !outFile.delete()) return false
            val committed = part.renameTo(outFile) || runCatching {
                part.copyTo(outFile, overwrite = true)
                part.delete()
                true
            }.getOrDefault(false)

            if (!committed || !outFile.exists() || outFile.length() <= 1024L) return false

            copyToExternalAndMaybeDeleteSrc(outFile)
        } catch (t: Throwable) {
            android.util.Log.e("PagesDownloadSvc", "downloadSingle failed: $urlStr", t)
            false
        }
    }

    // ====== إشعارات ======
    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name) + " • تنزيل التلاوات",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "تقدّم تنزيل تلاوات الآيات"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun canPostNotifications(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    @SuppressLint("MissingPermission")
    private fun updateNotification(
        paused: Boolean,
        progress: Int? = null,
        total: Int = totalItems,
        etaText: String? = null
    ) {
        if (!canPostNotifications()) return
        try {
            NotificationManagerCompat.from(this)
                .notify(NOTIF_ID, buildNotification(paused, progress, total, etaText))
        } catch (_: SecurityException) { /* ignore */ }
    }

    private fun buildNotification(
        paused: Boolean,
        progress: Int? = null,
        total: Int = totalItems,
        etaText: String? = null
    ): Notification {
        val title = if (paused) "التنزيل متوقف مؤقتًا" else "تنزيل تلاوة المصحف"
        val p = (progress ?: 0).coerceIn(0, max(1, total))
        val text = buildString {
            append("الملفات: ")
            append("$p / $total")
            if (!etaText.isNullOrBlank()) append(" • الوقت المتبقي: $etaText")
        }

        val pauseIntent  = Intent(this, PagesDownloadService::class.java).setAction(ACTION_PAUSE)
        val resumeIntent = Intent(this, PagesDownloadService::class.java).setAction(ACTION_RESUME)
        val cancelIntent = Intent(this, PagesDownloadService::class.java).setAction(ACTION_CANCEL)

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0

        val piPause  = PendingIntent.getService(this, 501, pauseIntent, flags)
        val piResume = PendingIntent.getService(this, 502, resumeIntent, flags)
        val piCancel = PendingIntent.getService(this, 503, cancelIntent, flags)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(!isCancelled.get())
            .setPriority(NotificationCompat.PRIORITY_LOW)

        progress?.let {
            builder.setProgress(total.coerceAtLeast(1), it.coerceAtMost(total.coerceAtLeast(1)), false)
        } ?: builder.setProgress(0, 0, true)

        if (paused) builder.addAction(0, "استئناف", piResume)
        else builder.addAction(0, "إيقاف مؤقت", piPause)
        builder.addAction(0, "إلغاء", piCancel)

        return builder.build()
    }

    // ====== أدوات مساعدة ======
    private fun formatEta(sec: Long): String {
        val s = max(0, sec)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format("%d:%02d:%02d", h, m, ss) else String.format("%02d:%02d", m, ss)
    }

    private fun qariDir(qariIdRaw: String): File {
        val safe = qariIdRaw.trim().lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
        val appStorage = getExternalFilesDir(null) ?: filesDir
        val dir = File(appStorage, "recitations/$safe")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun qariFile(qariIdRaw: String, surah: Int, ayah: Int): File {
        val qariId = qariIdRaw.trim().lowercase()
        val name = "%03d%03d.mp3".format(surah, ayah)
        return File(qariDir(qariId), name)
    }

    // ===== الشبكة =====
    private fun isConnected(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun canDownloadOnCurrentNetwork(): Boolean {
        val pref = settings.getString(PREF_NETWORK, "WIFI_ONLY") ?: "WIFI_ONLY"
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false

        val onWifi   = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val onMobile = caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)

        return when (pref) {
            "WIFI_ONLY"   -> onWifi
            "MOBILE_ONLY" -> onMobile
            else          -> caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    // ========= بيانات ثابتة =========
    private val JUZ_START_PAGES = intArrayOf(
        1, 22, 42, 62, 82, 102, 121, 141, 162, 182,
        201, 222, 242, 262, 282, 302, 322, 342, 362, 382,
        402, 422, 442, 462, 482, 502, 522, 542, 562, 582
    )

    private fun pageRangeForCurrentJuz(pageNow: Int): IntRange {
        var start = 1
        var end = 604
        for (i in 0 until 30) {
            val s = JUZ_START_PAGES[i]
            val e = if (i == 29) 604 else JUZ_START_PAGES[i + 1] - 1
            if (pageNow in s..e) { start = s; end = e; break }
        }
        return start..end
    }

    private fun isExternalStorageSelected(): Boolean {
        val mode = settings.getString(KEY_STORAGE_MODE, "internal") ?: "internal"
        val tree = settings.getString(PREF_TREE_URI, null)
        return mode == "external" && !tree.isNullOrEmpty()
    }

    private fun externalRootDoc(): DocumentFile? {
        val uriStr = settings.getString(PREF_TREE_URI, null) ?: return null
        return try { DocumentFile.fromTreeUri(this, android.net.Uri.parse(uriStr)) }
        catch (_: Throwable) { null }
    }

    private fun ensureExternalRecitationsDir(): DocumentFile? {
        val root = externalRootDoc() ?: return null
        root.listFiles().firstOrNull { it.isDirectory && it.name == "recitations" }?.let { return it }
        return if (root.canWrite()) root.createDirectory("recitations") else null
    }

    private fun ensureExternalQariDir(qariId: String): DocumentFile? {
        val parent = ensureExternalRecitationsDir() ?: return null
        val safe = qariId.trim().lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
        parent.listFiles().firstOrNull { it.isDirectory && it.name == safe }?.let { return it }
        return if (parent.canWrite()) parent.createDirectory(safe) else null
    }

    private fun prepareExternalTarget(qariId: String): Boolean = synchronized(externalLock) {
        val qariDir = ensureExternalQariDir(qariId) ?: return@synchronized false
        if (!qariDir.canWrite()) return@synchronized false

        externalQariDirCache = qariDir
        externalQariIdCache = qariId.trim().lowercase()
        externalFilesByName.clear()
        if (externalIndexPrefs.getBoolean(externalIndexReadyKey(qariId), false)) {
            return@synchronized true
        }
        val indexEditor = externalIndexPrefs.edit()
        runCatching {
            qariDir.listFiles().forEach { doc ->
                doc.name?.let { name ->
                    externalFilesByName[name] = doc
                    if (doc.isFile && name.endsWith(".mp3") && doc.length() > 1024L) {
                        indexEditor.putString(externalIndexKey(qariId, name), doc.uri.toString())
                    }
                }
            }
            indexEditor.putBoolean(externalIndexReadyKey(qariId), true)
            indexEditor.apply()
        }.getOrElse { return@synchronized false }
        true
    }

    private fun isExternalFileAvailable(name: String): Boolean = synchronized(externalLock) {
        val doc = externalFilesByName[name]
            ?: indexedExternalDocument(externalQariIdCache, name)
            ?: return@synchronized false
        val valid = runCatching { doc.isFile && doc.length() > 1024L }.getOrDefault(false)
        if (valid) {
            externalFilesByName[name] = doc
        } else {
            externalFilesByName.remove(name)
            externalIndexPrefs.edit()
                .remove(externalIndexKey(externalQariIdCache, name))
                .apply()
        }
        valid
    }

    private fun indexedExternalDocument(qariId: String, name: String): DocumentFile? {
        if (qariId.isBlank()) return null
        val saved = externalIndexPrefs.getString(externalIndexKey(qariId, name), null) ?: return null
        return runCatching {
            DocumentFile.fromSingleUri(this, android.net.Uri.parse(saved))
        }.getOrNull()
    }

    /** ينسخ الملف الذي تم تنزيله إلى المسار الخارجي (SAF) ويستبدله إن وُجد */
    private fun copyToExternalAndMaybeDeleteSrc(outFile: java.io.File): Boolean {
        if (!isExternalStorageSelected()) return outFile.exists() && outFile.length() > 1024L
        if (!outFile.exists() || outFile.length() <= 1024L) return false

        val qariId = outFile.parentFile?.name ?: return false
        val name   = outFile.name
        if (!name.endsWith(".mp3") || name.length != 10) return false // "NNNMMM.mp3"

        synchronized(externalLock) {
            val qariDir = externalQariDirCache ?: ensureExternalQariDir(qariId) ?: return false
            externalQariDirCache = qariDir

            val existing = externalFilesByName[name] ?: indexedExternalDocument(qariId, name)
            if (existing != null && runCatching { existing.length() > 1024L }.getOrDefault(false)) {
                externalFilesByName[name] = existing
                rememberExternalFile(qariId, name, existing.uri)
                runCatching { outFile.delete() }
                return true
            }
            existing?.let { stale -> runCatching { stale.delete() } }

            val tempName = "$name.part"
            externalFilesByName.remove(tempName)?.let { stale -> runCatching { stale.delete() } }
            val temp = qariDir.createFile("application/octet-stream", tempName) ?: return false

            return try {
                val stream = contentResolver.openOutputStream(temp.uri, "w") ?: return false
                stream.use { output ->
                    outFile.inputStream().use { input -> input.copyTo(output, 32 * 1024) }
                    output.flush()
                }

                if (temp.length() != outFile.length() || temp.length() <= 1024L) {
                    runCatching { temp.delete() }
                    return false
                }

                var finalDoc = temp
                if (!temp.renameTo(name)) {
                    // بعض مزوّدي SAF وبطاقات SD لا يدعمون إعادة تسمية الملف.
                    val direct = qariDir.createFile("application/octet-stream", name) ?: run {
                        runCatching { temp.delete() }
                        return false
                    }
                    val directOut = contentResolver.openOutputStream(direct.uri, "w") ?: run {
                        runCatching { direct.delete() }
                        runCatching { temp.delete() }
                        return false
                    }
                    directOut.use { output ->
                        val directIn = contentResolver.openInputStream(temp.uri) ?: return false
                        directIn.use { input -> input.copyTo(output, 64 * 1024) }
                        output.flush()
                    }
                    if (direct.length() != outFile.length() || direct.length() <= 1024L) {
                        runCatching { direct.delete() }
                        runCatching { temp.delete() }
                        return false
                    }
                    runCatching { temp.delete() }
                    finalDoc = direct
                }

                externalFilesByName[name] = finalDoc
                externalFilesByName.remove(tempName)
                rememberExternalFile(qariId, name, finalDoc.uri)
                runCatching { outFile.delete() }
                true
            } catch (_: Throwable) {
                runCatching { temp.delete() }
                false
            }
        }
    }

    private fun externalIndexKey(qariIdRaw: String, fileName: String): String {
        val tree = settings.getString(PREF_TREE_URI, "").orEmpty()
        val safe = qariIdRaw.trim().lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
        return "${tree.hashCode()}|$safe|$fileName"
    }

    private fun externalIndexReadyKey(qariIdRaw: String): String {
        val tree = settings.getString(PREF_TREE_URI, "").orEmpty()
        val safe = qariIdRaw.trim().lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")
        return "ready|${tree.hashCode()}|$safe"
    }

    private fun rememberExternalFile(qariId: String, fileName: String, uri: android.net.Uri) {
        externalIndexPrefs.edit()
            .putString(externalIndexKey(qariId, fileName), uri.toString())
            .apply()
    }

    private val AYAH_COUNTS = intArrayOf(
        7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,
        34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,
        12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,
        11,8,3,9,5,4,5,6,3,5,4,5,4,5,6
    )
}
