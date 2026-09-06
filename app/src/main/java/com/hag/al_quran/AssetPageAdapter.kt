package com.hag.al_quran

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.RectF
import android.os.SystemClock
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import androidx.recyclerview.widget.RecyclerView
import com.github.chrisbanes.photoview.PhotoView
import com.github.chrisbanes.photoview.PhotoViewAttacher
import com.hag.al_quran.ui.PageImageLoader
import com.hag.al_quran.utils.AyahHighlightView
import com.hag.al_quran.utils.Seg

// ================= TYPES =================
typealias AyahBounds = AyahBoundsRepo

data class Roi(
    val l: Float,
    val t: Float,
    val r: Float,
    val b: Float
)

// ================= PAGE GEOMETRY =================
/** نظام الإحداثيات الذي بُني عليه ayah_bounds_all.json. */
private const val BOUNDS_BASE_W = 290f
private const val BOUNDS_BASE_H = 428f

/**
 * مساحة أسطر النص داخل صورة صفحة المصحف العادية ذات 15 سطرًا.
 * كانت الشفرة السابقة تستنتجها من الحدود نفسها، فكانت النتيجة كامل الصورة
 * بما فيها الإطار والزخرفة، ولذلك كان التظليل مزاحًا وممتدًا أكثر من النص.
 */
private val STANDARD_TEXT_ROI = Roi(
    l = 0.053f,
    t = 0.033f,
    r = 0.947f,
    b = 0.950f
)

/**
 * بيانات الحدود تمثل حيز السطر كاملاً، بينما الحروف مع التشكيل تشغل جزءاً
 * أصغر قليلاً منه. تقليل الارتفاع حول مركز السطر يمنع التظليل من ملامسة
 * السطر السابق أو التالي، مع إبقاء جميع الحركات داخل التظليل.
 */
private const val STANDARD_LINE_HEIGHT_FILL = 0.88f

/** هامش دقيق للصفحتين المزخرفتين؛ إحداثياتهما مقاسة من الصورة الأصلية. */
private const val DECORATED_INSET_X_PX = 3f
private const val DECORATED_INSET_Y_PX = 4f

private fun pxRect(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    imageW: Float,
    imageH: Float
): RectF = RectF(
    (left + DECORATED_INSET_X_PX) / imageW,
    (top + DECORATED_INSET_Y_PX) / imageH,
    (right - DECORATED_INSET_X_PX) / imageW,
    (bottom - DECORATED_INSET_Y_PX) / imageH
)

/**
 * الصفحتان 1 و2 لا تستخدمان تخطيط 15 سطرًا، بل تصميمًا زخرفيًا خاصًا.
 * لذلك نطابق كل آية مباشرة بمكانها الحقيقي داخل الصورة.
 */
