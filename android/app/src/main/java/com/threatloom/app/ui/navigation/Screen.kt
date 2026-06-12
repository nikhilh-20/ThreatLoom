package com.threatloom.app.ui.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object Intelligence : Screen("intelligence", "Intelligence", Icons.Default.Psychology)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
    data object Drilldown : Screen("drilldown/{category}", "Category") {
        fun createRoute(category: String) = "drilldown/$category"
    }
    data object SubcategoryDrilldown : Screen("subcategory/{category}/{tag}", "Subcategory") {
        fun createRoute(category: String, tag: String) = "subcategory/$category/$tag"
    }
    data object ArticleDetail : Screen("article/{articleId}", "Article") {
        fun createRoute(articleId: Long) = "article/$articleId"
    }
    data object Quiz : Screen("quiz/{articleId}", "Quiz") {
        fun createRoute(articleId: Long) = "quiz/$articleId"
        fun globalRoute() = "quiz/${com.threatloom.app.ui.quiz.QuizViewModel.GLOBAL_ARTICLE_ID}"
    }
    data object Discuss : Screen("discuss/{articleId}", "Discuss") {
        fun createRoute(articleId: Long) = "discuss/$articleId"
    }
    data object ArticleChat : Screen("articlechat/{articleId}", "Chat") {
        fun createRoute(articleId: Long) = "articlechat/$articleId"
    }
    data object LogViewer : Screen("logs", "Logs")

    companion object {
        val bottomNavItems = listOf(Dashboard, Intelligence, Settings)
    }
}
