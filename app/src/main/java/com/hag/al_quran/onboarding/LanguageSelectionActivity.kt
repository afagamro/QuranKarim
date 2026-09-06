// File: app/src/main/java/com/hag/al_quran/onboarding/LanguageSelectionActivity.kt
package com.hag.al_quran.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Parcelable
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.updatePadding
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.hag.al_quran.BaseActivity
import com.hag.al_quran.MainActivity
import com.hag.al_quran.R

class LanguageSelectionActivity : BaseActivity() {

    private lateinit var recycler: RecyclerView
    private lateinit var btnContinue: MaterialButton
    private lateinit var adapter: LanguageAdapter
    private var selectedIndex = 0
    private var layoutManagerState: Parcelable? = null

    // مصدر الفتح
    private val cameFromSettings by lazy {
        intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)
    }
    private val cameFromDrawer by lazy {
        intent.getBooleanExtra(EXTRA_FROM_DRAWER, intent.getBooleanExtra("fromDrawer", false))
    }
    private val launchedForChangeInsideApp get() = cameFromSettings || cameFromDrawer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // ✅ إن لم نكن في وضع "تغيير اللغة من الداخل"
        //    والمستخدم أنهى الـ Onboarding سابقاً → لا نعرض شاشة اللغة
        if (!launchedForChangeInsideApp) {
            val p = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val onboarded = p.getBoolean(KEY_ONBOARDED, false)
            if (onboarded) {
                startMainAndFinish()
                return
            }
        }

        // ✅ لا نرسم تحت الأشرطة (الأشرطة صلبة وثابتة)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, true)

        // ✅ ألوان الأشرطة موحّدة مع الثيم
        window.statusBarColor = ContextCompat.getColor(this, R.color.topBarBg)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.bottomBarBg)

        // ✅ بما أن الخلفية فاتحة، نستخدم أيقونات داكنة
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        setContentView(R.layout.activity_language_selection)

        // Toolbar مع سهم رجوع فقط إن جئنا من الإعدادات/القائمة
        findViewById<MaterialToolbar?>(R.id.toolbar)?.apply {
            title = getString(R.string.choose_language_title)
            if (launchedForChangeInsideApp) {
                navigationIcon =
                    ContextCompat.getDrawable(this@LanguageSelectionActivity, R.drawable.ic_arrow_back)
                setNavigationOnClickListener { handleBack() }
            } else {
                navigationIcon = null
            }
        }

        recycler = findViewById(R.id.recycler)
        btnContinue = findViewById(R.id.btnContinue)

        // Insets: نزود الحافة السفلية حسب شريط التنقل ولوحة المفاتيح
        findViewById<View>(R.id.root_container)?.let { root ->
            ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
                val sys = insets.getInsets(
                    WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime()
                )
                v.updatePadding(bottom = sys.bottom)
                insets
            }
            ViewCompat.requestApplyInsets(root)
        }

        // البيانات + اختيار أولي
        val items = buildLanguagesWithDevice()
        selectedIndex = resolveInitialSelectionIndex(items)

        adapter = LanguageAdapter(items, initiallySelected = selectedIndex) { _, pos ->
            selectedIndex = pos
        }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        btnContinue.setOnClickListener {
            val index = selectedIndex.coerceIn(0, items.lastIndex)
            val chosen = items[index]
            // code = null للخيار الأول → لغة الجهاز
            saveLangAndFinish(chosen.code)
        }

        // رجوع مخصص
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = handleBack()
        })

        // استرجاع حالة التمرير
        if (savedInstanceState != null) {
            selectedIndex = savedInstanceState.getInt(STATE_SELECTED_INDEX, selectedIndex)
            layoutManagerState = savedInstanceState.getParcelable(STATE_LAYOUT_MANAGER)
            layoutManagerState?.let { recycler.layoutManager?.onRestoreInstanceState(it) }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_SELECTED_INDEX, selectedIndex)
        outState.putParcelable(
            STATE_LAYOUT_MANAGER,
            recycler.layoutManager?.onSaveInstanceState()
        )
    }

    /** سلوك زر الرجوع */
    private fun handleBack() {
        if (launchedForChangeInsideApp) {
            setResult(RESULT_CANCELED)
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        } else {
            // أول تشغيل → خروج من التطبيق
            finishAffinity()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        }
    }

    /** يحفظ اللغة ويُنهي الشاشة بالطريقة المناسبة */
    private fun saveLangAndFinish(langCode: String?) {
        val isSystem = langCode.isNullOrBlank()

        val locales = if (isSystem) {
            // لغة الجهاز (لا نحدد Locale خاص)
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(langCode)
        }

        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
            // نخزن "system" في حالة لغة الجهاز، أو كود اللغة (ar/en/...)
            putString(KEY_LANG, if (isSystem) LANG_SYSTEM else langCode!!)
            putBoolean(KEY_ONBOARDED, true)
        }

        AppCompatDelegate.setApplicationLocales(locales)

        if (launchedForChangeInsideApp) {
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_RESULT_LANG, if (isSystem) LANG_SYSTEM else langCode!!)
            )
            finish()
            overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        } else {
            startMainAndFinish()
        }
    }

    private fun startMainAndFinish() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
    }

    /** قائمة اللغات مع خيار لغة الجهاز */
    private fun buildLanguagesWithDevice(): List<LanguageItem> = listOf(
        LanguageItem(null, getString(R.string.device_language_option), "System Default", "🖥️"),
        LanguageItem("ar", "العربية",         "Arabic",     "🇸🇦"),
        LanguageItem("en", "English",          "English",    "🇺🇸"),
        LanguageItem("tr", "Türkçe",           "Turkish",    "🇹🇷"),
        LanguageItem("id", "Bahasa Indonesia", "Indonesian", "🇮🇩"),
        LanguageItem("ur", "اردو",             "Urdu",       "🇵🇰"),
        LanguageItem("fa", "فارسی",            "Persian",    "🇮🇷"),
        LanguageItem("bn", "বাংলা",            "Bengali",    "🇧🇩"),
        LanguageItem("ru", "Русский",          "Russian",    "🇷🇺"),
        LanguageItem("hi", "हिन्दी",           "Hindi",      "🇮🇳"),
        LanguageItem("es", "Español",          "Spanish",    "🇪🇸"),
        LanguageItem("fr", "Français",         "French",     "🇫🇷"),
        LanguageItem("de", "Deutsch",          "German",     "🇩🇪"),
        LanguageItem("zh", "中文",             "Chinese",    "🇨🇳"),
        LanguageItem("ja", "日本語",           "Japanese",   "🇯🇵"),
        LanguageItem("ko", "한국어",           "Korean",     "🇰🇷"),
        LanguageItem("pt", "Português",        "Portuguese", "🇵🇹"),
        LanguageItem("it", "Italiano",         "Italian",    "🇮🇹"),
        LanguageItem("sw", "Kiswahili",        "Swahili",    "🇹🇿"),
        LanguageItem("th", "ไทย",              "Thai",       "🇹🇭")
    )

    /** يحدد العنصر المختار مبدئيًا */
    private fun resolveInitialSelectionIndex(items: List<LanguageItem>): Int {
        val applied = AppCompatDelegate.getApplicationLocales()
        val appliedTag = if (applied.isEmpty) {
            null
        } else {
            applied.toLanguageTags().trim().ifEmpty { null }
        }

        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val savedRaw = prefs.getString(KEY_LANG, null)?.trim()?.ifEmpty { null }

        // لو القيمة "system" نعتبرها لغة جهاز → نرجع للخيار الأول
        val saved = if (savedRaw == LANG_SYSTEM) null else savedRaw

        val target = appliedTag ?: saved // أولوية للمطبق فعلياً
        if (target == null) return 0 // لغة الجهاز

        val idx = items.indexOfFirst { it.code?.equals(target, ignoreCase = true) == true }
        return if (idx >= 0) idx else 0
    }

    companion object {
        // مصادر الفتح
        const val EXTRA_FROM_SETTINGS = "from_settings"
        const val EXTRA_FROM_DRAWER  = "from_drawer"
        // نتيجة اللغة المختارة عند العودة
        const val EXTRA_RESULT_LANG  = "result_lang"

        private const val PREFS = "settings"
        private const val KEY_LANG = "lang"
        private const val KEY_ONBOARDED = "onboarded"

        private const val STATE_SELECTED_INDEX = "state_selected_index"
        private const val STATE_LAYOUT_MANAGER = "state_layout_manager"

        // قيمة خاصة تعني "استخدم لغة النظام"
        private const val LANG_SYSTEM = "system"
    }
}