private fun decoratedPageNormalizedRects(
    page: Int,
    surah: Int,
    ayah: Int
): List<RectF>? {
    return when (page) {
        1 -> {
            if (surah != 1) return emptyList()
            when (ayah) {
                // البسملة، ثم علامتها في بداية السطر التالي.
                1 -> listOf(
                    pxRect(142f, 198f, 452f, 270f, 600f, 949f),
                    pxRect(430f, 280f, 484f, 346f, 600f, 949f)
                )
                // الحمد لله رب العالمين، ثم علامة الآية 2 في السطر التالي.
                2 -> listOf(
                    pxRect(90f, 272f, 430f, 346f, 600f, 949f),
                    pxRect(482f, 342f, 536f, 414f, 600f, 949f)
                )
                // الرحمن الرحيم + علامة الآية 3، مع استبعاد علامة الآية 2.
                3 -> listOf(pxRect(210f, 342f, 482f, 414f, 600f, 949f))
                // مالك يوم | الدين + علامة الآية 4 في بداية السطر التالي.
                4 -> listOf(
                    pxRect(44f, 342f, 210f, 414f, 600f, 949f),
                    pxRect(408f, 416f, 560f, 498f, 600f, 949f)
                )
                // إياك نعبد وإياك نستعين، ثم علامة الآية 5.
                5 -> listOf(
                    pxRect(38f, 416f, 408f, 498f, 600f, 949f),
                    pxRect(482f, 494f, 552f, 576f, 600f, 949f)
                )
                // اهدنا الصراط المستقيم + علامة الآية 6.
                6 -> listOf(pxRect(162f, 494f, 482f, 576f, 600f, 949f))
                // صراط | الذين أنعمت... | عليهم ولا الضالين + علامة الآية 7.
                7 -> listOf(
                    pxRect(42f, 494f, 162f, 576f, 600f, 949f),
                    pxRect(38f, 572f, 562f, 660f, 600f, 949f),
                    pxRect(158f, 658f, 514f, 750f, 600f, 949f)
                )
                else -> emptyList()
            }
        }

        2 -> {
            if (surah != 2) return emptyList()
            when (ayah) {
                // الم + علامة الآية 1، دون إدخال أول كلمات الآية التالية.
                1 -> listOf(pxRect(372f, 274f, 552f, 342f, 600f, 933f))
                2 -> listOf(
                    pxRect(64f, 274f, 372f, 342f, 600f, 933f),
                    pxRect(284f, 340f, 552f, 412f, 600f, 933f)
                )
                3 -> listOf(
                    pxRect(56f, 340f, 284f, 412f, 600f, 933f),
                    pxRect(56f, 410f, 552f, 478f, 600f, 933f),
                    pxRect(478f, 466f, 528f, 538f, 600f, 933f)
                )
                4 -> listOf(
                    pxRect(56f, 466f, 478f, 538f, 600f, 933f),
                    pxRect(142f, 534f, 552f, 606f, 600f, 933f)
                )
                5 -> listOf(
                    pxRect(56f, 534f, 142f, 606f, 600f, 933f),
                    pxRect(66f, 602f, 542f, 674f, 600f, 933f),
                    pxRect(226f, 660f, 432f, 734f, 600f, 933f)
                )
                else -> emptyList()
            }
        }

        else -> null
    }
}

private fun mapNormalizedRectToView(
    displayRect: RectF,
    normalized: RectF,
    cal: PageCal
): RectF {
    val rawLeft = displayRect.left + normalized.left * displayRect.width()
    val rawTop = displayRect.top + normalized.top * displayRect.height()
    val rawRight = displayRect.left + normalized.right * displayRect.width()
    val rawBottom = displayRect.top + normalized.bottom * displayRect.height()

    val cx = (rawLeft + rawRight) / 2f
    val cy = (rawTop + rawBottom) / 2f
    val halfW = (rawRight - rawLeft) / 2f * cal.scaleXFix
    val halfH = (rawBottom - rawTop) / 2f * cal.scaleYFix

    return RectF(
        (cx - halfW + cal.offX).coerceAtLeast(displayRect.left),
        (cy - halfH + cal.offY).coerceAtLeast(displayRect.top),
        (cx + halfW + cal.offX).coerceAtMost(displayRect.right),
        (cy + halfH + cal.offY).coerceAtMost(displayRect.bottom)
    )
}

// ================= MAP SEG TO RECT =================
/**
 * تحويل Seg إلى مستطيل على الشاشة.
 *
 * الإصلاح الأساسي هنا:
 * - الـ ROI يمثل جزءاً من الصورة الكاملة.
 * - seg.x / seg.y مقاسان داخل مساحة النص، وليس داخل الصورة الكاملة.
 * - لذلك نحولهما مباشرة داخل مستطيل النص بعد استبعاد الإطار والزخرفة.
 * - ثم نطبّق معايرة الصفحة من CalibrationStore.
 */
