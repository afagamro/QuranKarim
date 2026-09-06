// File: app/src/main/java/com/hag/al_quran/ui/PageImageLoader.kt
package com.hag.al_quran.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import androidx.viewpager2.widget.ViewPager2
import com.bumptech.glide.Glide
import com.bumptech.glide.Priority
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.request.target.Target
import com.hag.al_quran.R
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * مسؤول عن تحميل صفحات المصحف:
 *
 * - عرض الصفحة من الأصول / من الملفات المحلية / من الإنترنت.
 * - تنزيل جميع الصفحات وحفظها كملفات حقيقية داخل التطبيق.
 * - تحميل مسبق (Prefetch) مع عدّاد تقدم.
 *
 * مصدر الصفحات:
 * GitHub:
 * https://github.com/afagamro/quran-pages
 */
object PageImageLoader {

    // ============================================================
    // حالة اكتمال تحميل جميع الصفحات
    // ============================================================

    @Volatile
    var PAGES_FULLY_CACHED: Boolean = false

    // عدد صفحات المصحف
    private const val TOTAL_PAGES = 604

    // ============================================================
    // ThreadPool لتحميل الصفحات بالتوازي
    // ============================================================

    private val downloadExecutor = Executors.newFixedThreadPool(6)

    // ============================================================
    // إعداد مسارات GitHub
    // ============================================================

    /**
     * مستودع GitHub:
     *
     * https://github.com/afagamro/quran-pages
     *
     * الصور موجودة في جذر المستودع:
     *
     * page_1.webp
     * page_2.webp
     * page_3.webp
     * ...
     * page_604.webp
     */

    private const val REMOTE_BASE_RAW =
        "https://raw.githubusercontent.com/afagamro/quran-pages/main"

    private const val REMOTE_BASE_CDN =
        "https://cdn.jsdelivr.net/gh/afagamro/quran-pages@main"

    // ============================================================
    // أول 3 صفحات من Android Assets
    // ============================================================

    private fun localAssetFor(page: Int) =
        "file:///android_asset/pages/page_${page}.webp"

    // ============================================================
    // روابط الصفحات البعيدة
    // ============================================================

    private fun primaryUrlFor(page: Int): String =
        "$REMOTE_BASE_RAW/page_${page}.webp"

    private fun fallbackUrlFor(page: Int): String =
        "$REMOTE_BASE_CDN/page_${page}.webp"

    private fun cacheBustUrlFor(page: Int): String =
        "${primaryUrlFor(page)}?t=${System.currentTimeMillis()}"

    // ============================================================
    // مجلد تخزين صفحات المصحف داخل مساحة التطبيق
    // ============================================================

    private fun getPagesDir(context: Context): File {
        return File(context.filesDir, "quran_pages").apply {
            if (!exists()) {
                mkdirs()
            }
        }
    }

    // ============================================================
    // ملف الصفحة المحلية بعد التنزيل
    // ============================================================

    private fun getLocalPageFile(
        context: Context,
        page: Int
    ): File {
        val dir = getPagesDir(context)
        return File(dir, "page_${page}.webp")
    }

    // ============================================================
    // عدد الصفحات المتوفرة
    // ============================================================

    /**
     * الصفحات 1-3 من Assets
     * والصفحات 4-604 من الملفات المحلية.
     */
    fun getDownloadedCount(context: Context): Int {

        var count = 0

        for (p in 1..TOTAL_PAGES) {

            if (p <= 3 || getLocalPageFile(context, p).exists()) {
                count++
            }
        }

        return count
    }

    // ============================================================
    // هل جميع الصفحات جاهزة للعمل بدون إنترنت؟
    // ============================================================

    /**
     * الصفحات 1-3 من Assets.
     *
     * الصفحات 4-604 يجب أن تكون موجودة
     * داخل quran_pages.
     */
    fun areAllPagesDownloaded(context: Context): Boolean {

        for (p in 1..TOTAL_PAGES) {

            if (p > 3 && !getLocalPageFile(context, p).exists()) {
                return false
            }
        }

        return true
    }

    // ============================================================
    // أوضاع التحميل
    // ============================================================

    enum class Mode {
        NORMAL,
        FAST_PREVIEW,
        CACHE_ONLY
    }

    // ============================================================
    // تحميل الصفحة للعرض
    // ============================================================

    fun load(
        context: Context,
        pageNumber: Int,
        into: ImageView
    ) =
        loadWithCallbacks(
            context,
            pageNumber,
            into,
            Mode.NORMAL,
            {},
            {},
            {}
        )

    fun load(
        context: Context,
        pageNumber: Int,
        into: ImageView,
        mode: Mode
    ) =
        loadWithCallbacks(
            context,
            pageNumber,
            into,
            mode,
            {},
            {},
            {}
        )

    // ============================================================
    // منطق تحميل الصفحة
    // ============================================================

