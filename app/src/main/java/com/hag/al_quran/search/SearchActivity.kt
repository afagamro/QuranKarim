package com.hag.al_quran.search

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.MenuItem
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.use
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.hag.al_quran.QuranPageActivity
import com.hag.al_quran.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.HashSet
import kotlin.math.max

class SearchActivity : AppCompatActivity(), SearchResultAdapter.Listener {

    enum class LocalSearchMode { PARTIAL, WHOLE }

    private lateinit var root: View
    private lateinit var input: EditText
    private lateinit var btnSearch: View

    private lateinit var pageNumberInput: EditText
    private lateinit var btnGoToPage: View

    private lateinit var progress: ProgressBar
    private lateinit var list: RecyclerView
    private val counterText by lazy { findViewById<TextView?>(R.id.resultCounter) }
    private val counterCard by lazy { findViewById<View?>(R.id.counterCard) }

    private val adapter = SearchResultAdapter(this)
    private val handler = Handler(Looper.getMainLooper())
    private val bg = Executors.newSingleThreadExecutor()
    private val seq = AtomicInteger(0)
    private var debouncePartial: Runnable? = null
    private var debounceWhole: Runnable? = null

    private lateinit var index: SearchIndex
    private lateinit var surahNames: List<String>
    private var indexReady = false

