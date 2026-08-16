package com.threatloom.app.ui.catalogue

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.domain.model.ArticleWithSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CatalogueViewModel @Inject constructor(
    private val articleRepository: ArticleRepository
) : ViewModel() {

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val tagsListAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    private var taggedArticles: List<Pair<ArticleWithSummary, List<String>>> = emptyList()

    private val _availableTags = MutableStateFlow<List<String>>(emptyList())
    val availableTags: StateFlow<List<String>> = _availableTags.asStateFlow()

    private val _selectedTags = MutableStateFlow<Set<String>>(emptySet())
    val selectedTags: StateFlow<Set<String>> = _selectedTags.asStateFlow()

    private val _filteredArticles = MutableStateFlow<List<ArticleWithSummary>>(emptyList())
    val filteredArticles: StateFlow<List<ArticleWithSummary>> = _filteredArticles.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val articles = articleRepository.getTaggedArticles()
                taggedArticles = articles.map { article ->
                    val tags = try {
                        tagsListAdapter.fromJson(article.tags ?: "[]") ?: emptyList()
                    } catch (_: Exception) {
                        emptyList()
                    }
                    article to tags
                }
                _availableTags.value = taggedArticles
                    .flatMap { it.second }
                    .filter { it.startsWith("tlc-") && it != "tlc-unknown" }
                    .distinct()
                    .sorted()
            } catch (_: Exception) {}
            _isLoading.value = false
            applyFilter()
        }
    }

    fun toggleTag(tag: String) {
        _selectedTags.value = if (tag in _selectedTags.value) {
            _selectedTags.value - tag
        } else {
            _selectedTags.value + tag
        }
        applyFilter()
    }

    fun clearSelection() {
        _selectedTags.value = emptySet()
        applyFilter()
    }

    private fun applyFilter() {
        val selected = _selectedTags.value
        _filteredArticles.value = if (selected.isEmpty()) {
            emptyList()
        } else {
            taggedArticles.filter { (_, tags) -> tags.any { it in selected } }.map { it.first }
        }
    }
}