    /**
     * المنطق:
     *
     * 1) الصفحات 1-3:
     *    من Android Assets.
     *
     * 2) الصفحات 4-604:
     *    - إذا وجد ملف محلي يتم استخدامه.
     *    - إذا لم يوجد ملف محلي يتم تحميله من GitHub.
     *
     * ترتيب الإنترنت:
     *
     * RAW
     * ثم jsDelivr CDN
     * ثم RAW مع cache-bust
     */
    fun loadWithCallbacks(
        context: Context,
        pageNumber: Int,
        into: ImageView,
        mode: Mode = Mode.NORMAL,
        onStart: () -> Unit,
        onReady: () -> Unit,
        onFail: () -> Unit
    ) {

        onStart()

        // ========================================================
        // الصفحات الثلاثة الأولى من Assets
        // ========================================================

        if (pageNumber in 1..3) {

            val model = localAssetFor(pageNumber)

            request(
                context,
                model,
                mode,
                simpleListener(
                    onReady,
                    onFail
                )
            ).into(into)

            return
        }

        // ========================================================
        // محاولة التحميل من الملف المحلي أولًا
        // ========================================================

        val localFile =
            getLocalPageFile(
                context,
                pageNumber
            )

        if (localFile.exists()) {

            request(
                context,
                localFile,
                mode,
                simpleListener(
                    onReady,
                    onFail
                )
            ).into(into)

            return
        }

        // ========================================================
        // لا يوجد ملف محلي
        // نجرب الإنترنت
        // ========================================================

        val urls = arrayOf(
            primaryUrlFor(pageNumber),
            fallbackUrlFor(pageNumber),
            cacheBustUrlFor(pageNumber)
        )

        tryLoadRemoteRecursively(
            context,
            urls,
            into,
            mode,
            onReady,
            onFail,
            0
        )
    }

    // ============================================================
    // محاولة تحميل الروابط بالتتابع
    // ============================================================

