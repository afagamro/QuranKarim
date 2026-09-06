// File: app/src/main/java/com/hag/al_quran/helpers/QuranAudioHelper.kt
package com.hag.al_quran.helpers

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.util.Log
import android.widget.Toast
import androidx.core.content.edit
import androidx.documentfile.provider.DocumentFile
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import com.hag.al_quran.audio.MadaniPageProvider
import com.hag.al_quran.search.AyahLocator
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

private const val TAG = "RangeRepeat"

class QuranAudioHelper(
    private val activity: QuranPageActivity,
    private val provider: MadaniPageProvider,
    val supportHelper: QuranSupportHelper,
    private val bgHandler: Handler
) {

    private var mediaPlayer: MediaPlayer? = null
    private var streamPlayer: ExoPlayer? = null
    private var streamSingleControl: Boolean = false
    private val externalCacheExecutor = Executors.newSingleThreadExecutor()

    @Volatile var isPlaying = false
    @Volatile var isAyahPlaying = false
    @Volatile var isPagePlaybackStarting = false
        private set
    @Volatile private var playToken: Long = 0
    @Volatile private var suppressAutoNext: Boolean = false
    @Volatile private var playGen: Int = 0

    private val queuePrepareLock = Any()
    private var queuePreparingKey: String? = null
    private val queuePrepareCallbacks = mutableListOf<() -> Unit>()
    @Volatile private var preparedPage: Int = -1
    @Volatile private var preparedQariId: String = ""
    @Volatile private var pausedPlaybackPage: Int = -1
    @Volatile private var pausedPlaybackQariId: String = ""

    val ayahQueue: MutableList<Triple<String, Int, Int>> = CopyOnWriteArrayList()
    private var currentIndex: Int = -1
    private var resumeFromMs: Int = 0

    var autoContinueToNextPage: Boolean = true

    private var singleSurahPlaying: Int? = null
    private var singleAyahPlaying: Int? = null

    // === التكرار الأساسي ===
    var repeatMode: String = "off"      // "off" | "page" | "ayah" | "range"
    var repeatCount: Int = 1
    var pageRepeatCount: Int = 1
    private var currentRepeat = 0
    private var lastRepeatedAyah: Pair<Int, Int>? = null
    private var pageRepeatIteration = 0

    // === تكرار النطاق ===
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

    // ===================== تفضيلات آخر آية وآخر نطاق =====================
    private val prefs by lazy { activity.getSharedPreferences("quran_audio", Context.MODE_PRIVATE) }
    private val externalIndexPrefs by lazy {
        activity.getSharedPreferences("external_audio_index_v1", Context.MODE_PRIVATE)
    }
    private val externalWarmupScheduled = ConcurrentHashMap.newKeySet<String>()

    private fun saveLastAyah(surah: Int, ayah: Int) {
        prefs.edit {
            putInt("last_surah", surah)
            putInt("last_ayah", ayah)
        }
    }

    private fun loadLastAyah(): Pair<Int, Int>? {
        val s = prefs.getInt("last_surah", -1)
        val a = prefs.getInt("last_ayah", -1)
        return if (s > 0 && a > 0) (s to a) else null
    }

    data class LastRange(val surah: Int, val fromAyah: Int, val toAyah: Int, val times: Int)

    fun saveLastRange(surah: Int, from: Int, to: Int, times: Int) {
        prefs.edit {
            putInt("range_surah", surah)
            putInt("range_from",  from)
            putInt("range_to",    to)
            putInt("range_times", times)
        }
    }

    fun loadLastRange(): LastRange? {
        val s  = prefs.getInt("range_surah", -1)
        val f  = prefs.getInt("range_from",  -1)
        val t  = prefs.getInt("range_to",    -1)
        val tm = prefs.getInt("range_times", -1)
        return if (s > 0 && f > 0 && t > 0 && tm > 0) LastRange(s, f, t, tm) else null
    }

    // ===================== مصادر الصوت =====================
    sealed class DataSource {
        data class LocalFile(val file: File): DataSource()
        data class ExternalUri(val uri: Uri): DataSource()
        data class Asset(val afd: AssetFileDescriptor, val debugPath: String): DataSource()
        data class Remote(val url: String): DataSource()
    }

    private fun ensurePlayer(): MediaPlayer {
        if (mediaPlayer == null) {
            mediaPlayer = MediaPlayer().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                } else {
                    @Suppress("DEPRECATION")
                    setAudioStreamType(AudioManager.STREAM_MUSIC)
                }
            }
        }
        return mediaPlayer!!
    }

    /**
     * مشغل البث السريع للروابط البعيدة. يبدأ بعد مخزن صغير بدل انتظار
     * MediaPlayer الطويل، وهو مهم خصوصًا لملفات السور الكاملة الكبيرة.
     */
    @OptIn(UnstableApi::class)
    private fun playFastRemote(
        url: String,
        token: Long,
        startMs: Int = 0,
        singleControl: Boolean = false,
        onStarted: () -> Unit,
        onEnded: () -> Unit,
        onFailed: () -> Unit
    ): Boolean {
        if (url.isBlank()) return false

        return playFastSource(
            mediaItem = MediaItem.fromUri(url),
            token = token,
            startMs = startMs,
            singleControl = singleControl,
            onStarted = onStarted,
            onEnded = onEnded,
            onFailed = onFailed
        )
    }

    @OptIn(UnstableApi::class)
    private fun playFastSource(
        mediaItem: MediaItem,
        token: Long,
        startMs: Int = 0,
        singleControl: Boolean = false,
        onStarted: () -> Unit,
        onEnded: () -> Unit,
        onFailed: () -> Unit
    ): Boolean {

        stopStreamPlayer()
        streamSingleControl = singleControl

        return try {
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    1_500,  // أقل مخزن أثناء التشغيل
                    15_000, // أقصى مخزن لتجنب استهلاك زائد
                    400,    // يبدأ بعد 0.4 ثانية صوت تقريبًا
                    1_000   // بعد انقطاع الشبكة
                )
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            val player = ExoPlayer.Builder(activity)
                .setLoadControl(loadControl)
                .build()
            streamPlayer = player

            var startDelivered = false
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (token != playToken || streamPlayer !== player) return

                    if (playbackState == Player.STATE_READY && !startDelivered) {
                        startDelivered = true
                        onStarted()
                    } else if (playbackState == Player.STATE_ENDED) {
                        stopStreamPlayer(player)
                        onEnded()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (token != playToken || streamPlayer !== player) return
                    Log.e("QuranAudioHelper", "Fast remote playback failed", error)
                    stopStreamPlayer(player)
                    onFailed()
                }
            })

            player.setMediaItem(mediaItem)
            if (startMs > 0) player.seekTo(startMs.toLong())
            player.playWhenReady = true
            player.prepare()
            true
        } catch (t: Throwable) {
            Log.e("QuranAudioHelper", "Unable to create fast stream player", t)
            stopStreamPlayer()
            false
        }
    }

    private fun stopStreamPlayer(expected: ExoPlayer? = null) {
        val player = streamPlayer ?: return
        if (expected != null && player !== expected) return
        streamPlayer = null
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.release() }
        streamSingleControl = false
    }

    private fun clearListeners() {
        try { mediaPlayer?.setOnPreparedListener(null) } catch (_: Exception) {}
        try { mediaPlayer?.setOnCompletionListener(null) } catch (_: Exception) {}
        try { mediaPlayer?.setOnErrorListener(null) } catch (_: Exception) {}
    }

    private fun isActuallyPlaying(): Boolean =
        try {
            val streamActive = streamPlayer?.let {
                it.playWhenReady &&
                    (it.playbackState == Player.STATE_BUFFERING || it.playbackState == Player.STATE_READY)
            } == true
            streamActive || mediaPlayer?.isPlaying == true
        } catch (_: Exception) { false }

    // ===================== إدارة عامة =====================
    fun release() {
        suppressAutoNext = true
        playToken++
        stopStreamPlayer()
        clearListeners()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        externalCacheExecutor.shutdownNow()
        isPlaying = false
        isAyahPlaying = false
        cancelRangeRepeat()
    }

    fun stopAll() {
        suppressAutoNext = true
        playToken++
        stopStreamPlayer()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        clearListeners()
        isAyahPlaying = false
        isPlaying = false
        resumeFromMs = 0
        activity.runOnUiThread {
            runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
            supportHelper.hideAyahBanner()
        }
        singleSurahPlaying = null
        singleAyahPlaying = null
        cancelRangeRepeat()
    }

    // ===================== آية واحدة =====================
    fun toggleSingleAyah(surah: Int, ayah: Int, qariId: String) {
        if (isAyahPlaying && singleSurahPlaying == surah && singleAyahPlaying == ayah) {
            stopSingleAyah()
        } else {
            if (isPlaying) stopPagePlayback()
            if (rangeState != null) cancelRangeRepeat()
            playSingleAyah(surah, ayah, qariId)
        }
    }

    fun playSingleAyah(surah: Int, ayah: Int, qariId: String) {
        if (provider.isWholeSurahQari(qariId)) {
            if (isPlaying) stopPagePlayback()
            if (isAyahPlaying) stopSingleAyah()
            if (rangeState != null) cancelRangeRepeat()
            singleSurahPlaying = surah
            singleAyahPlaying = ayah
            playWholeSurah(surah, qariId, singleControl = true)
            return
        }

        if (isPlaying) stopSingleAyah()
        if (rangeState != null) cancelRangeRepeat()

        suppressAutoNext = false
        isAyahPlaying = false
        singleSurahPlaying = surah
        singleAyahPlaying = ayah

        val token = ++playToken

        try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            when (val ds = resolveAyahDataSource(qariId, surah, ayah)) {
                is DataSource.LocalFile -> {
                    val started = playSingleAyahFast(
                        MediaItem.fromUri(Uri.fromFile(ds.file)), token, surah, ayah
                    )
                    Log.d("QuranAudioHelper", "Playing OFFLINE(file): ${ds.file.absolutePath}")
                    if (started) return
                    mp.setDataSource(ds.file.absolutePath)
                }
                is DataSource.ExternalUri -> {
                    val started = playSingleAyahFast(
                        MediaItem.fromUri(ds.uri), token, surah, ayah
                    )
                    Log.d("QuranAudioHelper", "Playing OFFLINE(uri): ${ds.uri}")
                    if (started) return
                    mp.setDataSource(activity, ds.uri)
                }
                is DataSource.Asset -> {
                    mp.setDataSource(ds.afd.fileDescriptor, ds.afd.startOffset, ds.afd.length)
                    Log.d("QuranAudioHelper", "Playing OFFLINE(asset): ${ds.debugPath}")
                }
                is DataSource.Remote -> {
                    val started = playSingleAyahFast(
                        MediaItem.fromUri(ds.url), token, surah, ayah
                    )
                    toastOnceOnline()
                    Log.d("QuranAudioHelper", "Playing ONLINE(url): ${ds.url}")
                    if (started) return
                    mp.setDataSource(ds.url)
                }
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                it.start()
                isAyahPlaying = true
                activity.runOnUiThread {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_pause) }
                    val text = supportHelper.getAyahTextFromJson(surah, ayah)
                    supportHelper.showOrUpdateAyahBanner(surah, ayah, text)
                    runCatching { activity.ayahOptionsBar.visibility = android.view.View.VISIBLE }
                    runCatching { activity.adapter.highlightAyahOnPage(activity.currentPage, surah, ayah) }
                }
                saveLastAyah(surah, ayah)

            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener
                if (suppressAutoNext) return@setOnCompletionListener
                isAyahPlaying = false
                activity.runOnUiThread {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
                    supportHelper.hideAyahBanner()
                }
                singleSurahPlaying = null
                singleAyahPlaying = null
            }

            mp.setOnErrorListener { _, _, _ ->
                stopSingleAyah()
                true
            }

            mp.prepareAsync()
        } catch (_: Exception) {
            Toast.makeText(activity, "تعذر تشغيل التلاوة", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopSingleAyah() {
        suppressAutoNext = true
        playToken++
        stopStreamPlayer()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        clearListeners()

        isAyahPlaying = false
        activity.runOnUiThread {
            runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
            supportHelper.hideAyahBanner()
        }
        singleSurahPlaying = null
        singleAyahPlaying = null
    }

    // ===================== تجهيز طابور الصفحة =====================
    fun prepareAudioQueueForPage(
        page: Int,
        qariId: String,
        fromStart: Boolean = true,
        onPrepared: (() -> Unit)? = null
    ) {
        val safeId = safeQari(qariId)
        if (isQueueReadyFor(page, safeId)) {
            activity.runOnUiThread {
                currentIndex = queueStartIndex(fromStart)
                resumeFromMs = 0
                onPrepared?.invoke()
            }
            return
        }

        val requestKey = "$page|$safeId"
        var shouldPrepare = false
        var myGen = 0
        synchronized(queuePrepareLock) {
            if (queuePreparingKey == requestKey) {
                onPrepared?.let(queuePrepareCallbacks::add)
            } else {
                myGen = ++playGen
                queuePreparingKey = requestKey
                queuePrepareCallbacks.clear()
                onPrepared?.let(queuePrepareCallbacks::add)
                shouldPrepare = true
            }
        }
        if (!shouldPrepare) return

        bgHandler.post {
            val qari = provider.getQariById(qariId) ?: return@post

            val list = supportHelper.loadAyahBoundsForPage(page)
                .sortedWith(compareBy({ it.sura_id }, { it.aya_id }))

            val newQueue = list.mapTo(ArrayList(list.size)) {
                Triple("", it.sura_id, it.aya_id)
            }
            val saved = if (fromStart) null else loadLastAyah()
            val newIndex = saved?.let { (s, a) ->
                newQueue.indexOfFirst { it.second == s && it.third == a }
            }?.takeIf { it >= 0 } ?: 0

            activity.runOnUiThread {
                val callbacks: List<() -> Unit>
                synchronized(queuePrepareLock) {
                    if (myGen != playGen || queuePreparingKey != requestKey) {
                        return@runOnUiThread
                    }

                    ayahQueue.clear()
                    ayahQueue.addAll(newQueue)
                    currentIndex = if (fromStart) 0 else newIndex
                    resumeFromMs = 0
                    preparedPage = page
                    preparedQariId = safeId
                    queuePreparingKey = null
                    callbacks = queuePrepareCallbacks.toList()
                    queuePrepareCallbacks.clear()
                }
                callbacks.forEach { callback -> runCatching { callback() } }
            }

            // فهرسة قديمة لمرة واحدة فقط، وبعد مهلة حتى لا تنافس بدء الصوت.
            scheduleExternalIndexWarmup(qari.id)
        }
    }

    private fun playSingleAyahFast(
        mediaItem: MediaItem,
        token: Long,
        surah: Int,
        ayah: Int
    ): Boolean = playFastSource(
        mediaItem = mediaItem,
        token = token,
        singleControl = true,
        onStarted = {
            if (token == playToken) {
                isAyahPlaying = true
                activity.runOnUiThread {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_pause) }
                    val text = supportHelper.getAyahTextFromJson(surah, ayah)
                    supportHelper.showOrUpdateAyahBanner(surah, ayah, text)
                    runCatching { activity.ayahOptionsBar.visibility = android.view.View.VISIBLE }
                    runCatching { activity.adapter.highlightAyahOnPage(activity.currentPage, surah, ayah) }
                }
                saveLastAyah(surah, ayah)
            }
        },
        onEnded = {
            if (token == playToken && !suppressAutoNext) {
                isAyahPlaying = false
                singleSurahPlaying = null
                singleAyahPlaying = null
                activity.runOnUiThread {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
                    supportHelper.hideAyahBanner()
                }
            }
        },
        onFailed = {
            if (token == playToken) stopSingleAyah()
        }
    )

    fun isQueueReadyFor(page: Int, qariId: String): Boolean =
        preparedPage == page &&
            preparedQariId == safeQari(qariId) &&
            ayahQueue.isNotEmpty()

    private fun queueStartIndex(fromStart: Boolean): Int {
        if (fromStart) return 0
        val saved = loadLastAyah() ?: return 0
        return ayahQueue.indexOfFirst {
            it.second == saved.first && it.third == saved.second
        }.takeIf { it >= 0 } ?: 0
    }

    // ===================== عند تغيير القارئ =====================
    fun onQariChanged(newQariId: String) {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}

        clearExternalCache()
        scheduleExternalIndexWarmup(newQariId)

        isPlaying = false
        isAyahPlaying = false
        isPagePlaybackStarting = false
        pausedPlaybackPage = -1
        pausedPlaybackQariId = ""
        ayahQueue.clear()
        preparedPage = -1
        preparedQariId = ""
        currentIndex = -1
        resumeFromMs = 0

        activity.runOnUiThread {
            activity.btnPlayPause.setImageResource(R.drawable.ic_play)
            supportHelper.hideAyahBanner()
        }
        cancelRangeRepeat()
    }

    fun preloadAudio(ds: DataSource) {
        try {
            val mp = ensurePlayer()
            mp.reset()
            when (ds) {
                is DataSource.LocalFile -> mp.setDataSource(ds.file.absolutePath)
                is DataSource.ExternalUri -> mp.setDataSource(activity, ds.uri)
                is DataSource.Asset -> mp.setDataSource(ds.afd.fileDescriptor, ds.afd.startOffset, ds.afd.length)
                is DataSource.Remote -> if (ds.url.isNotBlank()) mp.setDataSource(ds.url)
            }
            mp.setOnPreparedListener {
                Log.d("AudioPrep", "🔥 تم التحضير المسبق للآية – جاهز للتشغيل فورًا")
            }
            mp.prepareAsync()
        } catch (_: Exception) {}
    }

    // ===================== مصدر الآية =====================
    fun resolveAyahDataSource(qariId: String, surah: Int, ayah: Int): DataSource {
        if (provider.isWholeSurahQari(qariId)) {
            return resolveWholeSurahDataSource(qariId, surah)
        }

        // 1) ملفات داخلية داخل التطبيق
        if (supportHelper.hasOfflineAyahFile(qariId, surah, ayah)) {
            val file = supportHelper.getOfflineFileForAyah(qariId, surah, ayah)
            if (file.exists() && file.length() > 0L) return DataSource.LocalFile(file)
        }

        // 2) 🔥 الذاكرة الخارجية عبر الكاش السريع (externalAyahCache)
        findExternalAyahUri(qariId, surah, ayah)?.let { uri ->
            return DataSource.ExternalUri(uri)
        }

        // 3) أصول (assets) داخل الـ APK لو موجودة
        val assetRel = "quran_audio/${safeQari(qariId)}/${fileName(surah, ayah)}"
        try {
            val afd = activity.assets.openFd(assetRel)
            return DataSource.Asset(afd, assetRel)
        } catch (_: Throwable) { }

        // 4) أونلاين من الإنترنت (fallback)
        val qari = provider.getQariById(qariId)
        val url = if (qari != null) provider.getAyahUrl(qari, surah, ayah) else ""
        return DataSource.Remote(url)
    }

    /** ملف السورة الكاملة يُحفظ بصيغة SSS000.mp3 لتمييزه عن ملفات الآيات. */
    private fun resolveWholeSurahDataSource(qariId: String, surah: Int): DataSource {
        if (supportHelper.hasOfflineAyahFile(qariId, surah, 0)) {
            val file = supportHelper.getOfflineFileForAyah(qariId, surah, 0)
            if (file.exists() && file.length() > 0L) return DataSource.LocalFile(file)
        }

        findExternalAyahUri(qariId, surah, 0)?.let { return DataSource.ExternalUri(it) }

        val assetRel = "quran_audio/${safeQari(qariId)}/${fileName(surah, 0)}"
        try {
            return DataSource.Asset(activity.assets.openFd(assetRel), assetRel)
        } catch (_: Throwable) { }

        return DataSource.Remote(provider.getSurahUrl(qariId, surah).orEmpty())
    }

    private fun playWholeSurah(surah: Int, qariId: String, singleControl: Boolean): Boolean {
        suppressAutoNext = false
        resumeFromMs = 0
        val token = ++playToken

        fun markStarted() {
            if (token != playToken) return
            isPagePlaybackStarting = false
            isPlaying = !singleControl
            isAyahPlaying = singleControl
            activity.currentSurah = surah
            activity.currentAyah = 1
            activity.runOnUiThread {
                if (singleControl) {
                    runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_pause) }
                } else {
                    runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }
                }
                supportHelper.hideAyahBanner()
            }
        }

        fun markEnded() {
            if (token != playToken) return
            isPagePlaybackStarting = false
            isPlaying = false
            isAyahPlaying = false
            resumeFromMs = 0
            singleSurahPlaying = null
            singleAyahPlaying = null
            activity.runOnUiThread {
                runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
                runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                supportHelper.hideAyahBanner()
            }
        }

        fun markFailed() {
            if (token != playToken) return
            isPagePlaybackStarting = false
            isPlaying = false
            isAyahPlaying = false
            activity.runOnUiThread {
                runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_play) }
                runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                Toast.makeText(activity, "تعذر تشغيل تسجيل السورة.", Toast.LENGTH_SHORT).show()
            }
        }

        fun startFast(mediaItem: MediaItem): Boolean = playFastSource(
            mediaItem = mediaItem,
            token = token,
            singleControl = singleControl,
            onStarted = { markStarted() },
            onEnded = { markEnded() },
            onFailed = { markFailed() }
        )

        return try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            when (val ds = resolveWholeSurahDataSource(qariId, surah)) {
                is DataSource.LocalFile -> {
                    if (startFast(MediaItem.fromUri(Uri.fromFile(ds.file)))) return true
                    mp.setDataSource(ds.file.absolutePath)
                }
                is DataSource.ExternalUri -> {
                    if (startFast(MediaItem.fromUri(ds.uri))) return true
                    mp.setDataSource(activity, ds.uri)
                }
                is DataSource.Asset -> mp.setDataSource(ds.afd.fileDescriptor, ds.afd.startOffset, ds.afd.length)
                is DataSource.Remote -> {
                    if (ds.url.isBlank()) throw IllegalStateException("Missing whole-surah URL")
                    toastOnceOnline()
                    val started = startFast(MediaItem.fromUri(ds.url))
                    if (started) {
                        Toast.makeText(
                            activity,
                            "تسجيل هذا القارئ متاح كسورة كاملة، وسيبدأ من أول السورة.",
                            Toast.LENGTH_LONG
                        ).show()
                        return true
                    }
                    // رجوع إلى MediaPlayer فقط إذا تعذر إنشاء المشغل السريع.
                    mp.setDataSource(ds.url)
                }
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                it.start()
                markStarted()
            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener
                markEnded()
            }

            mp.setOnErrorListener { _, _, _ ->
                markFailed()
                true
            }

            Toast.makeText(
                activity,
                "تسجيل هذا القارئ متاح كسورة كاملة، وسيبدأ من أول السورة.",
                Toast.LENGTH_LONG
            ).show()
            mp.prepareAsync()
            true
        } catch (_: Exception) {
            isPagePlaybackStarting = false
            isPlaying = false
            isAyahPlaying = false
            Toast.makeText(activity, "تعذر تشغيل تسجيل السورة.", Toast.LENGTH_SHORT).show()
            false
        }
    }

    fun startPagePlayback(
        page: Int,
        qariId: String,
        fromStart: Boolean = true
    ): Boolean {
        if (isAyahPlaying) stopSingleAyah()
        if (rangeState != null) cancelRangeRepeat()

        isPagePlaybackStarting = true
        pausedPlaybackPage = -1
        pausedPlaybackQariId = ""

        currentRepeat = 0
        lastRepeatedAyah = null
        pageRepeatIteration = 0
        suppressAutoNext = false

        if (provider.isWholeSurahQari(qariId)) {
            if (isQueueReadyFor(page, qariId)) {
                val surah = ayahQueue.firstOrNull()?.second
                    ?: activity.currentSurah.coerceIn(1, 114)
                return playWholeSurah(surah, qariId, singleControl = false)
            }
            prepareAudioQueueForPage(page, qariId, fromStart = true) {
                val surah = ayahQueue.firstOrNull()?.second
                    ?: activity.currentSurah.coerceIn(1, 114)
                playWholeSurah(surah, qariId, singleControl = false)
            }
            return true
        }

        if (isQueueReadyFor(page, qariId)) {
            currentIndex = queueStartIndex(fromStart)
            resumeFromMs = 0
            playAt(currentIndex, 0, qariId)
            return true
        }

        prepareAudioQueueForPage(page, qariId, fromStart) {
            if (ayahQueue.isEmpty()) {
                isPagePlaybackStarting = false
                Toast.makeText(activity, "لا توجد آيات على الصفحة الحالية.", Toast.LENGTH_SHORT).show()
                activity.btnPlayPause.setImageResource(R.drawable.ic_play)
            } else {
                if (currentIndex !in ayahQueue.indices) currentIndex = 0
                // ⬅️ هنا يبدأ التشغيل الفعلي
                playAt(currentIndex, resumeFromMs, qariId)
            }
        }

        // تم قبول طلب التشغيل وسيبدأ فور اكتمال تجهيز الصفحة الجاري بالفعل.
        return true
    }

    // ===================== إيقاف/استئناف الصفحة =====================
    fun pausePagePlayback() {
        suppressAutoNext = true
        try {
            val stream = streamPlayer
            if (stream != null) {
                resumeFromMs = stream.currentPosition.toInt().coerceAtLeast(0)
                stream.pause()
            } else {
                mediaPlayer?.let {
                    resumeFromMs = it.currentPosition
                    it.pause()
                }
            }
        } catch (_: Exception) {}
        pausedPlaybackPage = preparedPage.takeIf { it > 0 } ?: activity.currentPage
        pausedPlaybackQariId = preparedQariId
        isPagePlaybackStarting = false
        isPlaying = false
        isAyahPlaying = false
        activity.runOnUiThread { runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) } }
    }

    fun stopPagePlayback() {
        suppressAutoNext = true
        playToken++
        stopStreamPlayer()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.reset() } catch (_: Exception) {}
        clearListeners()

        isPlaying = false
        isAyahPlaying = false
        isPagePlaybackStarting = false
        pausedPlaybackPage = -1
        pausedPlaybackQariId = ""
        resumeFromMs = 0

        activity.runOnUiThread {
            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
            supportHelper.hideAyahBanner()
        }
    }

    /**
     * إيقاف نهائي للصفحة الحالية مع إلغاء أي تجهيز قديم.
     * يستخدم عند الانتقال اليدوي إلى صفحة أخرى وعند زر الإيقاف النهائي،
     * حتى لا يستطيع زر التشغيل استئناف MediaPlayer/ExoPlayer للصفحة السابقة.
     */
    fun stopPagePlaybackAndClearQueue() {
        synchronized(queuePrepareLock) {
            ++playGen
            queuePreparingKey = null
            queuePrepareCallbacks.clear()
        }

        stopPagePlayback()
        ayahQueue.clear()
        preparedPage = -1
        preparedQariId = ""
        currentIndex = -1
        resumeFromMs = 0
    }

    /**
     * عند سحب صفحة جديدة أثناء التلاوة: ألغِ الصوت والطابور القديمين،
     * ثم ابدأ الصفحة الجديدة من أول آية فور اكتمال تجهيزها القصير.
     */
    fun switchPagePlaybackImmediately(page: Int, qariId: String): Boolean {
        synchronized(queuePrepareLock) {
            ++playGen
            queuePreparingKey = null
            queuePrepareCallbacks.clear()
        }

        stopPagePlayback()
        activity.runOnUiThread {
            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_loading) }
        }
        ayahQueue.clear()
        preparedPage = -1
        preparedQariId = ""
        currentIndex = -1
        resumeFromMs = 0
        suppressAutoNext = false
        return startPagePlayback(page, qariId, fromStart = true)
    }

    fun resumePagePlayback(page: Int, qariId: String): Boolean {
        if (
            pausedPlaybackPage != page ||
            pausedPlaybackQariId != safeQari(qariId)
        ) return false

        return try {
            streamPlayer?.let {
                if (!it.isPlaying && it.mediaItemCount > 0) {
                    suppressAutoNext = false
                    it.play()
                    isPagePlaybackStarting = false
                    pausedPlaybackPage = -1
                    pausedPlaybackQariId = ""
                    isPlaying = !streamSingleControl
                    isAyahPlaying = streamSingleControl || isPlaying
                    activity.runOnUiThread {
                        if (streamSingleControl) {
                            runCatching { activity.btnPlayAyah.setImageResource(R.drawable.ic_pause) }
                        } else {
                            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }
                        }
                    }
                    return true
                }
            }
            mediaPlayer?.let {
                if (!it.isPlaying) {
                    suppressAutoNext = false   // ✅ مهم: نعيد السماح بالإكمال التلقائي
                    it.start()
                    isPagePlaybackStarting = false
                    pausedPlaybackPage = -1
                    pausedPlaybackQariId = ""
                    isPlaying = true
                    activity.runOnUiThread {
                        runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }
                    }
                    true
                } else false
            } ?: false
        } catch (_: Exception) { false }
    }

    fun togglePlayPause(page: Int, qariId: String): Boolean {
        if (isAyahPlaying) stopSingleAyah()
        return if (isActuallyPlaying()) {
            stopPagePlayback()
            false
        } else {
            val resumed = resumePagePlayback(page, qariId)
            if (!resumed) startPagePlayback(page, qariId)
            true
        }
    }

    // ===================== تكرار النطاق =====================
    fun startRangeRepeat(surah: Int, fromAyah: Int, toAyah: Int, times: Int, qariId: String) {
        if (provider.isWholeSurahQari(qariId)) {
            Toast.makeText(
                activity,
                "تكرار آية أو نطاق غير متاح لهذا التسجيل لأنه منشور كسورة كاملة.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val start = if (fromAyah <= toAyah) fromAyah else toAyah
        val end   = if (fromAyah <= toAyah) toAyah   else fromAyah
        val loops = times.coerceIn(1, 99)

        Log.d(TAG, "startRangeRepeat s=$surah, $start..$end x$loops, qari=$qariId")
        saveLastRange(surah, start, end, loops)

        stopPagePlayback()
        stopSingleAyah()

        savedAutoContinueToNextPage = savedAutoContinueToNextPage ?: autoContinueToNextPage
        autoContinueToNextPage = false

        repeatMode = "range"
        currentRepeat = 0
        lastRepeatedAyah = null

        rangeState = RangeRepeatState(
            surah = surah,
            startAyah = start,
            endAyah = end,
            loopsLeft = loops,
            qariId = qariId,
            currentAyah = start
        )

        suppressAutoNext = false
        playToken++
        playRangeCurrent()
    }

    fun cancelRangeRepeat() {
        Log.d(TAG, "cancelRangeRepeat()")
        if (rangeState == null && repeatMode != "range") return
        rangeState = null
        if (repeatMode == "range") repeatMode = "off"
        savedAutoContinueToNextPage?.let { autoContinueToNextPage = it }
        savedAutoContinueToNextPage = null
    }

    private fun playRangeCurrent() {
        val st = rangeState ?: return
        val token = ++playToken

        Log.d(TAG, "playRangeCurrent ayah=${st.currentAyah}/${st.endAyah}, loopsLeft=${st.loopsLeft}")

        try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            when (val ds = resolveAyahDataSource(st.qariId, st.surah, st.currentAyah)) {
                is DataSource.LocalFile -> {
                    mp.setDataSource(ds.file.absolutePath)
                }
                is DataSource.ExternalUri -> {
                    mp.setDataSource(activity, ds.uri)
                }
                is DataSource.Asset -> {
                    mp.setDataSource(ds.afd.fileDescriptor, ds.afd.startOffset, ds.afd.length)
                }
                is DataSource.Remote -> {
                    val q = provider.getQariById(st.qariId)
                    val remoteUrl = if (q != null) provider.getAyahUrl(q, st.surah, st.currentAyah) else ""
                    mp.setDataSource(remoteUrl)
                    toastOnceOnline()
                }
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                it.start()
                isPlaying = true
                isAyahPlaying = true

                activity.currentSurah = st.surah
                activity.currentAyah = st.currentAyah
                activity.runOnUiThread {
                    runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }
                    val page = try { AyahLocator.getPageFor(activity, st.surah, st.currentAyah) } catch (_: Throwable) { -1 }
                    if (page in 1..604 && page != activity.currentPage) {
                        runCatching { activity.navigateToPageFromAudio(page, true) }
                    }
                }

                val ayahText = supportHelper.getAyahTextFromJson(st.surah, st.currentAyah)
                supportHelper.showOrUpdateAyahBanner(st.surah, st.currentAyah, ayahText)
                supportHelper.showAyahOptionsBar(st.surah, st.currentAyah, ayahText)
                saveLastAyah(st.surah, st.currentAyah)

                val nextAyah = when {
                    st.currentAyah < st.endAyah -> st.currentAyah + 1
                    st.loopsLeft > 1 -> st.startAyah
                    else -> null
                }
                nextAyah?.let { next ->
                    bgHandler.post { prefetchAyah(st.qariId, st.surah, next) }
                }
            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener
                if (suppressAutoNext) {
                    isPlaying = false
                    isAyahPlaying = false
                    return@setOnCompletionListener
                }

                val state = rangeState ?: return@setOnCompletionListener

                if (state.currentAyah < state.endAyah) {
                    state.currentAyah += 1
                    playRangeCurrent()
                    return@setOnCompletionListener
                }

                state.loopsLeft -= 1
                if (state.loopsLeft > 0) {
                    state.currentAyah = state.startAyah
                    playRangeCurrent()
                } else {
                    rangeState = null
                    savedAutoContinueToNextPage?.let { ac -> autoContinueToNextPage = ac }
                    savedAutoContinueToNextPage = null
                    repeatMode = "off"
                    isPlaying = false
                    isAyahPlaying = false
                    activity.runOnUiThread {
                        runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                        supportHelper.hideAyahBanner()
                    }
                }
            }

            mp.setOnErrorListener { _, _, _ ->
                val state = rangeState
                if (state != null) {
                    if (state.currentAyah < state.endAyah) {
                        state.currentAyah += 1
                        playRangeCurrent()
                    } else {
                        state.loopsLeft -= 1
                        if (state.loopsLeft > 0) {
                            state.currentAyah = state.startAyah
                            playRangeCurrent()
                        } else {
                            rangeState = null
                            savedAutoContinueToNextPage?.let { ac -> autoContinueToNextPage = ac }
                            savedAutoContinueToNextPage = null
                            repeatMode = "off"
                            isPlaying = false
                            isAyahPlaying = false
                        }
                    }
                }
                true
            }

            mp.prepareAsync()
        } catch (_: Exception) {
            val state = rangeState
            if (state != null) {
                if (state.currentAyah < state.endAyah) {
                    state.currentAyah += 1
                    playRangeCurrent()
                } else {
                    state.loopsLeft -= 1
                    if (state.loopsLeft > 0) {
                        state.currentAyah = state.startAyah
                        playRangeCurrent()
                    } else {
                        rangeState = null
                        savedAutoContinueToNextPage?.let { ac -> autoContinueToNextPage = ac }
                        savedAutoContinueToNextPage = null
                        repeatMode = "off"
                        isPlaying = false
                        isAyahPlaying = false
                    }
                }
            }
        }
    }

    // ===================== تشغيل عام (صفحات) =====================
    private fun playAt(index: Int, startMs: Int, qariId: String) {
        if (index !in ayahQueue.indices) {
            if (!suppressAutoNext && repeatMode == "page" &&
                pageRepeatCount > 1 && pageRepeatIteration < pageRepeatCount - 1
            ) {
                pageRepeatIteration += 1
                currentIndex = 0
                resumeFromMs = 0
                playAt(0, 0, qariId)
                return
            } else {
                pageRepeatIteration = 0
            }

            activity.runOnUiThread {
                runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                supportHelper.hideAyahBanner()
            }

            if (suppressAutoNext) {
                isPagePlaybackStarting = false
                isPlaying = false
                return
            }

            val next = activity.currentPage + 1
            if (autoContinueToNextPage && next <= 604) {
                prepareAudioQueueForPage(next, qariId, fromStart = true) {
                    currentIndex = 0
                    resumeFromMs = 0
                    if (ayahQueue.isNotEmpty()) {
                        runCatching { activity.navigateToPageFromAudio(next, true) }
                        playAt(0, 0, qariId)
                    } else {
                        isPagePlaybackStarting = false
                        isPlaying = false
                        activity.runOnUiThread {
                            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                            supportHelper.hideAyahBanner()
                        }
                    }
                }
            } else {
                isPagePlaybackStarting = false
                isPlaying = false
            }
            return
        }

        val (_, s, a) = ayahQueue[index]

        activity.currentSurah = s
        activity.currentAyah = a
        runCatching { activity.adapter.highlightAyahOnPage(activity.currentPage, s, a) }

        val token = ++playToken

        try {
            val mp = ensurePlayer()
            clearListeners()
            mp.reset()

            when (val ds = resolveAyahDataSource(qariId, s, a)) {
                is DataSource.LocalFile -> {
                    val started = playPageAyahFast(
                        mediaItem = MediaItem.fromUri(Uri.fromFile(ds.file)),
                        token = token,
                        startMs = startMs,
                        qariId = qariId,
                        surah = s,
                        ayah = a
                    )
                    Log.d("QuranAudioHelper", "Playing OFFLINE(file): ${ds.file.absolutePath}")
                    if (started) return
                    mp.setDataSource(ds.file.absolutePath)
                }
                is DataSource.ExternalUri -> {
                    val started = playPageAyahFast(
                        mediaItem = MediaItem.fromUri(ds.uri),
                        token = token,
                        startMs = startMs,
                        qariId = qariId,
                        surah = s,
                        ayah = a
                    )
                    Log.d("QuranAudioHelper", "Playing OFFLINE(uri): ${ds.uri}")
                    if (started) return
                    mp.setDataSource(activity, ds.uri)
                }
                is DataSource.Asset -> {
                    mp.setDataSource(ds.afd.fileDescriptor, ds.afd.startOffset, ds.afd.length)
                    Log.d("QuranAudioHelper", "Playing OFFLINE(asset): ${ds.debugPath}")
                }
                is DataSource.Remote -> {
                    val q = provider.getQariById(qariId)
                    val remoteUrl = if (q != null) provider.getAyahUrl(q, s, a) else ds.url
                    val started = playPageAyahFast(
                        mediaItem = MediaItem.fromUri(remoteUrl),
                        token = token,
                        startMs = startMs,
                        qariId = qariId,
                        surah = s,
                        ayah = a
                    )
                    toastOnceOnline()
                    Log.d("QuranAudioHelper", "Playing ONLINE(url): $remoteUrl")
                    if (started) return
                    mp.setDataSource(remoteUrl)
                }
            }

            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                if (startMs > 0) it.seekTo(startMs)
                it.start()
                isPagePlaybackStarting = false
                isPlaying = true
                isAyahPlaying = true
                activity.runOnUiThread {
                    runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }
                }

                val ayahText = supportHelper.getAyahTextFromJson(s, a)
                supportHelper.showOrUpdateAyahBanner(s, a, ayahText)
                supportHelper.showAyahOptionsBar(s, a, ayahText)
                saveLastAyah(s, a)

                // جهّز الآية التالية بدل إعادة تنزيل الآية الجاري بثها.
                ayahQueue.getOrNull(currentIndex + 1)?.let { next ->
                    bgHandler.post { prefetchAyah(qariId, next.second, next.third) }
                }
            }

            mp.setOnCompletionListener {
                if (token != playToken) return@setOnCompletionListener
                if (suppressAutoNext || !isPlaying) {
                    isPlaying = false
                    activity.runOnUiThread {
                        runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                        supportHelper.hideAyahBanner()
                    }
                    return@setOnCompletionListener
                }

                if (repeatMode == "ayah") {
                    val key = s to a
                    if (lastRepeatedAyah == key) currentRepeat++ else {
                        currentRepeat = 1; lastRepeatedAyah = key
                    }
                    if (currentRepeat < repeatCount) {
                        resumeFromMs = 0
                        playAt(currentIndex, 0, qariId)
                        return@setOnCompletionListener
                    } else {
                        currentRepeat = 0; lastRepeatedAyah = null
                    }
                }

                resumeFromMs = 0
                currentIndex += 1
                playAt(currentIndex, 0, qariId)
            }

            mp.setOnErrorListener { _, _, _ ->
                resumeFromMs = 0
                currentIndex += 1
                playAt(currentIndex, 0, qariId)
                true
            }

            mp.prepareAsync()
        } catch (_: Exception) {
            Toast.makeText(activity, "تعذر تشغيل التلاوة", Toast.LENGTH_SHORT).show()
            resumeFromMs = 0
            currentIndex += 1
            playAt(currentIndex, 0, qariId)
        }
    }

    /** تشغيل آية محلية أو بعيدة عبر Media3 بمخزن بدء صغير. */
    private fun playPageAyahFast(
        mediaItem: MediaItem,
        token: Long,
        startMs: Int,
        qariId: String,
        surah: Int,
        ayah: Int
    ): Boolean {
        return playFastSource(
            mediaItem = mediaItem,
            token = token,
            startMs = startMs,
            singleControl = false,
            onStarted = {
                if (token == playToken) {
                    isPagePlaybackStarting = false
                    isPlaying = true
                    isAyahPlaying = true
                    activity.runOnUiThread {
                        runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }
                    }

                    val ayahText = supportHelper.getAyahTextFromJson(surah, ayah)
                    supportHelper.showOrUpdateAyahBanner(surah, ayah, ayahText)
                    supportHelper.showAyahOptionsBar(surah, ayah, ayahText)
                    saveLastAyah(surah, ayah)

                    // تنزيل الآية التالية فقط؛ لا نكرر طلب الآية الجاري بثها.
                    ayahQueue.getOrNull(currentIndex + 1)?.let { next ->
                        bgHandler.post { prefetchAyah(qariId, next.second, next.third) }
                    }
                }
            },
            onEnded = {
                if (token == playToken) {
                    if (suppressAutoNext || !isPlaying) {
                        isPlaying = false
                        isAyahPlaying = false
                        activity.runOnUiThread {
                            runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_play) }
                            supportHelper.hideAyahBanner()
                        }
                    } else {
                        var repeated = false
                        if (repeatMode == "ayah") {
                            val key = surah to ayah
                            if (lastRepeatedAyah == key) currentRepeat++ else {
                                currentRepeat = 1
                                lastRepeatedAyah = key
                            }
                            if (currentRepeat < repeatCount) {
                                resumeFromMs = 0
                                playAt(currentIndex, 0, qariId)
                                repeated = true
                            } else {
                                currentRepeat = 0
                                lastRepeatedAyah = null
                            }
                        }

                        if (!repeated) {
                            resumeFromMs = 0
                            currentIndex += 1
                            playAt(currentIndex, 0, qariId)
                        }
                    }
                }
            },
            onFailed = {
                if (token == playToken) {
                    resumeFromMs = 0
                    currentIndex += 1
                    playAt(currentIndex, 0, qariId)
                }
            }
        )
    }

    // ===================== Ayah Changed Listener =====================
    private var ayahChangedListener: ((surah: Int, ayah: Int, text: String?) -> Unit)? = null

    fun setOnAyahChangedListener(listener: (Int, Int, String?) -> Unit) {
        ayahChangedListener = listener
    }

    // 🔥 استدعِ الحدث عند كل آية يتم تشغيلها
    private fun notifyAyahChanged(surah: Int, ayah: Int, text: String?) {
        ayahChangedListener?.invoke(surah, ayah, text)
    }

    // ===================== أوفلاين أولاً (داخلي + خارجي SAF) =====================
    fun clearExternalCache() {
        externalAyahCache.clear()
        externalCacheLoading.clear()
        externalWarmupScheduled.clear()
    }

    fun refreshExternalCache(qariId: String) {
        val cacheKey = safeQari(qariId)
        externalAyahCache.remove(cacheKey)
        externalWarmupScheduled.remove(cacheKey)
        scheduleExternalIndexWarmup(cacheKey)
    }

    private fun existsAnyLocal(qariId: String, surah: Int, ayah: Int): Boolean {
        if (supportHelper.hasOfflineAyahFile(qariId, surah, ayah)) return true
        if (findExternalAyahUri(qariId, surah, ayah) != null) return true
        val n = fileName(surah, ayah)
        return File(qariDirRecitations(qariId), n).exists() ||
                File(qariDirQuranAudio(qariId), n).exists()
    }

    private fun preferredLocalFile(qariId: String, surah: Int, ayah: Int): File =
        File(qariDirRecitations(qariId), fileName(surah, ayah))

    private fun prefetchAyah(qariId: String, surah: Int, ayah: Int) {
        if (supportHelper.hasOfflineAyahFile(qariId, surah, ayah)) return

        val cacheKey = safeQari(qariId)
        if (externalAyahCache[cacheKey]?.containsKey(fileName(surah, ayah)) == true) return

        val qari = provider.getQariById(qariId) ?: return
        supportHelper.downloadOneSilentInBackground(
            provider.getAyahUrl(qari, surah, ayah),
            preferredLocalFile(qariId, surah, ayah)
        )
    }

    // ===================== مسارات التخزين (كلها في externalFilesDir) =====================
    private fun qariDirRecitations(qariId: String): File {
        val safe = safeQari(qariId)
        val appStorage = activity.getExternalFilesDir(null) ?: activity.filesDir
        val dir = File(appStorage, "recitations/$safe")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun qariDirQuranAudio(qariId: String): File {
        val safe = safeQari(qariId)
        val appStorage = activity.getExternalFilesDir(null) ?: activity.filesDir
        val dir = File(appStorage, "quran_audio/$safe")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun safeQari(qariId: String): String =
        qariId.lowercase()
            .replace("\\s+".toRegex(), "_")
            .replace("[^a-z0-9_\\-]".toRegex(), "")

    private fun fileName(surah: Int, ayah: Int): String =
        "%03d%03d.mp3".format(surah, ayah)

    // ===================== Fallback أونلاين (عند الحاجة) =====================
    private fun tryOnlineFallback(
        mp: MediaPlayer,
        qariId: String,
        surah: Int,
        ayah: Int,
        token: Long
    ) {
        try {
            val qari = provider.getQariById(qariId)
            val online = if (qari != null) provider.getAyahUrl(qari, surah, ayah) else ""
            mp.reset()
            mp.setDataSource(online)
            mp.setOnPreparedListener {
                if (token != playToken) return@setOnPreparedListener
                it.start()
                isAyahPlaying = true
                isPlaying = true
                activity.runOnUiThread {
                    runCatching { activity.btnPlayPause.setImageResource(R.drawable.ic_pause) }
                }
            }
            mp.prepareAsync()
            toastOnceOnline()
        } catch (e: Throwable) {
            Log.e("QuranAudioHelper", "Online fallback failed", e)
            Toast.makeText(activity, activity.getString(R.string.audio_error), Toast.LENGTH_SHORT).show()
            stopAll()
        }
    }

    fun stopAllPlaybackAndClearQueue() {
        stopStreamPlayer()
        try { pausePagePlayback() } catch (_: Exception) {}
        try { stopSingleAyah() } catch (_: Exception) {}

        try {
            mediaPlayer?.reset()
            mediaPlayer?.release()
        } catch (_: Exception) {}
        mediaPlayer = null

        clearPendingQueueInternal()
        isPlaying = false
        isAyahPlaying = false
        cancelRangeRepeat()
    }

    private fun clearPendingQueueInternal() {
        synchronized(queuePrepareLock) {
            playGen++
            queuePreparingKey = null
            queuePrepareCallbacks.clear()
        }
        ayahQueue.clear()
        preparedPage = -1
        preparedQariId = ""
        currentIndex = -1
        isPagePlaybackStarting = false
        pausedPlaybackPage = -1
        pausedPlaybackQariId = ""
    }

    @Volatile private var onlineToastShown = false
    private fun toastOnceOnline() {
        if (!onlineToastShown) {
            onlineToastShown = true
            try {
                Toast.makeText(activity, activity.getString(R.string.playing_online_fallback), Toast.LENGTH_SHORT).show()
            } catch (_: Throwable) {}
            bgHandler.postDelayed({ onlineToastShown = false }, 6000L)
        }
    }

    // ====== Helper خارجي لمعرفة مصدر الآية (محلي/أونلاين) ======
    fun resolveAyahSource(surah: Int, ayah: Int, qariId: String): Pair<Boolean, String> {
        if (supportHelper.hasOfflineAyahFile(qariId, surah, ayah)) {
            val f = supportHelper.getOfflineFileForAyah(qariId, surah, ayah)
            return true to f.absolutePath
        }
        findExternalAyahUri(qariId, surah, ayah)?.let { return true to it.toString() }

        val base = provider.getQariById(qariId)?.url?.trimEnd('/') ?: ""
        val name = "%03d%03d.mp3".format(surah, ayah)
        return false to "$base/$name"
    }

    // ===================== دعم SAF الخارجي + Cache =====================
    private fun isExternalSelected(): Boolean {
        val p = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val mode = p.getString("recitation_storage", "internal") ?: "internal"
        val tree = p.getString("pref_tree_uri", null)
        return mode == "external" && !tree.isNullOrEmpty()
    }

    private fun externalRootDoc(): DocumentFile? {
        if (!isExternalSelected()) return null
        val p = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val uriStr = p.getString("pref_tree_uri", null) ?: return null
        return runCatching { DocumentFile.fromTreeUri(activity, Uri.parse(uriStr)) }.getOrNull()
    }

    private fun ensureExternalRecitationsDir(): DocumentFile? {
        val root = externalRootDoc() ?: return null
        root.listFiles().firstOrNull { it.isDirectory && it.name == "recitations" }?.let { return it }
        return if (root.canWrite()) root.createDirectory("recitations") else null
    }

    private fun ensureExternalQariDir(qariId: String): DocumentFile? {
        val parent = ensureExternalRecitationsDir() ?: return null
        val safe = safeQari(qariId)
        parent.listFiles().firstOrNull { it.isDirectory && it.name == safe }?.let { return it }
        return if (parent.canWrite()) parent.createDirectory(safe) else null
    }

    private val externalAyahCache = ConcurrentHashMap<String, Map<String, Uri>>()
    private val externalCacheLoading = ConcurrentHashMap.newKeySet<String>()

    private fun buildExternalCache(qariId: String, force: Boolean = false) {
        val cacheKey = safeQari(qariId)
        if (force) externalAyahCache.remove(cacheKey)
        if (externalAyahCache.containsKey(cacheKey)) return
        if (!externalCacheLoading.add(cacheKey)) return

        try {
            val qdir = ensureExternalQariDir(cacheKey) ?: return
            val map = mutableMapOf<String, Uri>()
            for (file in qdir.listFiles()) {
                if (file.isFile && file.name?.endsWith(".mp3") == true) {
                    map[file.name!!] = file.uri
                }
            }
            externalAyahCache[cacheKey] = map
            val editor = externalIndexPrefs.edit()
            map.forEach { (name, uri) ->
                editor.putString(externalIndexKey(cacheKey, name), uri.toString())
            }
            editor.putBoolean(externalIndexReadyKey(cacheKey), true)
            editor.apply()
            Log.d("QuranAudioHelper", "تم بناء الكاش للقارئ $cacheKey: ${map.size} ملف")
        } catch (e: Exception) {
            Log.e("QuranAudioHelper", "خطأ أثناء بناء الكاش", e)
        } finally {
            externalCacheLoading.remove(cacheKey)
        }
    }

    private fun findExternalAyahUri(qariId: String, s: Int, a: Int): Uri? {
        if (!isExternalSelected()) return null

        val cacheKey = safeQari(qariId)
        val name = fileName(s, a)
        externalIndexPrefs.getString(externalIndexKey(cacheKey, name), null)?.let { saved ->
            return runCatching { Uri.parse(saved) }.getOrNull()
        }
        externalAyahCache[cacheKey]?.get(name)?.let { return it }

        // لا نفحص مجلد SAF أبدًا أثناء ضغطة التشغيل.
        scheduleExternalIndexWarmup(cacheKey)
        return null
    }

    private fun scheduleExternalIndexWarmup(qariId: String) {
        if (!isExternalSelected()) return
        val cacheKey = safeQari(qariId)
        if (externalIndexPrefs.getBoolean(externalIndexReadyKey(cacheKey), false)) return
        if (!externalWarmupScheduled.add(cacheKey)) return

        bgHandler.postDelayed({
            if (isActuallyPlaying()) {
                externalWarmupScheduled.remove(cacheKey)
                scheduleExternalIndexWarmup(cacheKey)
                return@postDelayed
            }
            runCatching {
                externalCacheExecutor.execute {
                    try {
                        buildExternalCache(cacheKey, force = true)
                    } finally {
                        externalWarmupScheduled.remove(cacheKey)
                    }
                }
            }.onFailure { externalWarmupScheduled.remove(cacheKey) }
        }, 4_000L)
    }

    private fun externalIndexKey(qariIdRaw: String, name: String): String {
        val settings = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val tree = settings.getString("pref_tree_uri", "").orEmpty()
        return "${tree.hashCode()}|${safeQari(qariIdRaw)}|$name"
    }

    private fun externalIndexReadyKey(qariIdRaw: String): String {
        val settings = activity.getSharedPreferences("settings", Context.MODE_PRIVATE)
        val tree = settings.getString("pref_tree_uri", "").orEmpty()
        return "ready|${tree.hashCode()}|${safeQari(qariIdRaw)}"
    }

}