private fun mapSegToViewRect(
    attacher: PhotoViewAttacher,
    seg: Seg,
    baseW: Float,
    baseH: Float,
    roi: Roi,
    cal: PageCal
): RectF {
    val dr = attacher.displayRect ?: return RectF()

    val contentLeft = dr.left + roi.l * dr.width()
    val contentTop = dr.top + roi.t * dr.height()
    val contentRight = dr.left + roi.r * dr.width()
    val contentBottom = dr.top + roi.b * dr.height()

    val contentW = contentRight - contentLeft
    val contentH = contentBottom - contentTop

    if (contentW <= 0f || contentH <= 0f) return RectF()

    val sx = contentW / baseW
    val sy = contentH / baseH

    val rawLeft = contentLeft + seg.x * sx
    val rawTop = contentTop + seg.y * sy
    val rawRight = contentLeft + (seg.x + seg.w) * sx
    val rawBottom = contentTop + (seg.y + seg.h) * sy

    val cx = (rawLeft + rawRight) / 2f
    val cy = (rawTop + rawBottom) / 2f

    // معايرة الحجم حول المركز حتى لا ينحرف الطرف المقابل.
    val halfW = (rawRight - rawLeft) / 2f * cal.scaleXFix
    val halfH = (rawBottom - rawTop) / 2f * cal.scaleYFix *
            STANDARD_LINE_HEIGHT_FILL

    // معايرة الإزاحة بالبكسل على الشاشة.
    var left = cx - halfW + cal.offX
    var top = cy - halfH + cal.offY
    var right = cx + halfW + cal.offX
    var bottom = cy + halfH + cal.offY

    // تمديد خفيف فقط.
    val expandX = (right - left) * 0.008f

    left -= expandX
    right += expandX

    // لا نتجاوز ROI.
    left = left.coerceAtLeast(contentLeft)
    right = right.coerceAtMost(contentRight)
    top = top.coerceAtLeast(contentTop)
    bottom = bottom.coerceAtMost(contentBottom)

    return RectF(left, top, right, bottom)
}

