// File: app/src/main/java/com/hag/al_quran/SettingsFragment.kt
package com.hag.al_quran

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.documentfile.provider.DocumentFile
import androidx.fragment.app.Fragment
import com.google.android.material.slider.Slider
import com.hag.al_quran.utils.FontScale

class SettingsFragment : Fragment() {

    companion object {
        // ===== مفاتيح التخزين والإعدادات =====
        const val KEY_STORAGE_MODE = "recitation_storage"      // "internal" | "external"
        const val PREF_TREE_URI    = "pref_tree_uri"           // SAF tree uri
        private const val PREF_FONT = "font"
        private const val DEFAULT_FONT_KEY = "large"
        private const val PREF_KEEP_SCREEN_ON = "keep_screen_on"

        // ملف الـ SharedPreferences
        private const val PREF_FILE = "settings"

        // تفضيل نوع الشبكة (يقرأه PagesDownloadService و QuranSupportHelper)
        private const val PREF_NETWORK = "pref_network_type"   // "WIFI_ONLY" | "MOBILE_ONLY" | "ANY"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var pickDirectoryLauncher: ActivityResultLauncher<Uri?>

    // التخزين
    private var storageGroup: RadioGroup? = null
    private var pickFolderBtn: Button? = null
    private var storageSummaryTv: TextView? = null

    // الشبكة
    private var networkGroup: RadioGroup? = null
    private var rbWifi: RadioButton? = null
    private var rbMobile: RadioButton? = null
    private var rbAny: RadioButton? = null
    private var networkSummaryTv: TextView? = null

    // الخط
    private val idxToKey = arrayOf("xs", "small", "medium", "large", "xl", "xxl")
    private val keyToIdx = mapOf("xs" to 0, "small" to 1, "medium" to 2, "large" to 3, "xl" to 4, "xxl" to 5)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = requireContext().getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

        // مُنتقي مجلد خارجي عبر SAF + إذن دائم
        pickDirectoryLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
            if (uri == null) {
                if (prefs.getString(PREF_TREE_URI, null).isNullOrEmpty()) {
                    prefs.edit().putString(KEY_STORAGE_MODE, "internal").apply()
                    storageGroup?.check(R.id.storage_internal)
                    updateStorageUIVisibility()
                    updateStorageSummary()
                }
                Toast.makeText(requireContext(), "لم يتم اختيار مجلد", Toast.LENGTH_SHORT).show()
                return@registerForActivityResult
            }
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            runCatching { requireContext().contentResolver.takePersistableUriPermission(uri, flags) }

            val rootDoc = DocumentFile.fromTreeUri(requireContext(), uri)
            val recitations = ensureRecitationsDir(rootDoc)
            if (recitations == null || !recitations.canWrite()) {
                Toast.makeText(requireContext(), "المجلد غير قابل للكتابة. اختر بطاقة SD أو مجلد بإذن كتابة.", Toast.LENGTH_LONG).show()
                return@registerForActivityResult
            }

            prefs.edit()
                .putString(KEY_STORAGE_MODE, "external")
                .putString(PREF_TREE_URI, uri.toString())
                .apply()

            updateStorageUIVisibility()
            updateStorageSummary()
            Toast.makeText(requireContext(), "تم اختيار المجلد بنجاح ✅", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_settings, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // شريط علوي (رجوع)
        view.findViewById<androidx.appcompat.widget.Toolbar>(R.id.fragment_toolbar)?.apply {
            setNavigationIcon(R.drawable.ic_arrow_back)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
        }

        // عناصر الخط
        val fontSlider = view.findViewById<Slider>(R.id.fontSlider)
            ?: error("fontSlider غير موجود في fragment_settings.xml")
        val resetBtn = view.findViewById<Button>(R.id.resetOnboardingButton)
            ?: error("resetOnboardingButton غير موجود في fragment_settings.xml")
        val qrImage = view.findViewById<ImageView>(R.id.qrImage) // اختياري

        // عناصر التخزين
        storageGroup     = view.findViewById(R.id.storageGroup)
        pickFolderBtn    = view.findViewById(R.id.pickExternalFolderButton)
        storageSummaryTv = view.findViewById(R.id.storageSummary)

        // عناصر الشبكة
        networkGroup     = view.findViewById(R.id.networkGroup)
        rbWifi           = view.findViewById(R.id.net_wifi_only)
        rbMobile         = view.findViewById(R.id.net_mobile_only)
        rbAny            = view.findViewById(R.id.net_any)
        networkSummaryTv = view.findViewById(R.id.networkSummary)

        // ===== إعداد حجم الخط =====
        val savedFontKey = prefs.getString(PREF_FONT, null) ?: run {
            prefs.edit().putString(PREF_FONT, DEFAULT_FONT_KEY).apply()
            FontScale.saveChoice(requireContext(), DEFAULT_FONT_KEY)
            DEFAULT_FONT_KEY
        }
        fontSlider.value = (keyToIdx[savedFontKey] ?: 3).toFloat()

        fontSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val idx = slider.value.toInt().coerceIn(0, idxToKey.lastIndex)
                val key = idxToKey[idx]
                prefs.edit().putString(PREF_FONT, key).apply()
                FontScale.saveChoice(requireContext(), key)
                Toast.makeText(requireContext(), getString(R.string.font_changed), Toast.LENGTH_SHORT).show()
                requireActivity().recreate()
            }
        })

        // ===== إعادة ضبط سريعة =====
        resetBtn.setOnClickListener {
            prefs.edit()
                .putString(PREF_FONT, DEFAULT_FONT_KEY)
                .putBoolean(PREF_KEEP_SCREEN_ON, false)
                .putString(KEY_STORAGE_MODE, "internal")
                .remove(PREF_TREE_URI)
                .putString(PREF_NETWORK, "WIFI_ONLY")
                .apply()
            FontScale.saveChoice(requireContext(), DEFAULT_FONT_KEY)
            fontSlider.value = (keyToIdx[DEFAULT_FONT_KEY] ?: 3).toFloat()
            updateStorageUIVisibility()
            updateStorageSummary()
            updateNetworkUIFromPref()
            Toast.makeText(requireContext(), getString(R.string.settings_reset), Toast.LENGTH_SHORT).show()
            requireActivity().recreate()
        }

        // ===== وضع التخزين (داخلي/خارجي) =====
        when (prefs.getString(KEY_STORAGE_MODE, "internal")) {
            "external" -> storageGroup?.check(R.id.storage_external)
            else       -> storageGroup?.check(R.id.storage_internal)
        }

        storageGroup?.setOnCheckedChangeListener { _, checkedId ->
            val mode = if (checkedId == R.id.storage_external) "external" else "internal"
            prefs.edit().putString(KEY_STORAGE_MODE, mode).apply()
            updateStorageUIVisibility()
            updateStorageSummary()
            if (mode == "external" && prefs.getString(PREF_TREE_URI, null).isNullOrEmpty()) {
                openFolderPicker()
            }
        }
        pickFolderBtn?.setOnClickListener { openFolderPicker() }
        updateStorageUIVisibility()
        updateStorageSummary()

        // ===== نوع الشبكة للتحميل =====
        updateNetworkUIFromPref()
        networkGroup?.setOnCheckedChangeListener { _, checkedId ->
            val value = when (checkedId) {
                R.id.net_mobile_only -> "MOBILE_ONLY"
                R.id.net_any         -> "ANY"
                else                 -> "WIFI_ONLY"
            }
            prefs.edit().putString(PREF_NETWORK, value).apply()
            updateNetworkSummary()
            Toast.makeText(requireContext(), "تم حفظ التفضيل: ${networkLabel(value)}", Toast.LENGTH_SHORT).show()
        }

        // (اختياري) فتح شاشة QR إن كانت موجودة لديك
        qrImage?.setOnClickListener {
            parentFragmentManager.beginTransaction()
                .replace(R.id.main_content, QRFragment())
                .addToBackStack(null)
                .commit()
        }
    }

    // ===================== مساعدات الشبكة =====================
    private fun updateNetworkUIFromPref() {
        when (prefs.getString(PREF_NETWORK, "WIFI_ONLY")) {
            "MOBILE_ONLY" -> rbMobile?.isChecked = true
            "ANY"         -> rbAny?.isChecked = true
            else          -> rbWifi?.isChecked = true
        }
        updateNetworkSummary()
    }

    private fun updateNetworkSummary() {
        val v = prefs.getString(PREF_NETWORK, "WIFI_ONLY") ?: "WIFI_ONLY"
        networkSummaryTv?.text = "التفضيل الحالي: ${networkLabel(v)}"
    }

    private fun networkLabel(code: String) = when (code) {
        "MOBILE_ONLY" -> "البيانات فقط"
        "ANY"         -> "أي شبكة (واي-فاي أو بيانات)"
        else          -> "واي-فاي فقط"
    }

    // ===================== مساعدات التخزين الخارجي =====================
    private fun openFolderPicker() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                pickDirectoryLauncher.launch(prefs.getString(PREF_TREE_URI, null)?.let { Uri.parse(it) })
            } else {
                Toast.makeText(requireContext(), "يتطلب اختيار مجلد على أندرويد 5.0 فأعلى", Toast.LENGTH_LONG).show()
            }
        } catch (_: Exception) {
            Toast.makeText(requireContext(), "تعذّر فتح مُنتقي المجلد.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateStorageUIVisibility() {
        val isExternal = prefs.getString(KEY_STORAGE_MODE, "internal") == "external"
        pickFolderBtn?.visibility = if (isExternal) View.VISIBLE else View.GONE
    }

    private fun updateStorageSummary() {
        val mode = prefs.getString(KEY_STORAGE_MODE, "internal") ?: "internal"
        val summary = if (mode == "internal") {
            "المكان الحالي: الذاكرة الداخلية للتطبيق (مجلد خاص)."
        } else {
            val treeStr = prefs.getString(PREF_TREE_URI, null)
            if (treeStr.isNullOrEmpty()) {
                "المكان الحالي: تخزين خارجي (لم يتم اختيار مجلد بعد)."
            } else {
                val name = getFolderDisplayName(Uri.parse(treeStr)) ?: "مجلد خارجي"
                "المكان الحالي: $name/recitations"
            }
        }
        storageSummaryTv?.text = summary
    }

    private fun getFolderDisplayName(treeUri: Uri): String? =
        runCatching { DocumentFile.fromTreeUri(requireContext(), treeUri)?.name }.getOrNull()

    /** يتأكد من وجود recitations/ داخل الجذر المختار، أو ينشئه. */
    private fun ensureRecitationsDir(root: DocumentFile?): DocumentFile? {
        if (root == null || !root.isDirectory) return null
        root.listFiles().firstOrNull { it.isDirectory && it.name == "recitations" }?.let { return it }
        return if (root.canWrite()) root.createDirectory("recitations") else null
    }
}
