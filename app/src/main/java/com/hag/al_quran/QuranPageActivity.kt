// File: app/src/main/java/com/hag/al_quran/QuranPageActivity.kt
package com.hag.al_quran

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.*
import android.text.TextUtils
import android.view.*
import android.view.animation.AnimationUtils
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.GestureDetectorCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.helpers.QuranAudioHelper
import com.hag.al_quran.helpers.QuranSupportHelper
import com.hag.al_quran.search.AyahLocator
import com.hag.al_quran.tafsir.TafsirManager
import com.hag.al_quran.utils.*
import java.util.concurrent.ExecutorService
import kotlin.math.max
import kotlin.math.roundToLong
import android.widget.ArrayAdapter
import androidx.lifecycle.lifecycleScope
import com.hag.al_quran.ui.PageImageLoader
import com.hag.al_quran.ui.ThemeManager
import com.hag.al_quran.download.PagesDownloadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.appcompat.widget.PopupMenu as AppCompatPopupMenu

class QuranPageActivity : BaseActivity() {

    companion object {
        const val EXTRA_TARGET_SURAH = "EXTRA_TARGET_SURAH"
        const val EXTRA_TARGET_AYAH  = "EXTRA_TARGET_AYAH"
        const val EXTRA_TARGET_PAGE  = "EXTRA_TARGET_PAGE"
        const val EXTRA_QUERY        = "EXTRA_QUERY"

        private const val PREFS_NAME = "quran_prefs"
        // مفاتيح جديدة حتى يظهر الشريط الجديد افتراضيًا ولا يرث إخفاء البانر القديم
        private const val KEY_SUPPRESS_AYAH_BANNER = "toolbar_recitation_strip_suppressed"
        private const val KEY_BANNER_TOGGLE_STATE = "toolbar_recitation_strip_enabled"
        private const val KEY_PAGES_CACHED = "pages_cached"

        private const val TOTAL_PAGES = 604

        const val CHANNEL_ID = "quran_playback_channel"
        private const val NOTIF_ID   = 99111

        private const val ACT_PLAY   = "com.hag.al_quran.NOTIF_PLAY"
        private const val ACT_PAUSE  = "com.hag.al_quran.NOTIF_PAUSE"
        private const val ACT_STOP   = "com.hag.al_quran.NOTIF_STOP"

        private var ayahBarClosedByUser = false

        private const val REQ_POST_NOTIFS = 8807

        // مفاتيح الإعدادات
        const val KEY_QARI_ID = "pref_qari_id"
        const val PREF_REPEAT_AYAH = "pref_repeat_ayah_count"
        const val PREF_REPEAT_PAGE = "pref_repeat_page_count"

        const val AUTO_HIDE_DELAY_MS = 4000
    }

    private var statusBarScrim: View? = null

