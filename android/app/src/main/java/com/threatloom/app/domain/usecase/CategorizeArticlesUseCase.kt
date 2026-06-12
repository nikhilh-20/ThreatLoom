package com.threatloom.app.domain.usecase

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import com.threatloom.app.data.repository.ArticleRepository
import com.threatloom.app.domain.category.CategoryRules
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.domain.model.CategoryGroup
import com.threatloom.app.domain.model.SubcategoryGroup
import javax.inject.Inject

class CategorizeArticlesUseCase @Inject constructor(
    private val articleRepository: ArticleRepository
) {
    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val listAdapter = moshi.adapter<List<String>>(
        Types.newParameterizedType(List::class.java, String::class.java)
    )

    suspend fun getCategories(limitPerCategory: Int = 10, sinceDate: String? = null): List<CategoryGroup> {
        val articles = articleRepository.getTaggedArticles(sinceDate)
        val catMap = linkedMapOf<String, LinkedHashMap<Long, ArticleWithSummary>>()

        for (article in articles) {
            val tags = try { listAdapter.fromJson(article.tags ?: "[]") ?: emptyList() } catch (e: Exception) { emptyList() }
            val assigned = mutableSetOf<String>()
            for (tag in tags) {
                val cat = CategoryRules.tagToCategory(tag)
                if (cat != null && cat !in assigned) {
                    assigned.add(cat)
                    catMap.getOrPut(cat) { linkedMapOf() }.putIfAbsent(article.id, article)
                }
            }
        }

        return catMap.entries
            .sortedByDescending { it.value.size }
            .map { (name, arts) ->
                CategoryGroup(
                    name = name,
                    count = arts.size,
                    articles = arts.values.toList().take(limitPerCategory)
                )
            }
    }

    suspend fun getSubcategories(categoryName: String, limitPerSub: Int = 50, sinceDate: String? = null): List<SubcategoryGroup> {
        if (categoryName !in CategoryRules.KNOWN_ENTITIES) return emptyList()

        val articles = getArticlesForCategory(categoryName, sinceDate)
        val subMap = linkedMapOf<String, LinkedHashMap<Long, ArticleWithSummary>>()
        val matchedIds = mutableSetOf<Long>()

        for (article in articles) {
            val tags = try { listAdapter.fromJson(article.tags ?: "[]") ?: emptyList() } catch (e: Exception) { emptyList() }
            for (tag in tags) {
                val cat = CategoryRules.tagToCategory(tag)
                if (cat == categoryName && !CategoryRules.isGenericTag(tag, categoryName)) {
                    val canonical = CategoryRules.canonicalEntityTag(tag.trim().lowercase(), categoryName)
                    subMap.getOrPut(canonical) { linkedMapOf() }.putIfAbsent(article.id, article)
                    matchedIds.add(article.id)
                }
            }
        }

        val result = subMap.entries
            .sortedByDescending { it.value.size }
            .map { (tag, arts) ->
                SubcategoryGroup(
                    tag = tag,
                    displayName = CategoryRules.formatEntityName(tag),
                    count = arts.size,
                    articles = arts.values.toList().take(limitPerSub)
                )
            }
            .toMutableList()

        val generalArticles = articles.filter { it.id !in matchedIds }
        if (generalArticles.isNotEmpty()) {
            result.add(SubcategoryGroup("__general__", "General", generalArticles.size, generalArticles.take(limitPerSub)))
        }

        return result
    }

    suspend fun getArticlesForSubcategory(categoryName: String, subcategoryTag: String, sinceDate: String? = null): List<ArticleWithSummary> {
        val articles = articleRepository.getTaggedArticles(sinceDate)
        val result = mutableListOf<ArticleWithSummary>()
        val seenIds = mutableSetOf<Long>()

        for (article in articles) {
            val tags = try { listAdapter.fromJson(article.tags ?: "[]") ?: emptyList() } catch (e: Exception) { emptyList() }
            for (tag in tags) {
                val cat = CategoryRules.tagToCategory(tag)
                if (cat == categoryName && article.id !in seenIds) {
                    val canonical = CategoryRules.canonicalEntityTag(tag.trim().lowercase(), categoryName)
                    if (subcategoryTag == "__general__") {
                        // General bucket: articles that don't match any specific subcategory
                        if (CategoryRules.isGenericTag(tag, categoryName)) {
                            seenIds.add(article.id)
                            result.add(article)
                            break
                        }
                    } else if (canonical == subcategoryTag) {
                        seenIds.add(article.id)
                        result.add(article)
                        break
                    }
                }
            }
        }
        return result
    }

    suspend fun getArticlesForCategory(categoryName: String, sinceDate: String? = null): List<ArticleWithSummary> {
        val articles = articleRepository.getTaggedArticles(sinceDate)
        val result = mutableListOf<ArticleWithSummary>()
        val seenIds = mutableSetOf<Long>()

        for (article in articles) {
            val tags = try { listAdapter.fromJson(article.tags ?: "[]") ?: emptyList() } catch (e: Exception) { emptyList() }
            for (tag in tags) {
                val cat = CategoryRules.tagToCategory(tag)
                if (cat == categoryName && article.id !in seenIds) {
                    seenIds.add(article.id)
                    result.add(article)
                    break
                }
            }
        }
        return result
    }
}