    private fun tryLoadRemoteRecursively(
        context: Context,
        urls: Array<String>,
        into: ImageView,
        mode: Mode,
        onReady: () -> Unit,
        onFail: () -> Unit,
        index: Int
    ) {

        if (index >= urls.size) {

            onFail()

            return
        }

        val url = urls[index]

        request(
            context,
            url,
            mode,
            object : RequestListener<Drawable> {

                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {

                    // تجربة الرابط التالي بعد تأخير بسيط
                    into.postDelayed({

                        tryLoadRemoteRecursively(
                            context,
                            urls,
                            into,
                            mode,
                            onReady,
                            onFail,
                            index + 1
                        )

                    }, 200)

                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {

                    onReady()

                    return false
                }
            }
        ).into(into)
    }

    // ============================================================
    // إعداد Glide
    // ============================================================

    private fun request(
        context: Context,
        model: Any,
        mode: Mode,
        listener: RequestListener<Drawable>
    ) =
        Glide.with(context)
            .load(model)
            .apply(
                RequestOptions()
                    .diskCacheStrategy(
                        DiskCacheStrategy.AUTOMATIC
                    )
                    .onlyRetrieveFromCache(
                        mode == Mode.CACHE_ONLY
                    )
                    .format(
                        DecodeFormat.PREFER_RGB_565
                    )
                    .downsample(
                        DownsampleStrategy.AT_MOST
                    )
                    .placeholder(
                        R.drawable.ic_placeholder_page
                    )
                    .error(
                        R.drawable.ic_error_loading
                    )
                    .timeout(12_000)
            )
            .priority(
                Priority.IMMEDIATE
            )
            .also {

                if (mode == Mode.FAST_PREVIEW) {
                    it.thumbnail(0.35f)
                }
            }
            .transition(
                DrawableTransitionOptions.withCrossFade()
            )
            .listener(listener)

    // ============================================================
    // Listener بسيط
    // ============================================================

    private fun simpleListener(
        onReady: () -> Unit,
        onFail: () -> Unit
    ) =
        object : RequestListener<Drawable> {

            override fun onLoadFailed(
                e: GlideException?,
                model: Any?,
                target: Target<Drawable>,
                isFirstResource: Boolean
            ): Boolean {

                onFail()

                return false
            }

            override fun onResourceReady(
                resource: Drawable,
                model: Any,
                target: Target<Drawable>?,
                dataSource: DataSource,
                isFirstResource: Boolean
            ): Boolean {

                onReady()

                return false
            }
        }

    // ============================================================
    // Prefetch
    // ============================================================

    /**
     * تحميل مسبق للصفحات القريبة من الصفحة الحالية.
     *
     * الافتراضي:
     * صفحتان قبل الصفحة الحالية
     * وصفحتان بعدها.
     */
    fun prefetchAround(
        context: Context,
        page: Int,
        radius: Int = 2
    ) {

        for (
        p in (page - radius)..(page + radius)
        ) {

            if (p !in 1..TOTAL_PAGES) {
                continue
            }

            val model: Any =
                when {

                    // الصفحات 1-3 من Assets
                    p in 1..3 ->
                        localAssetFor(p)

                    // إذا كانت الصفحة موجودة محليًا
                    getLocalPageFile(
                        context,
                        p
                    ).exists() ->
                        getLocalPageFile(
                            context,
                            p
                        )

                    // وإلا من GitHub
                    else ->
                        primaryUrlFor(p)
                }

            Glide.with(context)
                .load(model)
                .diskCacheStrategy(
                    DiskCacheStrategy.AUTOMATIC
                )
                .preload()
        }
    }

    // ============================================================
    // Prefetch + Download
    // ============================================================

    /**
     * تنزيل صفحة واحدة وحفظها كملف حقيقي.
     *
     * الصفحات 1-3 تعتبر جاهزة دائمًا
     * لأنها موجودة داخل Assets.
     */
    fun prefetchPageRetry(
        context: Context,
        page: Int,
        onDone: (Boolean) -> Unit
    ) {

        // الصفحات الثلاثة الأولى من Assets
        if (page in 1..3) {

            onDone(true)

            return
        }

        // الملف المحلي
        val localFile =
            getLocalPageFile(
                context,
                page
            )

        // إذا كان موجودًا وحجمه مناسب
        if (
            localFile.exists() &&
            localFile.length() > 5_000
        ) {

            onDone(true)

            return
        }

        // ========================================================
        // روابط التحميل
        // ========================================================

        val urls = arrayOf(
            primaryUrlFor(page),
            fallbackUrlFor(page),
            cacheBustUrlFor(page)
        )

        // ========================================================
        // التحميل داخل ThreadPool
        // ========================================================

        downloadExecutor.execute {

            val success =
                downloadPageSync(
                    urls,
                    localFile
                )

            onDone(success)
        }
    }

    // ============================================================
    // تنزيل مباشر باستخدام HttpURLConnection
    // ============================================================

    private fun downloadPageSync(
        urls: Array<String>,
        target: File
    ): Boolean {

        for (urlStr in urls) {

            try {

                val url =
                    URL(urlStr)

                val conn =
                    (url.openConnection() as HttpURLConnection)
                        .apply {

                            connectTimeout = 10_000
                            readTimeout = 20_000
                            instanceFollowRedirects = true
                            useCaches = true
                        }

                conn.connect()

                val code =
                    conn.responseCode

                // =================================================
                // التحقق من نجاح HTTP
                // =================================================

                if (code !in 200..299) {

                    conn.disconnect()

                    continue
                }

                // =================================================
                // إنشاء المجلد إذا لم يكن موجودًا
                // =================================================

                target.parentFile?.let { parent ->

                    if (!parent.exists()) {
                        parent.mkdirs()
                    }
                }

                // =================================================
                // نسخ البيانات إلى الملف
                // =================================================

                conn.inputStream.use { input ->

                    FileOutputStream(target).use { output ->

                        val buffer =
                            ByteArray(8 * 1024)

                        while (true) {

                            val len =
                                input.read(buffer)

                            if (len <= 0) {
                                break
                            }

                            output.write(
                                buffer,
                                0,
                                len
                            )
                        }

                        output.flush()
                    }
                }

                conn.disconnect()

                // =================================================
                // التأكد من أن الملف ليس فارغًا
                // =================================================

                if (target.length() > 5_000) {

                    return true

                } else {

                    runCatching {
                        target.delete()
                    }
                }

            } catch (_: Exception) {

                // نجرب الرابط التالي
            }
        }

        // ========================================================
        // فشل جميع الروابط
        // ========================================================

        runCatching {

            if (target.exists()) {
                target.delete()
            }
        }

        return false
    }

    // ============================================================
    // تنزيل جميع الصفحات
    // ============================================================

    fun prefetchAllPages(
        context: Context,
        onProgress: (done: Int, total: Int) -> Unit,
        onFinished: () -> Unit
    ) {

        val done =
            AtomicInteger(0)

        // الصفحات من 1 إلى 604
        for (p in 1..TOTAL_PAGES) {

            prefetchPageRetry(
                context,
                p
            ) {

                val d =
                    done.incrementAndGet()

                onProgress(
                    d,
                    TOTAL_PAGES
                )

                if (d == TOTAL_PAGES) {

                    onFinished()
                }
            }
        }
    }

    // ============================================================
    // بعد اكتمال التحميل
    // ============================================================

    fun handleDownloadCompleted(
        context: Context,
        pageViewPager: ViewPager2?
    ) {

        Handler(
            Looper.getMainLooper()
        ).postDelayed({

            // تحقق حقيقي من الملفات
            PAGES_FULLY_CACHED =
                areAllPagesDownloaded(
                    context
                )

            pageViewPager?.let {

                val current =
                    it.currentItem

                it.post {

                    // تحديث الصفحة الحالية
                    it.adapter?.notifyItemChanged(
                        current
                    )

                    // تحديث الصفحة السابقة
                    if (current > 0) {

                        it.adapter?.notifyItemChanged(
                            current - 1
                        )
                    }

                    // تحديث الصفحة التالية
                    if (
                        current <
                        (it.adapter?.itemCount ?: 1) - 1
                    ) {

                        it.adapter?.notifyItemChanged(
                            current + 1
                        )
                    }
                }
            }

        }, 1000)
    }
}