    private var suggestionsAdapter: ArrayAdapter<String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_search)

        setupBackToolbar()

        root = findViewById(R.id.searchRoot) ?: findViewById(android.R.id.content)
        input = findViewById(R.id.searchInput)
        btnSearch = findViewById(R.id.btnSearch)

        pageNumberInput = findViewById(R.id.pageNumberInput)
        btnGoToPage = findViewById(R.id.btnGoToPage)

        progress = findViewById(R.id.progress)
        list = findViewById(R.id.resultsList)

        btnSearch.setOnClickListener { startSearch(input.text.toString(), LocalSearchMode.WHOLE) }

        // ===== تفعيل زر الانتقال برقم الصفحة بكل سلاسة =====
        btnGoToPage.setOnClickListener {
            goToPageAction()
        }

        pageNumberInput.setOnEditorActionListener { _, actionId, event ->
            val isGo = actionId == EditorInfo.IME_ACTION_GO
            val isEnter = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isGo || isEnter) {
                goToPageAction()
                true
            } else false
        }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val top = insets.getInsets(WindowInsetsCompat.Type.statusBars()).top
            v.setPadding(v.paddingLeft, top + 12, v.paddingRight, v.paddingBottom)
            insets
        }

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.setHasFixedSize(true)
        list.itemAnimator = null

        (input as? AutoCompleteTextView)?.let { ac ->
            val history = SearchHistory.load(this)
            suggestionsAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, history)
            ac.setAdapter(suggestionsAdapter)
            ac.threshold = 1
            ac.setOnItemClickListener { _, _, pos, _ ->
                val chosen = suggestionsAdapter?.getItem(pos) ?: return@setOnItemClickListener
                ac.setText(chosen); ac.setSelection(chosen.length)
                startSearch(chosen, LocalSearchMode.WHOLE)
            }
        }

        input.setOnEditorActionListener { _, actionId, event ->
            val isImeSearch = actionId == EditorInfo.IME_ACTION_SEARCH
            val isEnterKey = event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN
            if (isImeSearch || isEnterKey) { startSearch(input.text.toString(), LocalSearchMode.WHOLE); true } else false
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleHybrid(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        onBackPressedDispatcher.addCallback(this) { hideKeyboard(); finish() }

        progress.visibility = View.VISIBLE
        Thread {
            try {
                val ayat = SearchUtils.loadAllAyat(this)
                surahNames = SearchUtils.loadSurahNames(this)
                index = SearchIndex(ayat, includeBasmala = true)
                indexReady = true

                WordIndex.buildIfNeeded(this)

                runOnUiThread { progress.visibility = View.GONE; hideCounter() }
            } catch (_: Exception) {
                runOnUiThread {
                    progress.visibility = View.GONE
                    hideCounter()
                    Toast.makeText(this, "تعذّر تحميل البيانات", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun setupBackToolbar() {
        val tb = findViewById<MaterialToolbar?>(R.id.toolbar) ?: return

        ViewCompat.setLayoutDirection(tb, ViewCompat.LAYOUT_DIRECTION_RTL)
        tb.layoutDirection = View.LAYOUT_DIRECTION_RTL

        tb.navigationIcon = AppCompatResources.getDrawable(this, R.drawable.ic_arrow_back)
        tb.navigationIcon?.setTint(resolveAttrColor(com.google.android.material.R.attr.colorOnSurface))

        tb.title = ""
        tb.setNavigationOnClickListener { hideKeyboard(); finish() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) { hideKeyboard(); finish(); return true }
        return super.onOptionsItemSelected(item)
    }

    private fun resolveAttrColor(attr: Int): Int =
        obtainStyledAttributes(intArrayOf(attr)).use { it.getColor(0, 0xFF000000.toInt()) }

    private fun hideKeyboard() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow((currentFocus ?: View(this)).windowToken, 0)
        } catch (_: Exception) {}
    }

    private fun goToPageAction() {
        val pageStr = pageNumberInput.text.toString().trim()
        if (pageStr.isEmpty()) {
            Toast.makeText(this, "الرجاء إدخال رقم الصفحة", Toast.LENGTH_SHORT).show()
            return
        }

        val pageNum = pageStr.toIntOrNull()
        if (pageNum == null || pageNum !in 1..604) {
            Toast.makeText(this, "رقم الصفحة غير صالح (من 1 إلى 604)", Toast.LENGTH_SHORT).show()
            return
        }

        hideKeyboard()

        val intent = Intent(this, QuranPageActivity::class.java).apply {
            putExtra(QuranPageActivity.EXTRA_TARGET_PAGE, pageNum)
        }
        startActivity(intent)
    }

    private fun scheduleHybrid(q: String) {
        debouncePartial?.let { handler.removeCallbacks(it) }
        debounceWhole?.let { handler.removeCallbacks(it) }

        val trimmed = q.trim()
        if (trimmed.isEmpty()) {
            hideCounter()
            adapter.submitListWithQuery(emptyList(), "")
            return
        }

        debouncePartial = Runnable { startSearch(trimmed, LocalSearchMode.PARTIAL) }
        handler.postDelayed(debouncePartial!!, 100)

        val endsWithBoundary = q.lastOrNull()?.let { isBoundary(it) } == true
        val delayWhole = if (endsWithBoundary) 0L else 200L
        debounceWhole = Runnable { startSearch(trimmed, LocalSearchMode.WHOLE) }
        handler.postDelayed(debounceWhole!!, max(0L, delayWhole))
    }

    private fun isBoundary(c: Char): Boolean =
        c.isWhitespace() || ".,،؛;؟?!()[]{}-_/\\|«»\"'ـ".indexOf(c) >= 0

    private fun startSearch(queryRaw: String, mode: LocalSearchMode) {
        val q = queryRaw.trim()
        if (q.isEmpty()) {
            adapter.submitListWithQuery(emptyList(), "")
            hideCounter()
            return
        }
        if (!indexReady) { Toast.makeText(this, "جاري التجهيز…", Toast.LENGTH_SHORT).show(); return }

        if (mode == LocalSearchMode.WHOLE) progress.visibility = View.VISIBLE
        val mySeq = seq.incrementAndGet()

        bg.submit {
            val normQ = SearchUtils.normalizeArabic(SearchUtils.stripTashkeel(q))
            val hits = when (mode) {
                LocalSearchMode.PARTIAL -> index.searchPartial(q)
                LocalSearchMode.WHOLE   -> index.searchWholeWord(q, useStemming = true)
            }

            var totalOccurrences = 0
            val surahSet = HashSet<Int>()
            val ayahSet  = HashSet<Pair<Int, Int>>()

            for (h in hits) {
                val occ = when (mode) {
                    LocalSearchMode.PARTIAL -> countSubstringOccurrences(h.norm, normQ)
                    LocalSearchMode.WHOLE   -> index.countWholeOccurrences(h.surah, h.ayah, normQ, useStemming = true)
                }
                if (occ > 0) {
                    totalOccurrences += occ
                    surahSet.add(h.surah)
                    ayahSet.add(h.surah to h.ayah)
                }
            }

            val ayahMatches = ayahSet.size
            val surahCount  = surahSet.size

            if (mySeq != seq.get()) return@submit

            runOnUiThread {
                if (mode == LocalSearchMode.WHOLE) progress.visibility = View.GONE

                if (q.isNotBlank() && ayahMatches > 0) {
                    val msg = "وردت \"$q\" ${toArabic(totalOccurrences)} مرة في " +
                            "${toArabic(ayahMatches)} آية ضمن ${toArabic(surahCount)} سورة"
                    showCounter(msg)
                } else {
                    hideCounter()
                    if (mode == LocalSearchMode.WHOLE) {
                        Toast.makeText(this, "لا نتائج لـ \"$q\"", Toast.LENGTH_SHORT).show()
                    }
                }

                val uiList = hits.map { a ->
                    val name = surahNames.getOrNull(a.surah - 1) ?: "سورة ${a.surah}"
                    val ayahForUi = if (a.ayah == 0) 1 else a.ayah
                    SearchResultItem(
                        surah = a.surah, ayah = ayahForUi, page = a.page,
                        surahName = name, text = a.text
                    )
                }

                adapter.submitListWithQuery(uiList, q)
                list.scheduleLayoutAnimation()

                if (mode == LocalSearchMode.WHOLE) {
                    (input as? AutoCompleteTextView)?.let { ac ->
                        SearchHistory.save(this, q)
                        val newList = SearchHistory.load(this)
                        suggestionsAdapter?.clear()
                        suggestionsAdapter?.addAll(newList)
                        suggestionsAdapter?.notifyDataSetChanged()
                        ac.post { if (ac.text.isNotEmpty()) ac.showDropDown() }
                    }
                }

                if (counterText == null && ayahMatches > 0) {
                    Toast.makeText(this, "عدد النتائج: ${toArabic(ayahMatches)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun countSubstringOccurrences(text: String, pattern: String): Int {
        if (pattern.isEmpty() || text.isEmpty()) return 0
        var i = 0; var c = 0
        while (true) {
            val p = text.indexOf(pattern, i)
            if (p < 0) break
            c++; i = p + pattern.length
        }
        return c
    }

    private fun showCounter(text: String) {
        counterText?.apply {
            this.text = text
            if (visibility != View.VISIBLE) {
                alpha = 0f
                visibility = View.VISIBLE
                counterCard?.visibility = View.VISIBLE
                animate().alpha(1f).setDuration(120).start()
            } else {
                counterCard?.visibility = View.VISIBLE
            }
        }
    }

    private fun hideCounter() {
        val tv = counterText ?: return
        tv.animate().alpha(0f).setDuration(100).withEndAction {
            tv.visibility = View.GONE
            tv.alpha = 1f
            counterCard?.visibility = View.GONE
        }.start()
    }

    override fun onResultClick(item: SearchResultItem) {
        val query = input.text?.toString()?.trim().orEmpty()
        val pageFromLocator = AyahLocator.getPageFor(this, item.surah, item.ayah)
        val finalPage = if (pageFromLocator > 0) pageFromLocator else item.page

        val intent = Intent(this, QuranPageActivity::class.java).apply {
            putExtra(QuranPageActivity.EXTRA_TARGET_SURAH, item.surah)
            putExtra(QuranPageActivity.EXTRA_TARGET_AYAH,  item.ayah)
            putExtra(QuranPageActivity.EXTRA_QUERY,        query)
            if (finalPage > 0) putExtra(QuranPageActivity.EXTRA_TARGET_PAGE, finalPage)
        }
        startActivity(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        debouncePartial?.let { handler.removeCallbacks(it) }
        debounceWhole?.let { handler.removeCallbacks(it) }
        bg.shutdownNow()
    }

    private fun toArabic(n: Int): String {
        val d = charArrayOf('٠','١','٢','٣','٤','٥','٦','٧','٨','٩')
        return n.toString().map { d[it - '0'] }.joinToString("")
    }
}