// ================= ADAPTER =================
class AssetPageAdapter(
    private val context: Context,
    private val pages: List<String>,
    private val realPageNumber: Int,
    private val onAyahClick: (surah: Int, ayah: Int, ayahText: String) -> Unit,
    private val onImageTap: () -> Unit,
    private val onNeedPagesDownload: () -> Unit = {},
    private var topPaddingPx: Int = 0
) : RecyclerView.Adapter<AssetPageAdapter.PageViewHolder>() {

    init {
        setHasStableIds(true)
    }

    /** يمكن استدعاؤها من Activity لتحديث المسافة العلوية الثابتة. */
    fun setFixedTopPadding(paddingPx: Int) {
        if (paddingPx != topPaddingPx) {
            topPaddingPx = paddingPx
            notifyDataSetChanged()
        }
    }

    var selectedAyah: Pair<Int, Int>? = null

    private val ayahBoundsMap by lazy {
        BoundsRepo.loadBoundsMap(context)
    }

    private val selectionByPage =
        mutableMapOf<Int, Pair<Int, Int>?>()

    private fun pageToIndex(page: Int): Int =
        (page - 1).coerceIn(0, pages.size - 1)

    fun highlightAyahOnPage(
        page: Int,
        surah: Int,
        ayah: Int
    ) {
        selectionByPage[page] = surah to ayah
        notifyItemChanged(pageToIndex(page))
    }

    fun clearHighlightOnPage(page: Int) {
        selectionByPage.remove(page)
        notifyItemChanged(pageToIndex(page))
    }

    class PageViewHolder(
        itemView: View,
        val photoView: PhotoView,
        val overlay: AyahHighlightView
    ) : RecyclerView.ViewHolder(itemView)

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int
    ): PageViewHolder {
        val scroll = ScrollView(parent.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            overScrollMode = ScrollView.OVER_SCROLL_NEVER
            setPadding(0, topPaddingPx, 0, 0)
            clipToPadding = false
        }

        val root = FrameLayout(parent.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val photo = PhotoView(parent.context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setZoomable(false)
            isClickable = true
        }

        val overlay = AyahHighlightView(parent.context).apply {
            id = View.generateViewId()
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isClickable = false
            isFocusable = false
            setBackgroundColor(Color.TRANSPARENT)
            // بدون هالة خارج المستطيل حتى يطابق التظليل حدود الآية تماماً.
            setFeatherPx(0f)
            setRects(emptyList())
            setOnTouchListener { _, _ -> false }
        }

        root.addView(photo)
        root.addView(overlay)
        scroll.addView(root)

        return PageViewHolder(scroll, photo, overlay)
    }

    override fun getItemCount(): Int = pages.size

    override fun getItemId(position: Int): Long {
        val pageNumber = if (realPageNumber == 0) {
            position + 1
        } else {
            realPageNumber
        }
        return pageNumber.toLong()
    }

    // =============== HELPERS ===============

    /** مطابقة ذكية لمعالجة فرق البسملة ±1. */
    private fun resolveAyahBounds(
        boundsList: List<AyahBounds>,
        surah: Int,
        ayah: Int
    ): AyahBounds? {
        boundsList.firstOrNull {
            it.sura_id == surah && it.aya_id == ayah
        }?.let { return it }

        if (surah != 9) {
            boundsList.firstOrNull {
                it.sura_id == surah && it.aya_id == ayah + 1
            }?.let { return it }

            if (ayah > 1) {
                boundsList.firstOrNull {
                    it.sura_id == surah && it.aya_id == ayah - 1
                }?.let { return it }
            }
        }

        val sameSurah = boundsList.filter {
            it.sura_id == surah
        }

        if (sameSurah.isNotEmpty()) {
            return sameSurah.minByOrNull {
                kotlin.math.abs(it.aya_id - ayah)
            }
        }

        return null
    }

    /** ننتظر حتى تجهز displayRect. */
    private fun waitForDisplayRect(
        pv: PhotoView,
        tries: Int = 12,
        delayMs: Long = 32L,
        ready: () -> Unit
    ) {
        fun check(left: Int) {
            val dr = pv.attacher.displayRect
            val ok = dr != null &&
                    dr.width() > 0f &&
                    dr.height() > 0f &&
                    pv.drawable != null

            if (ok) {
                ready()
            } else if (left > 0) {
                pv.postDelayed(
                    { check(left - 1) },
                    delayMs
                )
            } else {
                ready()
            }
        }

        pv.post { check(tries) }
    }

    /** إعادة ضبط التكبير بحيث تلائم الصورة العرض. */
    private fun resetScaleToFit(pv: PhotoView) {
        val att = pv.attacher
        att.setZoomable(true)

        val min = pv.minimumScale

        if (min > 0f) {
            pv.setScale(min, false)
        } else {
            att.setDisplayMatrix(android.graphics.Matrix())
        }

        att.setZoomable(false)
        pv.scaleType = ImageView.ScaleType.FIT_CENTER
    }

    /** تحويل Seg إلى utils.Seg. */
    private fun toUtilsSeg(
        s: com.hag.al_quran.Seg
    ): com.hag.al_quran.utils.Seg =
        com.hag.al_quran.utils.Seg(
            s.x,
            s.y,
            s.w,
            s.h
        )

    // =============== BIND ===============

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(
        holder: PageViewHolder,
        position: Int
    ) {
        val pageNumber = if (realPageNumber == 0) {
            position + 1
        } else {
            realPageNumber
        }

        holder.photoView.scaleType =
            ImageView.ScaleType.FIT_CENTER

        PageImageLoader.load(
            context = context,
            pageNumber = pageNumber,
            into = holder.photoView
        )

        NightMode.applyInvert(
            holder.photoView,
            context
        )

        waitForDisplayRect(holder.photoView) {
            resetScaleToFit(holder.photoView)

            val att = holder.photoView.attacher
            val boundsList = ayahBoundsMap[pageNumber] ?: emptyList()

            val baseW = BOUNDS_BASE_W
            val baseH = BOUNDS_BASE_H
            val roi = STANDARD_TEXT_ROI

            // تحميل المعايرة الخاصة بالصفحة.
            // إذا لم توجد معايرة للصفحة سيأخذ global تلقائياً.
            val cal = CalibrationStore.loadForPage(
                context,
                pageNumber
            )

            val isNight = (
                    context.resources.configuration.uiMode
                            and Configuration.UI_MODE_NIGHT_MASK
                    ) == Configuration.UI_MODE_NIGHT_YES

            val highlightColor = if (isNight) {
                Color.argb(110, 80, 220, 140)
            } else {
                Color.argb(100, 52, 199, 89)
            }

            holder.overlay.setColor(highlightColor)

            fun toScreenRects(
                ab: AyahBounds?
            ): List<RectF> {
                if (ab == null) return emptyList()

                val decorated = decoratedPageNormalizedRects(
                    page = pageNumber,
                    surah = ab.sura_id,
                    ayah = ab.aya_id
                )

                if (decorated != null) {
                    val dr = att.displayRect ?: return emptyList()
                    return decorated.map { normalized ->
                        // إحداثيات الصفحتين 1 و2 مقاسة من صورهما الأصلية؛
                        // لا نطبق عليها المعايرة العامة الخاصة بصفحات 15 سطراً.
                        mapNormalizedRectToView(dr, normalized, PageCal())
                    }
                }

                return ab.segs.map { seg ->
                    mapSegToViewRect(
                        attacher = att,
                        seg = toUtilsSeg(seg),
                        baseW = baseW,
                        baseH = baseH,
                        roi = roi,
                        cal = cal
                    )
                }
            }

            var selected: AyahBounds? = null

            selectionByPage[pageNumber]?.let { (s, a) ->
                selected = resolveAyahBounds(
                    boundsList,
                    s,
                    a
                )
                holder.overlay.setRects(
                    toScreenRects(selected)
                )
            } ?: holder.overlay.setRects(
                emptyList()
            )

            // نقرة: إظهار/إخفاء الأشرطة | ضغطة مطولة: اختيار آية + Haptic.
            var lastToggleAt = 0L
            val tapDebounceMs = 400L

            val detector = GestureDetector(
                holder.photoView.context,
                object : GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: MotionEvent): Boolean = true

                    override fun onSingleTapConfirmed(
                        e: MotionEvent
                    ): Boolean {
                        val now = SystemClock.uptimeMillis()

                        if (now - lastToggleAt < tapDebounceMs) {
                            return true
                        }

                        lastToggleAt = now
                        onImageTap()
                        return true
                    }

                    override fun onLongPress(
                        e: MotionEvent
                    ) {
                        // الاختيار يعتمد على نفس المستطيلات المرسومة فعليًا؛
                        // لذلك يظل دقيقًا بعد المعايرة وفي الصفحتين المزخرفتين.
                        val hit = boundsList.firstOrNull { ab ->
                            toScreenRects(ab).any { rect -> rect.contains(e.x, e.y) }
                        }

                        if (hit != null) {
                            holder.itemView.performHapticFeedback(
                                HapticFeedbackConstants.LONG_PRESS
                            )

                            val oldR = toScreenRects(selected)

                            selected = hit
                            selectionByPage[pageNumber] =
                                hit.sura_id to hit.aya_id

                            selectedAyah =
                                hit.sura_id to hit.aya_id

                            val newR = toScreenRects(selected)

                            if (
                                oldR.isNotEmpty() &&
                                newR.isNotEmpty() &&
                                oldR.size == newR.size
                            ) {
                                holder.overlay.animateTo(
                                    newR,
                                    160
                                )
                            } else {
                                holder.overlay.setRects(newR)
                            }

                            onAyahClick(
                                hit.sura_id,
                                hit.aya_id,
                                BoundsRepo.getAyahText(
                                    context,
                                    hit.sura_id,
                                    hit.aya_id
                                )
                            )
                        }
                    }
                }
            )

            holder.photoView.setOnPhotoTapListener(null)
            holder.photoView.setOnViewTapListener(null)

            holder.photoView.setOnTouchListener { _, ev ->
                detector.onTouchEvent(ev)
                false
            }

            var firstMatrix = true

            att.setOnMatrixChangeListener {
                if (firstMatrix) {
                    firstMatrix = false

                    val cur = holder.photoView.scale
                    val min = holder.photoView.minimumScale

                    if (cur - min > 0.001f) {
                        holder.photoView.post {
                            resetScaleToFit(holder.photoView)
                        }
                    }
                }

                holder.overlay.setRects(
                    toScreenRects(selected)
                )
            }
        }
    }

    override fun onViewAttachedToWindow(
        holder: PageViewHolder
    ) {
        super.onViewAttachedToWindow(holder)

        holder.photoView.post {
            resetScaleToFit(holder.photoView)
        }
    }

    override fun onViewRecycled(
        holder: PageViewHolder
    ) {
        try {
            holder.overlay.setRects(emptyList())
            holder.photoView.scaleType =
                ImageView.ScaleType.FIT_CENTER
            resetScaleToFit(holder.photoView)
        } finally {
            super.onViewRecycled(holder)
        }
    }
}
