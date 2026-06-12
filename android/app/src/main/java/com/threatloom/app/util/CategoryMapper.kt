package com.threatloom.app.util

import com.threatloom.app.domain.category.CategoryRules
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CategoryMapper @Inject constructor() {
    private val rules = CategoryRules

    fun tagToCategory(tag: String): String? = rules.tagToCategory(tag)

    fun isGenericTag(tag: String, categoryName: String): Boolean = rules.isGenericTag(tag, categoryName)

    fun formatEntityName(tag: String): String = rules.formatEntityName(tag)

    fun canonicalEntityTag(tagLower: String, categoryName: String): String =
        rules.canonicalEntityTag(tagLower, categoryName)
}