    // ===================== Repeat Mode =====================
    private enum class RepeatMode { OFF, PAGE, AYAH }
    private val PREF_REPEAT_MODE = "pref_repeat_mode"
    private var repeatMode: RepeatMode = RepeatMode.OFF
    private fun loadRepeatMode(): RepeatMode =
        when (prefs.getInt(PREF_REPEAT_MODE, 0)) {
            1 -> RepeatMode.PAGE
            2 -> RepeatMode.AYAH
            else -> RepeatMode.OFF
        }
    private fun saveRepeatMode(mode: RepeatMode) {
        val v = when (mode) {
            RepeatMode.OFF  -> 0
            RepeatMode.PAGE -> 1
            RepeatMode.AYAH -> 2
        }
        prefs.edit().putInt(PREF_REPEAT_MODE, v).apply()
    }
    private fun updateRepeatIcon() {
        when (repeatMode) {
            RepeatMode.OFF -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.alpha = 0.55f
                btnRepeat.contentDescription = getString(R.string.repeat_off)
            }
            RepeatMode.PAGE -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat)
                btnRepeat.alpha = 1f
                btnRepeat.contentDescription = getString(R.string.repeat_page)
            }
            RepeatMode.AYAH -> {
                btnRepeat.setImageResource(R.drawable.ic_repeat_one)
                btnRepeat.alpha = 1f
                btnRepeat.contentDescription = getString(R.string.repeat_ayah)
            }
        }
        audioHelper.repeatMode = when (repeatMode) {
            RepeatMode.OFF  -> "off"
            RepeatMode.PAGE -> "page"
            RepeatMode.AYAH -> "ayah"
        }
    }

    // ===================== Views & State =====================
    lateinit var toolbar: MaterialToolbar
    lateinit var viewPager: ViewPager2

    // أسفل الشاشة
    lateinit var bottomOverlays: LinearLayout
    lateinit var audioControlsCard: MaterialCardView

    // شريط التلاوة
    lateinit var audioControls: LinearLayout
    lateinit var btnPlayPause: ImageButton
    lateinit var btnQari: TextView
    lateinit var audioDownload: ImageButton
    lateinit var btnRepeat: ImageButton

    // شريط خيارات الآية
    lateinit var ayahOptionsBar: MaterialCardView
    lateinit var btnDownloadTafsir: ImageButton
    lateinit var btnShareAyah: ImageButton
    lateinit var btnCopyAyah: ImageButton
    lateinit var btnPlayAyah: ImageButton
    lateinit var btnCloseAyahBar: ImageButton
    var ayahPreview: TextView? = null

    // شريط التلاوة المتحرك داخل الـ Toolbar
    // أبقيناه عامًا لتوافق QuranSupportHelper مع الاسم القديم.
    var ayahBanner: View? = null
    private var toolbarSurahTitle: TextView? = null
    private var toolbarRecitationStrip: TextView? = null
    private var restartMarqueeRunnable: Runnable? = null
    private var lastRecitationText: String = "▶ جاهز لبدء التلاوة"

    // Services
    lateinit var prefs: SharedPreferences
    lateinit var provider: MadaniPageProvider
    lateinit var audioHelper: QuranAudioHelper
    lateinit var supportHelper: QuranSupportHelper
    lateinit var tafsirManager: TafsirManager

    // حالة
    var currentQariId: String = "fares"
    var currentPage = 1
    private var audioDrivenTargetPosition: Int? = null
    var currentSurah = 1
    var currentAyah = 1

    // تحكم بالأشرطة
    private var barsVisible = true
    var hideHandler: Handler? = null
    private val hideRunnable = Runnable { setAllBarsVisible(false) }

    // عرض الصفحات
    lateinit var adapter: AssetPageAdapter
    var lastPos: Int = -1

    // صوت بالخلفية
    private val audioBgThread = HandlerThread("quran-audio-bg").apply { start() }
    internal val audioBgHandler by lazy { Handler(audioBgThread.looper) }
    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }
    private var prepareQueueRunnable: Runnable? = null

    // Gesture
    private lateinit var gestureDetector: GestureDetectorCompat

    // ==== تكرار النطاق ====
    private data class RangeRepeatState(
        val surah: Int,
        val startAyah: Int,
        val endAyah: Int,
        var loopsLeft: Int,
        val qariId: String,
        var currentAyah: Int
    )
    @Volatile private var rangeState: RangeRepeatState? = null
    private var savedAutoContinueToNextPage: Boolean? = null

    // ==== CENTER LOADER ====
    private var centerVisibleLocks = 0
    private lateinit var centerLoader: View
    private lateinit var centerLoaderText: TextView
    private lateinit var centerLoaderPercent: TextView

    // عناصر شريط التحميل
    private lateinit var centerProgress: ProgressBar
    private lateinit var centerCount: TextView
    private lateinit var centerEta: TextView

    // قياسات وإغلاق insets
    private var toolbarHeight = 0
    private var bottomOverlaysHeight = 0
    private var topInsetLocked = 0
    private var bottomInsetLocked = 0
    private var insetsLocked = false

    // ==== Ayah Now-Playing Banner State ====
    private var ayahBannerVisibleByToggle = true
    private var ayahBannerSuppressedByUser = false
    // ظاهر افتراضيًا منذ فتح صفحة المصحف، ويبقى ظاهرًا عند انتقال الصفحات.
    private var recitationStripActive = true
    private var reopenDownloadDialogAfterSettings = false

    fun openDownloadSettingsFromDialog() {
        reopenDownloadDialogAfterSettings = true
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    // ========================== IMMERSIVE ==========================
    private fun isLandscape(): Boolean =
        resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    private fun enterImmersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        c.isAppearanceLightStatusBars = false
        c.isAppearanceLightNavigationBars = false
        c.hide(WindowInsetsCompat.Type.systemBars())
    }
    private fun exitImmersive() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.show(WindowInsetsCompat.Type.systemBars())
    }

    private val notifReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ACT_PLAY -> {
                    val resumed = audioHelper.resumePagePlayback(currentPage, currentQariId)
                    if (!resumed) audioHelper.startPagePlayback(currentPage, currentQariId)
                    showAyahBanner()
                    updateNotification(isPlaying = true)
                    setAllBarsVisible(true)
                }
                ACT_PAUSE -> {
                    audioHelper.pausePagePlayback()
                    setRecitationStripActive(false)
                    updateNotification(isPlaying = false)
                    setAllBarsVisible(true)
                }
                ACT_STOP -> {
                    audioHelper.stopPagePlaybackAndClearQueue()
                    setRecitationStripActive(false)
                    NotificationManagerCompat.from(this@QuranPageActivity).cancel(NOTIF_ID)
                    setAllBarsVisible(false)
                }
                PagesDownloadService.ACTION_FILES_CHANGED -> {
                    val qariId = intent.getStringExtra(PagesDownloadService.EXTRA_CHANGED_QARI)
                        ?: currentQariId
                    audioHelper.refreshExternalCache(qariId)
                    debouncePrepareQueue(currentPage, immediate = true)
                }
            }
        }
    }

    private val ayahBarGuard = ViewTreeObserver.OnPreDrawListener {
        if (ayahBarClosedByUser && ayahOptionsBar.visibility == View.VISIBLE) {
            ayahOptionsBar.visibility = View.GONE
            return@OnPreDrawListener false
        }
        true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntentAction(intent.action)
    }
    private fun pendingSelfBroadcast(action: String, reqCode: Int): PendingIntent {
        val i = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this, reqCode, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
    private fun handleIntentAction(action: String?) {
        when (action) {
            ACT_PLAY -> {
                val resumed = audioHelper.resumePagePlayback(currentPage, currentQariId)
                if (!resumed) audioHelper.startPagePlayback(currentPage, currentQariId)
                showAyahBanner()
                updateNotification(isPlaying = true)
                setAllBarsVisible(true)
            }

            ACT_PAUSE -> {
                audioHelper.pausePagePlayback()
                setRecitationStripActive(false)
                updateNotification(isPlaying = false)
                setAllBarsVisible(true)
            }

            ACT_STOP -> {
                audioHelper.stopPagePlaybackAndClearQueue()
                setRecitationStripActive(false)
                NotificationManagerCompat.from(this).cancel(NOTIF_ID)
                setAllBarsVisible(false)
            }
        }
    }

    // ======== مراقِب حالة التشغيل ========
    private var lastPlayingState: Boolean = false
    private val playbackWatcher = object : Runnable {
        override fun run() {
            val nowPlaying = (audioHelper.isPlaying || audioHelper.isAyahPlaying)
            lastPlayingState = nowPlaying

            // فرض الظهور في كل دورة أثناء التشغيل؛ بهذه الطريقة لا يستطيع
            // أي كود آخر أو قيمة محفوظة إخفاء الشريط عند تبديل الصفحة.
            if (nowPlaying) {
                recitationStripActive = true
                ayahBannerVisibleByToggle = true
                ayahBannerSuppressedByUser = false
                renderRecitationStrip(visible = true)
            }
            uiHandler.postDelayed(this, 250)
        }
    }

    private fun unifySystemBars() {
        val bg = ContextCompat.getColor(this, R.color.topBarBg)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        val isNight = (resources.configuration.uiMode and
                android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES

        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isNight
            isAppearanceLightNavigationBars = !isNight
        }
    }

    private val AYAH_COUNTS = intArrayOf(
        7,286,200,176,120,165,206,75,129,109,123,111,43,52,99,128,111,110,98,135,112,78,118,64,77,227,93,88,69,60,
        34,30,73,54,45,83,182,88,75,85,54,53,89,59,37,35,38,29,18,45,60,49,62,55,78,96,29,22,24,13,14,11,11,18,
        12,12,30,52,52,44,28,28,20,56,40,31,50,40,46,42,29,19,36,25,22,17,19,26,30,20,15,21,11,8,8,19,5,8,8,11,
        11,8,3,9,5,4,5,6,3,5,4,5,4,5,6
    )

    // ===================== CENTER LOADER =====================
    fun showCenterLoader(text: String? = null, percent: Int = 0) {
        if (userClosedOverlay) return
        runOnUiThread {
            if (!text.isNullOrEmpty() && ::centerLoaderText.isInitialized) {
                centerLoaderText.text = text
            }
            if (::centerLoaderPercent.isInitialized) {
                centerLoaderPercent.text = "  ($percent%)"
            }
            if (::centerLoader.isInitialized) {
                centerLoader.visibility = View.VISIBLE
                centerLoader.isClickable = false
                centerLoader.isFocusable = false
                centerLoader.bringToFront()
                centerLoader.elevation = 200f
                centerLoader.translationZ = 200f
            }
        }
    }

    fun hideCenterLoader() {
        runOnUiThread {
            if (::centerLoader.isInitialized) centerLoader.visibility = View.GONE
            if (::centerLoaderPercent.isInitialized) centerLoaderPercent.text = "  (0%)"
            if (::centerCount.isInitialized) centerCount.text = "0 / $totalPagesForPrefetch"
            if (::centerEta.isInitialized) centerEta.text = getString(R.string.remaining_time)
            if (::centerProgress.isInitialized) centerProgress.progress = 0
        }
    }

    private fun acquireCenterLock(msg: String? = null, progress: Int? = null) {
        if (userClosedOverlay) return
        centerVisibleLocks++

        runOnUiThread {
            if (::centerLoader.isInitialized) {
                centerLoader.visibility = View.VISIBLE
                centerLoader.isClickable = false
                centerLoader.isFocusable = false
                centerLoader.bringToFront()
                centerLoader.elevation = 200f
                centerLoader.translationZ = 200f
            }

            msg?.let { if (::centerLoaderText.isInitialized) centerLoaderText.text = it }

            if (::centerLoaderPercent.isInitialized && progress != null) {
                centerLoaderPercent.text = "  ($progress%)"
                centerLoaderPercent.visibility = View.VISIBLE
            }
        }
    }

    private fun releaseCenterLock() {
        if (centerVisibleLocks > 0) centerVisibleLocks--

        runOnUiThread {
            if (centerVisibleLocks == 0 && !bulkPrefetchRunning && ::centerLoader.isInitialized) {
                centerLoader.visibility = View.GONE
                if (::centerLoaderPercent.isInitialized) centerLoaderPercent.text = "  (0%)"
            }
        }
    }

    // ===================== التحميل الجماعي =====================
    @SuppressLint("SetTextI18n")
    private fun startBulkPagesPrefetch() {
        if (prefs.getBoolean("pages_prefetched", false)) return
        if (bulkPrefetchRunning) return

        bulkPrefetchRunning = true
        downloadedCount = 0
        isPaused = false
        isCancelled = false
        userClosedOverlay = false

        showCenterLoader(
            "يرجى الانتظار أثناء تنزيل صفحات المصحف… املأ وقتك بالاستغفار والصلاة على النبي ﷺ",
            0
        )

        val parallelism = 4
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        val startTime = System.currentTimeMillis()

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val semaphore = kotlinx.coroutines.sync.Semaphore(parallelism)
                val jobs = mutableListOf<kotlinx.coroutines.Deferred<Unit>>()

                for (page in 1..totalPagesForPrefetch) {

                    if (isCancelled) break
                    waitIfPaused()
                    if (isCancelled) break

                    semaphore.acquire()

                    val job = async(Dispatchers.IO) {
                        try {
                            val success: Boolean = kotlinx.coroutines.suspendCancellableCoroutine { cont ->
                                PageImageLoader.prefetchPageRetry(this@QuranPageActivity, page) { ok ->
                                    if (!cont.isCompleted) cont.resume(ok) {}
                                }
                            }

                            if (success) {
                                val done = counter.incrementAndGet()
                                downloadedCount = done

                                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000L
                                val remaining = if (done > 0)
                                    (((totalPagesForPrefetch - done) * elapsedSec) / done.toDouble()).roundToLong()
                                else 0L

                                withContext(Dispatchers.Main) {
                                    if (userClosedOverlay) return@withContext

                                    if (::centerProgress.isInitialized) {
                                        centerProgress.max = totalPagesForPrefetch
                                        centerProgress.progress = done
                                    }

                                    if (::centerCount.isInitialized) {
                                        centerCount.text = "$done / $totalPagesForPrefetch"
                                    }

                                    if (::centerLoaderPercent.isInitialized) {
                                        val percent = if (totalPagesForPrefetch > 0) (done * 100) / totalPagesForPrefetch else 0
                                        centerLoaderPercent.text = "  ($percent%)"
                                    }

                                    if (::centerEta.isInitialized) {
                                        centerEta.text = formatEta(remaining)
                                    }
                                }
                            }

                        } finally {
                            semaphore.release()
                        }
                    }

                    jobs.add(job)
                }

                jobs.awaitAll()

                withContext(Dispatchers.Main) {
                    val done = counter.get()

                    if (!isCancelled && done >= totalPagesForPrefetch) {
                        PageImageLoader.PAGES_FULLY_CACHED = true
                        prefs.edit().putBoolean("pages_prefetched", true).apply()

                        if (!userClosedOverlay) {
                            showCenterLoader("🎉 تم تنزيل جميع الصفحات بنجاح 📚", 100)
                            if (::centerLoaderPercent.isInitialized) {
                                centerLoaderPercent.text = "✔ $done / $totalPagesForPrefetch صفحة محفوظة 🔒"
                            }
                            if (::centerCount.isInitialized) {
                                centerCount.text = "$done / $totalPagesForPrefetch"
                            }
                        }

                        val current = viewPager.currentItem
                        viewPager.post {
                            viewPager.adapter?.notifyItemRangeChanged(
                                (current - 1).coerceAtLeast(0),
                                3
                            )
                        }

                        Handler(Looper.getMainLooper()).postDelayed({
                            hideCenterLoader()
                            Toast.makeText(
                                this@QuranPageActivity,
                                "📚 تم تحميل جميع صفحات المصحف بنجاح 🔒",
                                Toast.LENGTH_LONG
                            ).show()
                        }, 1200)

                    } else if (isCancelled) {
                        hideCenterLoader()
                    } else {
                        if (!userClosedOverlay) {
                            showCenterLoader("⚠ تم إيقاف التحميل قبل إكمال جميع الصفحات", 0)
                        } else {

                        }
                    }
                }

            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    hideCenterLoader()
                    Toast.makeText(
                        this@QuranPageActivity,
                        "⚠ حدث خطأ أثناء تنزيل الصفحات",
                        Toast.LENGTH_LONG
                    ).show()
                }
            } finally {
                bulkPrefetchRunning = false
            }
        }
    }

    // =====================================================================
    @SuppressLint("TouchableViewAccessibility", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        val pageBg = ContextCompat.getColor(this, R.color.quran_page_bg)
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        window.statusBarColor = pageBg
        window.navigationBarColor = pageBg
        run {
            val night = (resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
        }

        setContentView(R.layout.activity_quran_page)

        // ===== ربط عناصر اللودر =====
        centerLoader        = findViewById(R.id.centerLoader)
        centerLoaderText    = findViewById(R.id.centerText)
        centerLoaderPercent = findViewById(R.id.centerPercent)
        centerProgress      = findViewById(R.id.centerProgress)
        centerCount         = findViewById(R.id.centerCount)
        centerEta           = findViewById(R.id.centerEta)

        initMarquee(centerLoaderText)

        val btnPauseLoader  = findViewById<Button>(R.id.btnPause)
        val btnResumeLoader = findViewById<Button>(R.id.btnResume)
        val btnCloseLoader  = findViewById<Button>(R.id.btnClose)

        btnPauseLoader.setOnClickListener {
            isPaused = true
            btnPauseLoader.isEnabled = false
            btnResumeLoader.isEnabled = true
            Toast.makeText(this, "تم إيقاف التحميل مؤقتًا", Toast.LENGTH_SHORT).show()
        }

        btnResumeLoader.setOnClickListener {
            if (isPaused) {
                isPaused = false
                btnPauseLoader.isEnabled = true
                btnResumeLoader.isEnabled = false
                synchronized(pauseLock) { pauseLock.notifyAll() }
                Toast.makeText(this, "جارٍ استئناف تحميل الصفحات…", Toast.LENGTH_SHORT).show()
            }
        }

        btnCloseLoader.setOnClickListener {
            userClosedOverlay = true
            hideCenterLoader()
            showBackgroundContinueNotification()
        }

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.toolbar)) { view: View, insets: WindowInsetsCompat ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            view.setPadding(view.paddingLeft, top, view.paddingRight, view.paddingBottom)
            WindowInsetsCompat.CONSUMED
        }

        ensureNotificationChannel()
        requestNotifPermissionIfNeeded()

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)

        val haveAllPages = PageImageLoader.areAllPagesDownloaded(this)
        when {
            haveAllPages && !prefs.getBoolean("pages_prefetched", false) -> {
                prefs.edit().putBoolean("pages_prefetched", true).apply()
            }
            !haveAllPages && prefs.getBoolean("pages_prefetched", false) -> {
                prefs.edit().putBoolean("pages_prefetched", false).apply()
            }
        }
        PageImageLoader.PAGES_FULLY_CACHED = haveAllPages

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        provider      = MadaniPageProvider(this)
        supportHelper = QuranSupportHelper(this, provider)
        audioHelper   = QuranAudioHelper(this, provider, supportHelper, audioBgHandler)
        tafsirManager = TafsirManager(this)

        // ===== قراءة المقاصد =====
        val pageFromNew  = intent.getIntExtra(EXTRA_TARGET_PAGE, 0)
        val pageFromOld  = intent.getIntExtra("page", intent.getIntExtra("page_number", 0))
        val surahFromNew = intent.getIntExtra(EXTRA_TARGET_SURAH, 0)
        val ayahFromNew  = intent.getIntExtra(EXTRA_TARGET_AYAH, 0)
        val surahFromOld = intent.getIntExtra("surah_number", 0)
        val ayahFromOld  = intent.getIntExtra("ayah_number", 0)

        currentSurah = when {
            surahFromNew > 0 -> surahFromNew
            surahFromOld > 0 -> surahFromOld
            else -> 1
        }
        currentAyah = when {
            ayahFromNew > 0 -> ayahFromNew
            ayahFromOld > 0 -> ayahFromOld
            else -> 1
        }
        currentPage = when {
            pageFromNew > 0 -> pageFromNew
            pageFromOld > 0 -> pageFromOld
            else -> try { AyahLocator.getPageFor(this, currentSurah, currentAyah) } catch (_: Throwable) { 1 }
        }.coerceIn(1, TOTAL_PAGES)

        // ===== ربط الواجهة =====
        toolbar           = findViewById(R.id.toolbar)
        viewPager         = findViewById(R.id.pageViewPager)
        bottomOverlays    = findViewById(R.id.bottomOverlays)

        (viewPager.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
            if (resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT) {
                lp.topMargin = dpToPx(75)
            } else {
                lp.topMargin = 0
            }
            viewPager.layoutParams = lp
        }

        audioControlsCard = findViewById(R.id.audioControlsCard)
        audioControls     = findViewById(R.id.audioControls)
        btnPlayPause      = findViewById(R.id.btnPlayPause)
        btnQari           = findViewById(R.id.btnQari)
        audioDownload     = findViewById(R.id.audio_download)
        btnRepeat         = findViewById(R.id.btnRepeat)
        ayahOptionsBar    = findViewById(R.id.ayahOptionsBar)
        ayahOptionsBar.viewTreeObserver.addOnPreDrawListener(ayahBarGuard)
        val btnTafsirMenu = findViewById<TextView>(R.id.btnTafsirMenu)
        btnDownloadTafsir = findViewById(R.id.btnDownloadTafsir)
        btnShareAyah      = findViewById(R.id.btnShareAyah)
        btnCopyAyah       = findViewById(R.id.btnCopyAyah)
        btnPlayAyah       = findViewById(R.id.btnPlayAyah)
        btnCloseAyahBar   = findViewById(R.id.btnCloseOptions)
        ayahPreview       = findViewById(R.id.ayahPreview)
        initMarquee(ayahPreview)
        ayahOptionsBar.visibility = View.GONE

        findViewById<ImageButton>(R.id.btnCloseAudioBar)?.setOnClickListener { hideAudioBar() }

        // ===== حالة شريط التلاوة داخل الـ Toolbar =====
        ayahBannerVisibleByToggle = prefs.getBoolean(KEY_BANNER_TOGGLE_STATE, true)
        ayahBannerSuppressedByUser = prefs.getBoolean(KEY_SUPPRESS_AYAH_BANNER, false)

        audioHelper.setOnAyahChangedListener { surah, ayah, text ->
            runOnUiThread { showOrUpdateAyahBanner(surah, ayah, text) }
        }

        // ===== تحميل الإعدادات =====
        val savedQariId = prefs.getString(KEY_QARI_ID, currentQariId) ?: currentQariId
        currentQariId = provider.canonicalQariId(savedQariId)
        if (provider.getQariById(currentQariId) == null) currentQariId = "fares"
        if (currentQariId != savedQariId) {
            prefs.edit().putString(KEY_QARI_ID, currentQariId).apply()
        }
        repeatMode = loadRepeatMode()
        audioHelper.repeatCount = prefs.getInt(PREF_REPEAT_AYAH, 1).coerceIn(1, 99)
        audioHelper.pageRepeatCount = prefs.getInt(PREF_REPEAT_PAGE, 1).coerceIn(1, 99)
        updateRepeatIcon()

        // ===== Insets: أسفل الشاشة =====
        ViewCompat.setOnApplyWindowInsetsListener(bottomOverlays) { v: View, insets: WindowInsetsCompat ->
            val bottomBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            val base = (12 * resources.displayMetrics.density).toInt()
            v.updatePadding(bottom = bottomBars + base)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.setOnApplyWindowInsetsListener(viewPager) { v: View, insets: WindowInsetsCompat ->
            val navBottom = insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.navigationBars()).bottom
            (v as ViewGroup).setPadding(0, 0, 0, navBottom)
            (v as ViewGroup).clipToPadding = false
            (v as ViewGroup).clipChildren  = false
            WindowInsetsCompat.CONSUMED
        }

        // ===== Toolbar =====
        setSupportActionBar(toolbar)
        toolbar.setPopupTheme(R.style.ToolbarPopupTheme)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }
        setupToolbarRecitationStrip(
            supportHelper.getSurahNameForPage(currentPage).ifEmpty { getString(R.string.app_name) }
        )
        applyAyahBannerVisibility()

        // ===== ViewPager =====
        viewPager.setBackgroundColor(pageBg)
        viewPager.offscreenPageLimit = 1
        (viewPager.getChildAt(0) as? RecyclerView)?.apply {
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(4)
            overScrollMode = RecyclerView.OVER_SCROLL_NEVER
        }

        setupTafsirMenuButton(btnTafsirMenu)

        // ===== زر التكرار =====
        btnRepeat.setOnClickListener { view ->
            val popup = AppCompatPopupMenu(this, view)
            menuInflater.inflate(R.menu.menu_repeat_modes, popup.menu)
            popup.setOnMenuItemClickListener { mi ->
                when (mi.itemId) {
                    R.id.repeat_off -> {
                        audioHelper.cancelRangeRepeat()
                        repeatMode = RepeatMode.OFF
                        saveRepeatMode(repeatMode)
                        updateRepeatIcon()
                        Toast.makeText(this, getString(R.string.repeat_off), Toast.LENGTH_SHORT).show()
                        setAllBarsVisible(true)
                        true
                    }
                    R.id.repeat_ayah -> {
                        audioHelper.cancelRangeRepeat()
                        repeatMode = RepeatMode.AYAH
                        saveRepeatMode(repeatMode)
                        updateRepeatIcon()
                        Toast.makeText(this, "تكرار آية × ${audioHelper.repeatCount}", Toast.LENGTH_SHORT).show()
                        setAllBarsVisible(true)
                        true
                    }
                    R.id.repeat_page -> {
                        audioHelper.cancelRangeRepeat()
                        repeatMode = RepeatMode.PAGE
                        saveRepeatMode(repeatMode)
                        updateRepeatIcon()
                        Toast.makeText(this, "تكرار الصفحة × ${audioHelper.pageRepeatCount}", Toast.LENGTH_SHORT).show()
                        setAllBarsVisible(true)
                        true
                    }
                    R.id.repeat_range -> {
                        this@QuranPageActivity.showRepeatRangeDialog()
                        setAllBarsVisible(true)
                        true
                    }
                    else -> false
                }
            }
            popup.show()
        }

        // ===== أزرار شريط الآية =====
        btnPlayAyah.setOnClickListener {
            if (audioHelper.isAyahPlaying) {
                audioHelper.stopSingleAyah()
                setRecitationStripActive(false)
                updateNotification(isPlaying = false)
                setAllBarsVisible(true)
            } else {
                audioHelper.playSingleAyah(currentSurah, currentAyah, currentQariId)
                showAyahBanner()
                updateNotification(
                    isPlaying = true,
                    surah = currentSurah,
                    ayah = currentAyah,
                    customText = ayahPreview?.text?.toString()
                )
                showAudioBar()
                setAllBarsVisible(true)
            }
            if (!ayahBarClosedByUser) showAyahOptions(true)
        }
        btnCopyAyah.setOnClickListener {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = ayahPreview?.text?.toString().orEmpty()
            cm.setPrimaryClip(ClipData.newPlainText("Ayah", text))
            Toast.makeText(this, "تم نسخ الآية!", Toast.LENGTH_SHORT).show()
        }
        btnShareAyah.setOnClickListener { supportHelper.shareCurrentAyah(currentSurah, currentAyah) }
        btnCloseAyahBar.setOnClickListener {
            ayahBarClosedByUser = true
            showAyahOptions(false)
        }

        // ===== اختيار القارئ =====
        btnQari.text = provider.getQariById(currentQariId)?.name ?: "فارس عباد"
        btnQari.setOnClickListener {
            supportHelper.showQariPicker { qari ->
                val oldWasPagePlaying = audioHelper.isPlaying
                val oldWasAyahPlaying = audioHelper.isAyahPlaying
                val page = currentPage
                val sura = currentSurah
                val ayah = currentAyah
                currentQariId = qari.id
                btnQari.text  = qari.name
                prefs.edit().putString(KEY_QARI_ID, currentQariId).apply()
                audioHelper.stopAllPlaybackAndClearQueue()
                when {
                    oldWasPagePlaying -> {
                        showAudioBar()
                        audioHelper.startPagePlayback(page, currentQariId)
                        updateNotification(isPlaying = true)
                    }
                    oldWasAyahPlaying -> {
                        showAudioBar()
                        audioHelper.playSingleAyah(sura, ayah, currentQariId)
                        updateNotification(
                            isPlaying = true,
                            surah = sura,
                            ayah  = ayah,
                            customText = ayahPreview?.text?.toString()
                        )
                    }
                    else -> {
                        debouncePrepareQueue(page, immediate = true)
                    }
                }
                if (oldWasPagePlaying || oldWasAyahPlaying) showAyahBanner()
                setAllBarsVisible(true)
            }
        }

        audioDownload.setOnClickListener {
            supportHelper.showDownloadScopeDialog(currentPage, currentSurah, currentQariId)
        }

        btnPlayPause.setOnClickListener {
            if (audioHelper.isPlaying) {
                audioHelper.pausePagePlayback()
                setRecitationStripActive(false)
                btnPlayPause.setImageResource(R.drawable.ic_play)
                updateNotification(isPlaying = false)
            } else {
                val resumed = audioHelper.resumePagePlayback(currentPage, currentQariId)
                if (resumed) {
                    btnPlayPause.setImageResource(R.drawable.ic_pause)
                    updateNotification(isPlaying = true)
                    showAyahBanner()
                } else {
                    btnPlayPause.setImageResource(R.drawable.ic_loading)
                    audioHelper.startPagePlayback(currentPage, currentQariId, true)
                    showAyahBanner()
                    updateNotification(isPlaying = true)
                }
            }
        }

        prepareBarsOverlay()

        // ===== Adapter للصفحات =====
        val pageNames = (1..TOTAL_PAGES).map { "page_$it.webp" }
        adapter = AssetPageAdapter(
            context = this,
            pages = pageNames,
            realPageNumber = 0,
            onAyahClick = { s, a, t ->
                currentSurah = s
                currentAyah  = a
                val text = try { supportHelper.getAyahTextFromJson(s, a) } catch (_: Throwable) { t ?: "" }
                ayahPreview?.text = text
                ayahPreview?.isSelected = true
                ayahBarClosedByUser = false
                showAyahOptions(true)
                setAllBarsVisible(true)
            },
            onImageTap = { safeToggleBars() },
        )

        viewPager.adapter = adapter
        viewPager.setCurrentItem((currentPage - 1).coerceIn(0, TOTAL_PAGES - 1), false)
        viewPager.post { adapter.highlightAyahOnPage(currentPage, currentSurah, currentAyah) }

        viewPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)

                val pageChangedByAudio = audioDrivenTargetPosition == position
                if (pageChangedByAudio || audioDrivenTargetPosition != null) {
                    audioDrivenTargetPosition = null
                }
                val pagePlaybackWasActive =
                    audioHelper.isPlaying || audioHelper.isPagePlaybackStarting

                if (lastPos != -1 && lastPos != position) {
                    adapter.clearHighlightOnPage(lastPos + 1)
                }

                lastPos = position
                currentPage = position + 1

                adapter.clearHighlightOnPage(currentPage)
                showAyahOptions(false)

                val title = supportHelper.getSurahNameForPage(currentPage)
                    .ifEmpty { getString(R.string.app_name) }
                if (toolbarSurahTitle?.text?.toString() != title) {
                    toolbarSurahTitle?.text = title
                }

                saveLastVisitedPage(this@QuranPageActivity, currentPage)
                invalidateOptionsMenu()
                if (pagePlaybackWasActive && !pageChangedByAudio) {
                    // سحب يدوي أثناء التلاوة: أوقف الصفحة السابقة فورًا وابدأ
                    // الصفحة الجديدة من أول آية، دون انتظار اكتمال الطابور القديم.
                    prepareQueueRunnable?.let { uiHandler.removeCallbacks(it) }
                    btnPlayPause.setImageResource(R.drawable.ic_loading)
                    audioHelper.switchPagePlaybackImmediately(currentPage, currentQariId)
                    updateNotification(isPlaying = true)
                } else if (!pageChangedByAudio) {
                    // إذا كانت التلاوة متوقفة مؤقتًا، فلا نحتفظ بمشغل الصفحة
                    // السابقة بعد السحب. وإلا سيستأنف زر التشغيل الصوت القديم.
                    audioHelper.stopPagePlaybackAndClearQueue()
                    debouncePrepareQueue(currentPage)
                }

                if (recitationStripActive) {
                    setRecitationStripActive(true)
                } else {
                    applyAyahBannerVisibility()
                }
            }
        })

        hideHandler = Handler(Looper.getMainLooper())
        setAllBarsVisible(true)
        debouncePrepareQueue(currentPage, immediate = true)

        if (!prefs.getBoolean("pages_prefetched", false) && !bulkPrefetchRunning) {
            startBulkPagesPrefetch()
        }

        lastPlayingState = (audioHelper.isPlaying || audioHelper.isAyahPlaying)
        // لا نربط الظهور الأولي بحالة الصوت؛ الشريط ظاهر افتراضيًا دائمًا.
        recitationStripActive = true
        applyAyahBannerVisibility()
        uiHandler.post(playbackWatcher)
    }

    /** انتقال أنشأه مشغل الصوت؛ لا يُعامل كسحب يدوي ولا يعيد تشغيل الطابور. */
    fun navigateToPageFromAudio(page: Int, smoothScroll: Boolean = true) {
        val position = (page - 1).coerceIn(0, TOTAL_PAGES - 1)
        audioDrivenTargetPosition = position
        viewPager.setCurrentItem(position, smoothScroll)
    }

    data class AyahId(val surah: Int, val ayah: Int)

    // ===== شريط التلاوة المتحرك داخل الـ Toolbar =====
    private fun setRecitationStripActive(active: Boolean) {
        recitationStripActive = active
        if (active) {
            // أي بدء/استمرار حقيقي للتلاوة يعيد الشريط ما لم يخفه المستخدم بنفسه.
            if (!ayahBannerSuppressedByUser) {
                ayahBannerVisibleByToggle = true
                if (::prefs.isInitialized) {
                    prefs.edit()
                        .putBoolean(KEY_BANNER_TOGGLE_STATE, true)
                        .apply()
                }
            }
        } else {
            lastPlayingState = false
        }
        applyAyahBannerVisibility()
    }

    /**
     * لا نعيد تعيين النص إلا إذا تغيّرت الآية فعلًا؛ إعادة text أو isSelected
     * باستمرار تعيد تشغيل الـ Marquee من البداية وتسبب الرجفة.
     */
    private fun renderRecitationStrip(visible: Boolean) {
        val strip = toolbarRecitationStrip ?: return

        val wantedVisibility = if (visible) View.VISIBLE else View.GONE
        if (strip.visibility != wantedVisibility) {
            strip.visibility = wantedVisibility
        }

        if (!visible) return

        if (strip.text?.toString() != lastRecitationText) {
            restartMarqueeRunnable?.let(strip::removeCallbacks)
            strip.isSelected = false
            strip.text = lastRecitationText
            strip.requestLayout()

            // ننتظر اكتمال قياس النص الجديد؛ وإلا قد يقرر TextView أن الآية
            // الطويلة لا تحتاج إلى Marquee قبل معرفة عرضها الحقيقي.
            val restart = Runnable {
                if (strip.visibility == View.VISIBLE) {
                    strip.isSelected = true
                    strip.invalidate()
                }
            }
            restartMarqueeRunnable = restart
            strip.postDelayed(restart, 150L)
        } else if (!strip.isSelected) {
            strip.isSelected = true
        }
    }

    private fun applyAyahBannerVisibility() {
        // أثناء التلاوة نعتمد على الحالة الفعلية فقط، ولا نعتمد على مفاتيح
        // البانر القديم التي قد تتغير عند تجهيز الصفحة التالية.
        val shouldShow = recitationStripActive

        renderRecitationStrip(visible = shouldShow)
    }

    private fun showAyahBanner() {
        ayahBannerSuppressedByUser = false
        ayahBannerVisibleByToggle = true
        prefs.edit()
            .putBoolean(KEY_SUPPRESS_AYAH_BANNER, false)
            .putBoolean(KEY_BANNER_TOGGLE_STATE, true)
            .apply()
        setRecitationStripActive(true)
    }

    fun hideAyahBanner(userClose: Boolean = false) {
        // لا نسمح لأي كود قديم بإخفاء الشريط أثناء التلاوة، حتى لو مرّر
        // userClose=true. الإيقاف الحقيقي يغيّر recitationStripActive أولًا.
        if (recitationStripActive) {
            renderRecitationStrip(visible = true)
            return
        }

        if (userClose) {
            ayahBannerSuppressedByUser = true
            ayahBannerVisibleByToggle = false
            prefs.edit()
                .putBoolean(KEY_SUPPRESS_AYAH_BANNER, true)
                .putBoolean(KEY_BANNER_TOGGLE_STATE, false)
                .apply()
        } else {
            ayahBannerVisibleByToggle = false
            prefs.edit()
                .putBoolean(KEY_BANNER_TOGGLE_STATE, false)
                .apply()
        }
        applyAyahBannerVisibility()
    }

    fun showOrUpdateAyahBanner(surah: Int, ayah: Int, text: String?) {
        val surahName = supportHelper.getSurahNameByNumber(surah).ifEmpty { "سورة $surah" }
        currentSurah = surah
        currentAyah = ayah

        val newRecitationText = buildString {
            append("▶ ")
            append(surahName)
            append(" • آية ")
            append(ayah)
            if (!text.isNullOrBlank()) {
                append(" • ")
                append(text.trim())
            }
        }

        if (lastRecitationText != newRecitationText) {
            lastRecitationText = newRecitationText
        }
        setRecitationStripActive(true)
    }

    private fun requestAyahOptions() {
        ayahBarClosedByUser = false
        showAyahOptions(true)
        setAllBarsVisible(true)
    }

    // ======== إظهار/إخفاء شريط التلاوة ========
    private fun showAudioBar() {
        if (audioControlsCard.visibility != View.VISIBLE) {
            val startTY = (audioControlsCard.height.takeIf { it > 0 } ?: bottomOverlaysHeight) + bottomInsetLocked
            audioControlsCard.translationY = startTY.toFloat()
            audioControlsCard.alpha = 0f
            audioControlsCard.visibility = View.VISIBLE
            audioControlsCard.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(160)
                .start()
        }
    }

    private fun hideAudioBar() {
        if (audioControlsCard.visibility == View.VISIBLE) {
            val endTY = (audioControlsCard.height.takeIf { it > 0 } ?: bottomOverlays.height) + bottomInsetLocked
            audioControlsCard.animate()
                .translationY(endTY.toFloat())
                .alpha(0f)
                .setDuration(140)
                .withEndAction {
                    audioControlsCard.visibility = View.GONE
                    audioControlsCard.translationY = 0f
                    audioControlsCard.alpha = 1f
                }
                .start()
        }
    }

    private fun showBackgroundContinueNotification() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        val channelId = "recitation_bg_info"

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val ch = android.app.NotificationChannel(
                channelId,
                getString(R.string.notif_download_title),
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "إشعار معلوماتي قصير عند متابعة التنزيل بالخلفية"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            nm.createNotificationChannel(ch)
        }

        val n = androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_download)
            .setContentTitle(getString(R.string.notif_download_title))
            .setContentText(getString(R.string.background_will_continue))
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setTimeoutAfter(3500)
            .build()

        val id = 9901
        try {
            nm.notify(id, n)
            if (android.os.Build.VERSION.SDK_INT < 26) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    nm.cancel(id)
                }, 3500)
            }
        } catch (_: SecurityException) {
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && isLandscape() && !barsVisible) enterImmersive()
    }

    override fun onResume() {
        super.onResume()
        if (isLandscape() && !barsVisible) enterImmersive() else exitImmersive()
        if (reopenDownloadDialogAfterSettings && ::supportHelper.isInitialized) {
            reopenDownloadDialogAfterSettings = false
            window.decorView.post {
                supportHelper.showDownloadScopeDialog(currentPage, currentSurah, currentQariId)
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(ACT_PLAY); addAction(ACT_PAUSE); addAction(ACT_STOP)
            addAction(PagesDownloadService.ACTION_FILES_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(notifReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(notifReceiver, filter)
        }
    }

    override fun onStop() {
        super.onStop()
        try { unregisterReceiver(notifReceiver) } catch (_: Exception) {}
    }

    // ============================ NOTIFICATION ============================
    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID, "تشغيل التلاوة", NotificationManager.IMPORTANCE_LOW
            ).apply { description = "إشعار تشغيل/إيقاف تلاوة القرآن" }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(ch)
        }
    }

    private fun requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_POST_NOTIFS
                )
            }
        }
    }

    fun updateNotification(
        isPlaying: Boolean,
        surah: Int? = null,
        ayah: Int? = null,
        customText: String? = null
    ) {
        ensureNotificationChannel()

        val title = if (surah != null && ayah != null) {
            val sName = if (::supportHelper.isInitialized)
                supportHelper.getSurahNameByNumber(surah).ifEmpty { "سورة $surah" }
            else
                "سورة $surah"
            "$sName • آية $ayah"
        } else {
            if (isPlaying) "جاري تلاوة القرآن" else "التلاوة متوقفة"
        }

        val text = when {
            !customText.isNullOrBlank() -> customText
            surah != null && ayah != null -> if (::supportHelper.isInitialized) {
                try { supportHelper.getAyahTextFromJson(surah, ayah) } catch (_: Throwable) { "—" }
            } else {
                "—"
            }
            else -> "—"
        }

        val contentPI = PendingIntent.getActivity(
            this, 100,
            Intent(this, QuranPageActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(if (isPlaying) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setOngoing(isPlaying)
            .setContentIntent(contentPI)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        val playPI  = pendingSelfBroadcast(ACT_PLAY , 201)
        val pausePI = pendingSelfBroadcast(ACT_PAUSE, 202)
        val stopPI  = pendingSelfBroadcast(ACT_STOP , 203)

        if (isPlaying) {
            builder.addAction(android.R.drawable.ic_media_pause, "إيقاف مؤقت", pausePI)
        } else {
            builder.addAction(android.R.drawable.ic_media_play, "تشغيل", playPI)
        }
        builder.addAction(android.R.drawable.ic_menu_close_clear_cancel, "إيقاف", stopPI)

        val canPost = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true

        if (canPost) NotificationManagerCompat.from(this).notify(NOTIF_ID, builder.build())
    }

    // ============================ MENU ============================
    private fun debouncePrepareQueue(page: Int, immediate: Boolean = false) {
        prepareQueueRunnable?.let { uiHandler.removeCallbacks(it) }
        val r = Runnable { audioHelper.prepareAudioQueueForPage(page, currentQariId) }
        prepareQueueRunnable = r
        uiHandler.postDelayed(r, if (immediate) 0 else 120)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_page_viewer, menu)

        updateThemeToggleTitle(menu.findItem(R.id.action_toggle_theme))

        val bannerItem = menu.findItem(R.id.action_show_ayah_banner)
        // الشريط أصبح تلقائيًا؛ نخفي مفتاح التبديل الذي كان يعيد حالته إلى false.
        bannerItem.isVisible = false

        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val favItem = menu.findItem(R.id.action_toggle_page_bookmark)
        favItem?.setIcon(
            if (isFavoritePage(this, currentPage)) R.drawable.ic_star_filled
            else R.drawable.ic_star_border
        )

        val themeItem = menu.findItem(R.id.action_toggle_theme)
        updateThemeToggleTitle(themeItem)

        val bannerItem = menu.findItem(R.id.action_show_ayah_banner)
        bannerItem?.isVisible = false

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {

            R.id.action_toggle_page_bookmark -> {
                if (isFavoritePage(this, currentPage)) {
                    removeFavoritePage(this, currentPage)
                    item.setIcon(R.drawable.ic_star_border)
                    Toast.makeText(this, "تم إزالة حفظ الصفحة", Toast.LENGTH_SHORT).show()
                } else {
                    addFavoritePage(this, currentPage)
                    item.setIcon(R.drawable.ic_star_filled)
                    toolbar.startAnimation(AnimationUtils.loadAnimation(this, R.anim.star_click))
                    Toast.makeText(this, "تم حفظ الصفحة في المفضلة", Toast.LENGTH_SHORT).show()
                }
                true
            }

            R.id.action_toggle_theme -> {
                ThemeManager.toggle(this)
                updateThemeToggleTitle(item)
                recreate()
                true
            }

            R.id.action_show_ayah_banner -> {
                if (ayahBannerVisibleByToggle && !ayahBannerSuppressedByUser) {
                    // هذا إخفاء صريح من المستخدم، وليس إخفاءً تلقائيًا.
                    hideAyahBanner(userClose = true)
                    item.title = "إظهار شريط التلاوة"
                } else {
                    showAyahBanner()
                    item.title = "إخفاء شريط التلاوة"
                }
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun updateThemeToggleTitle(item: MenuItem?) {
        item ?: return
        val night = ThemeManager.isNight(this)
        item.title = if (night) "☀️" else "🌙"
    }

    override fun onDestroy() {
        super.onDestroy()
        isCancelled = true
        exec?.shutdownNow()
        try { audioBgThread.quitSafely() } catch (_: Exception) {}

        hideHandler?.removeCallbacks(hideRunnable)
        prepareQueueRunnable?.let { uiHandler.removeCallbacks(it) }
        uiHandler.removeCallbacks(playbackWatcher)
        restartMarqueeRunnable?.let { toolbarRecitationStrip?.removeCallbacks(it) }

        try { ayahOptionsBar.viewTreeObserver.removeOnPreDrawListener(ayahBarGuard) } catch (_: Exception) {}

        prepareQueueRunnable = null
        restartMarqueeRunnable = null
    }

    /**
     * يبني محتوى العنوان داخل المساحة المتبقية من الـ Toolbar.
     * في الواجهة العربية يكون الترتيب:
     * رجوع | اسم السورة | شريط التلاوة المتحرك | نجمة المفضلة.
     */
    private fun setupToolbarRecitationStrip(initialTitle: String) {
        supportActionBar?.setDisplayShowTitleEnabled(false)
        toolbar.title = ""

        toolbar.findViewWithTag<View>("quran_toolbar_content")?.let {
            toolbar.removeView(it)
        }

        // لا نستخدم toolbar.titleTextColor لأنها غير متاحة في بعض إصدارات AppCompat.
        val colorValue = android.util.TypedValue()
        val titleColor = if (
            theme.resolveAttribute(android.R.attr.textColorPrimary, colorValue, true)
        ) {
            if (colorValue.resourceId != 0) {
                ContextCompat.getColor(this, colorValue.resourceId)
            } else {
                colorValue.data
            }
        } else {
            android.graphics.Color.WHITE
        }
        val isNightMode = ThemeManager.isNight(this)
        val glassTopColor = android.graphics.Color.argb(
            if (isNightMode) 58 else 105,
            255, 255, 255
        )
        val glassBottomColor = android.graphics.Color.argb(
            if (isNightMode) 20 else 42,
            255, 255, 255
        )
        val glassStrokeColor = android.graphics.Color.argb(
            if (isNightMode) 170 else 225,
            if (isNightMode) 255 else 177,
            if (isNightMode) 255 else 139,
            if (isNightMode) 255 else 69
        )

        val content = LinearLayout(this).apply {
            tag = "quran_toolbar_content"
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            clipChildren = true
        }

        toolbarSurahTitle = TextView(this).apply {
            text = initialTitle
            setTextColor(titleColor)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            gravity = Gravity.CENTER_VERTICAL
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dpToPx(110)
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
        }

        toolbarRecitationStrip = TextView(this).apply {
            text = lastRecitationText
            setTextColor(titleColor)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG_RTL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            setPadding(dpToPx(9), 0, dpToPx(9), 0)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(glassTopColor, glassBottomColor)
            ).apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = dpToPx(14).toFloat()
                setStroke(dpToPx(2), glassStrokeColor)
            }
            elevation = dpToPx(2).toFloat()
            clipToOutline = true
            contentDescription = "الآية التي يجري تلاوتها"
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            visibility = View.GONE
            setOnClickListener {
                showAudioBar()
                setAllBarsVisible(true)
            }
        }
        initMarquee(toolbarRecitationStrip)
        // مرجع توافق فقط للأكواد القديمة. لا نجعله يشير إلى شريط الـ Toolbar
        // حتى لا يتمكن QuranSupportHelper من إخفاء الشريط عند تبديل الصفحة.
        ayahBanner = View(this).apply { visibility = View.GONE }

        val titleParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            marginEnd = dpToPx(6)
        }

        val stripParams = LinearLayout.LayoutParams(
            0,
            dpToPx(28),
            1f
        ).apply {
            marginEnd = dpToPx(4)
        }

        content.addView(toolbarSurahTitle, titleParams)
        content.addView(toolbarRecitationStrip, stripParams)

        val toolbarParams = androidx.appcompat.widget.Toolbar.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = dpToPx(2)
            marginEnd = dpToPx(2)
        }
        toolbar.addView(content, toolbarParams)
    }

    private fun initMarquee(tv: TextView?) {
        tv?.apply {
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.MARQUEE
            marqueeRepeatLimit = -1
            // بعض إصدارات TextView على أجهزة Samsung لا تبدأ الـ Marquee
            // للنص الطويل ما لم يكن العرض قابلًا للتركيز، حتى مع isSelected.
            isFocusable = true
            isFocusableInTouchMode = true
            setHorizontallyScrolling(true)
            isHorizontalFadingEdgeEnabled = true
            setFadingEdgeLength(dpToPx(10))
            isSelected = true
        }
    }

    private fun prepareBarsOverlay() {
        toolbar.post {
            toolbarHeight = toolbar.height
            ViewCompat.setOnApplyWindowInsetsListener(toolbar) { v: View, insets: WindowInsetsCompat ->
                if (!insetsLocked) {
                    topInsetLocked = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
                }
                v.updatePadding(top = topInsetLocked)
                WindowInsetsCompat.CONSUMED
            }
        }
        bottomOverlays.post { bottomOverlaysHeight = bottomOverlays.height }

        toolbar.visibility = View.VISIBLE
        bottomOverlays.visibility = View.VISIBLE
        audioControlsCard.visibility = View.VISIBLE
        ayahOptionsBar.visibility = View.GONE

        toolbar.alpha = 1f
        bottomOverlays.alpha = 1f
        audioControlsCard.alpha = 1f

        toolbar.post { insetsLocked = true }
    }

    private fun setAllBarsVisible(
        visible: Boolean,
        autoHideMs: Int? = null,
        allowWhilePlaying: Boolean = false
    ) {
        barsVisible = visible

        val ctrl = WindowInsetsControllerCompat(window, window.decorView)
        ctrl.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (isLandscape()) {
            if (visible) ctrl.show(WindowInsetsCompat.Type.systemBars())
            else ctrl.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            ctrl.show(WindowInsetsCompat.Type.systemBars())
        }

        val dur = 180L

        if (visible) toolbar.visibility = View.VISIBLE
        val topH = (if (toolbar.height > 0) toolbar.height else toolbarHeight) + topInsetLocked
        val tYTop = if (visible) 0f else -topH.toFloat()
        toolbar.animate()
            .translationY(tYTop)
            .alpha(if (visible) 1f else 0f)
            .setDuration(dur)
            .withEndAction { if (!visible && isLandscape()) toolbar.visibility = View.GONE }
            .start()

        if (visible) {
            bottomOverlays.visibility = View.VISIBLE
            audioControlsCard.visibility = View.VISIBLE
        }
        val bottomH =
            (if (bottomOverlays.height > 0) bottomOverlays.height else bottomOverlaysHeight) + bottomInsetLocked
        val tYBottom = if (visible) 0f else bottomH.toFloat()

        bottomOverlays.animate()
            .translationY(tYBottom)
            .alpha(if (visible) 1f else 0f)
            .setDuration(dur)
            .withEndAction { if (!visible) bottomOverlays.visibility = View.GONE }
            .start()

        audioControlsCard.animate()
            .translationY(tYBottom)
            .alpha(if (visible) 1f else 0f)
            .setDuration(dur)
            .withEndAction { if (!visible) audioControlsCard.visibility = View.GONE }
            .start()

        hideHandler?.removeCallbacks(hideRunnable)
    }

    private fun showAyahOptions(show: Boolean, force: Boolean = false) {
        if (show && ayahBarClosedByUser && !force) return

        ayahOptionsBar.clearAnimation()
        if (show) {
            ayahOptionsBar.alpha = 0f
            ayahOptionsBar.visibility = View.VISIBLE
            ayahOptionsBar.animate().alpha(1f).setDuration(150).start()
        } else {
            ayahOptionsBar.animate()
                .alpha(0f).setDuration(120)
                .withEndAction {
                    ayahOptionsBar.visibility = View.GONE
                    ayahOptionsBar.alpha = 1f
                }.start()
        }
    }

    private fun arePagesCached(): Boolean = prefs.getBoolean(KEY_PAGES_CACHED, false)
    private fun setPagesCachedDone() { prefs.edit().putBoolean(KEY_PAGES_CACHED, true).apply() }

    private fun formatEta(sec: Long): String {
        val s = max(0, sec)
        val h = s / 3600
        val m = (s % 3600) / 60
        val ss = s % 60
        return if (h > 0) String.format("الوقت المتبقي: %d:%02d:%02d", h, m, ss)
        else String.format("الوقت المتبقي: %02d:%02d", m, ss)
    }

    @Volatile private var isPaused = false
    @Volatile private var isCancelled = false
    @Volatile private var userClosedOverlay = false
    private val pauseLock = Object()
    private var exec: ExecutorService? = null

    private var bulkPrefetchRunning = false
    private var totalPagesForPrefetch = 604
    private var downloadedCount = 0

    private fun waitIfPaused() {
        synchronized(pauseLock) {
            while (isPaused && !isCancelled) {
                try { pauseLock.wait(150) } catch (_: InterruptedException) { break }
            }
        }
    }

    fun showBarsThenAutoHide(delayMs: Int = 3500) {}

    private fun dpToPx(dp: Int): Int =
        (dp * resources.displayMetrics.density).toInt()

    private fun setupTafsirMenuButton(btnTafsirMenu: TextView) {
        fun updateButtonText() {
            btnTafsirMenu.text = "فتح التفسير ▾"
            btnTafsirMenu.contentDescription = try {
                "اختيار المفسر ثم فتح التفسير. المحدد حاليًا: ${tafsirManager.getSelectedName()}"
            } catch (_: Throwable) {
                "اختيار المفسر ثم فتح التفسير"
            }
        }

        fun currentAyahText(): String = try {
            supportHelper.getAyahTextFromJson(currentSurah, currentAyah)
        } catch (_: Throwable) {
            ayahPreview?.text?.toString().orEmpty()
        }

        updateButtonText()
        btnTafsirMenu.isClickable = true
        btnTafsirMenu.isFocusable = true

        // استجابة مرئية للمس تجعل الزر واضحًا وحيًا عند الضغط.
        btnTafsirMenu.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> view.animate()
                    .scaleX(0.94f).scaleY(0.94f).alpha(0.78f)
                    .setDuration(70L).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate()
                    .scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(110L).start()
            }
            false
        }

        // اعرض المفسرين أولًا، ثم افتح تفسير المفسر الذي اختاره المستخدم.
        btnTafsirMenu.setOnClickListener {
            tafsirManager.showPickerDialog(this) {
                updateButtonText()
                tafsirManager.openSelectedTafsir(
                    this,
                    currentSurah,
                    currentAyah,
                    currentAyahText()
                )
            }
        }

        btnDownloadTafsir.setOnClickListener {
            tafsirManager.showDownloadDialog(
                activity = this,
                surah = currentSurah,
                ayah = currentAyah,
                ayahText = currentAyahText()
            ) {
                updateButtonText()
            }
        }
    }

    private fun normalizeDigits(s: String?): String {
        if (s.isNullOrBlank()) return ""
        val ar = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        val fa = charArrayOf('۰','۱','۲','۳','۴','۵','۶','۷','۸','۹')
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val i1 = ar.indexOf(ch); val i2 = fa.indexOf(ch)
            when {
                i1 >= 0 -> sb.append(('0'.code + i1).toChar())
                i2 >= 0 -> sb.append(('0'.code + i2).toChar())
                else    -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private var lastTapAt = 0L

    private fun toggleBars() {
        if (barsVisible) {
            showAyahOptions(false, force = true)
            setAllBarsVisible(false)
        } else {
            setAllBarsVisible(true)
        }
    }

    private fun safeToggleBars() {
        val now = SystemClock.uptimeMillis()
        if (now - lastTapAt < 180) return
        lastTapAt = now
        toggleBars()
    }

    @SuppressLint("InflateParams")
    private fun showRepeatRangeDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_repeat_range, null, false)

        val spSurah = v.findViewById<Spinner>(R.id.spSurah)
        val etFrom =
            v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etFrom)
        val etTo =
            v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTo)
        val tilFrom =
            v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilFrom)
        val tilTo =
            v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilTo)
        val btnFromCur =
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnFromCurrent)
        val btnToEnd =
            v.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnToEndPage)
        val cgTimes = v.findViewById<com.google.android.material.chip.ChipGroup>(R.id.cgTimes)
        val etTimes =
            v.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etTimes)
        val tilTimes =
            v.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.tilTimes)

        val surahNames = (1..114).map { n ->
            supportHelper.getSurahNameByNumber(n).ifEmpty { "سورة $n" }
        }
        spSurah.adapter =
            ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, surahNames)
        spSurah.setSelection((currentSurah - 1).coerceIn(0, 113))

        btnFromCur.setOnClickListener { etFrom.setText(currentAyah.toString()) }
        btnToEnd.setOnClickListener {
            val selSurah = spSurah.selectedItemPosition + 1
            val bounds = supportHelper.loadAyahBoundsForPage(currentPage)
            val lastOnPage =
                bounds.filter { it.sura_id == selSurah }.maxOfOrNull { it.aya_id } ?: currentAyah
            etTo.setText(lastOnPage.toString())
        }

        fun updateCustomVisibility(checkedId: Int) {
            val custom = (checkedId == R.id.chipCustom)
            tilTimes.visibility = if (custom) View.VISIBLE else View.GONE
            if (custom) {
                etTimes.requestFocus()
                etTimes.setSelection(etTimes.text?.length ?: 0)
            }
        }
        cgTimes.setOnCheckedChangeListener(
            com.google.android.material.chip.ChipGroup.OnCheckedChangeListener { _, checkedId ->
                updateCustomVisibility(checkedId)
            }
        )
        updateCustomVisibility(cgTimes.checkedChipId)

        fun selectedTimes(): Int {
            val id = cgTimes.checkedChipId
            val txt = when (id) {
                R.id.chip1 -> "1"
                R.id.chip3 -> "3"
                R.id.chip5 -> "5"
                R.id.chip10 -> "10"
                R.id.chipCustom -> etTimes.text?.toString().orEmpty()
                else -> "1"
            }
            return parseArabicIntAdvanced(txt, 1).coerceIn(1, 99)
        }

        audioHelper.loadLastRange()?.let { last ->
            spSurah.setSelection((last.surah - 1).coerceIn(0, 113))
            etFrom.setText(last.fromAyah.toString())
            etTo.setText(last.toAyah.toString())
            when (last.times) {
                1 -> cgTimes.check(R.id.chip1)
                3 -> cgTimes.check(R.id.chip3)
                5 -> cgTimes.check(R.id.chip5)
                10 -> cgTimes.check(R.id.chip10)
                else -> {
                    cgTimes.check(R.id.chipCustom)
                    tilTimes.visibility = View.VISIBLE
                    etTimes.setText(last.times.toString())
                }
            }
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("تكرار نطاق آيات")
            .setView(v)
            .setPositiveButton(getString(R.string.ok)) { dialog, _ ->

                tilFrom.error = null
                tilTo.error = null

                val surah = spSurah.selectedItemPosition + 1
                val from  = parseArabicIntAdvanced(etFrom.text?.toString(), 1)
                val to    = parseArabicIntAdvanced(etTo.text?.toString(), 1)
                val times = selectedTimes()

                var valid = true
                if (from <= 0) {
                    tilFrom.error = "أدخل رقم آية صحيح"
                    valid = false
                }
                if (to <= 0) {
                    tilTo.error = "أدخل رقم آية صحيح"
                    valid = false
                }
                if (!valid) return@setPositiveButton

                audioHelper.startRangeRepeat(surah, from, to, times, currentQariId)
                setAllBarsVisible(true, null, allowWhilePlaying = true)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun parseArabicInt(src: String?): Int {
        if (src.isNullOrBlank()) return 0
        val mapped = buildString(src.length) {
            for (ch in src.trim()) {
                append(
                    when (ch) {
                        '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                        '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                        else -> ch
                    }
                )
            }
        }
        return mapped.toIntOrNull() ?: 0
    }

    private fun parseArabicIntAdvanced(src: String?, defaultValue: Int = 1): Int {
        if (src.isNullOrBlank()) return defaultValue

        val normalized = buildString(src.length) {
            for (ch in src.trim()) {
                append(
                    when (ch) {
                        '٠' -> '0'; '١' -> '1'; '٢' -> '2'; '٣' -> '3'; '٤' -> '4'
                        '٥' -> '5'; '٦' -> '6'; '٧' -> '7'; '٨' -> '8'; '٩' -> '9'
                        else -> ch
                    }
                )
            }
        }

        return normalized.filter { it.isDigit() }.toIntOrNull() ?: defaultValue
    }

    private val SURAH_NAMES = arrayOf(
        "1. الفاتحة","2. البقرة","3. آل عمران","4. النساء","5. المائدة","6. الأنعام","7. الأعراف",
        "8. الأنفال","9. التوبة","10. يونس","11. هود","12. يوسف","13. الرعد","14. إبراهيم","15. الحجر",
        "16. النحل","17. الإسراء","18. الكهف","19. مريم","20. طه","21. الأنبياء","22. الحج","23. المؤمنون",
        "24. النور","25. الفرقان","26. الشعراء","27. النمل","28. القصص","29. العنكبوت","30. الروم","31. لقمان",
        "32. السجدة","33. الأحزاب","34. سبأ","35. فاطر","36. يس","37. الصافات","38. ص","39. الزمر",
        "40. غافر","41. فصلت","42. الشورى","43. الزخرف","44. الدخان","45. الجاثية","46. الأحقاف","47. محمد",
        "48. الفتح","49. الحجرات","50. ق","51. الذاريات","52. الطور","53. النجم","54. القمر","55. الرحمن",
        "56. الواقعة","57. الحديد","58. المجادلة","59. الحشر","60. الممتحنة","61. الصف","62. الجمعة",
        "63. المنافقون","64. التغابن","65. الطلاق","66. التحريم","67. الملك","68. القلم","69. الحاقة",
        "70. المعارج","71. نوح","72. الجن","73. المزمل","74. المدثر","75. القيامة","76. الإنسان",
        "77. المرسلات","78. النبأ","79. النازعات","80. عبس","81. التكوير","82. الانفطار","83. المطففين",
        "84. الانشقاق","85. البروج","86. الطارق","87. الأعلى","88. الغاشية","89. الفجر","90. البلد",
        "91. الشمس","92. الليل","93. الضحى","94. الشرح","95. التين","96. العلق","97. القدر",
        "98. البينة","99. الزلزلة","100. العاديات","101. القارعة","102. التكاثر","103. العصر",
        "104. الهمزة","105. الفيل","106. قريش","107. الماعون","108. الكوثر","109. الكافرون",
        "110. النصر","111. المسد","112. الإخلاص","113. الفلق","114. الناس"
    )
}
