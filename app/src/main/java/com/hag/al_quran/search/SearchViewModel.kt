package com.hag.al_quran.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = SearchRepository(app)

    data class UiState(
        val query: String = "",
        val options: SearchOptions = SearchOptions(),
        val isLoading: Boolean = false,
        val totalCount: Int = 0,
        val results: List<SearchResultItem> = emptyList()
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            _state
                .debounce { state ->
                    // تقليل زمن الانتظار جداً لتصبح الاستجابة فورية (100 ميلي ثانية)
                    if (state.query.isBlank()) 0L else 100L
                }
                .distinctUntilChanged { old, new ->
                    old.query == new.query && old.options == new.options
                }
                .flatMapLatest { st ->
                    kotlinx.coroutines.flow.flow {
                        val q = st.query.trim()
                        if (q.isBlank()) {
                            emit(st.copy(results = emptyList(), totalCount = 0, isLoading = false))
                            return@flow
                        }

                        // إظهار النتائج مباشرة بدون شاشة تحميل مزعجة للبحث السريع
                        val res = repo.search(q, st.options)
                        emit(st.copy(results = res, totalCount = res.size, isLoading = false))
                    }
                }
                .flowOn(Dispatchers.Default) // التنفيذ على خيوط الخلفية المخصصة للمعالجة السريعة
                .collect { newSt ->
                    _state.value = newSt
                }
        }
    }

    fun setQuery(q: String) {
        _state.update { it.copy(query = q) }
    }

    fun setMode(mode: SearchOptions.Mode) {
        _state.update { it.copy(options = it.options.copy(mode = mode)) }
    }

    fun setExactTashkeel(enabled: Boolean) {
        _state.update { it.copy(options = it.options.copy(exactTashkeel = enabled)) }
    }
}