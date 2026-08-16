package com.threatloom.app.ui.navigation

import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector? = null) {
    data object Dashboard : Screen("dashboard", "Dashboard", Icons.Default.Dashboard)
    data object Intelligence : Screen("intelligence", "Intelligence", Icons.Default.Psychology)
    data object SavedChats : Screen("saved_chats", "Saved Chats", Icons.Default.Bookmarks)
    // No Material icon reads as a plain "T" — MainScreen renders a Text("T") fallback for
    // whichever screen has a null icon instead of a vector.
    data object Catalogue : Screen("catalogue", "Catalogue", icon = null)
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
    data object ArticleChat : Screen("articlechat/{articleId}?conversationId={conversationId}", "Chat") {
        const val NEW_CONVERSATION_ID = -1L
        fun createRoute(articleId: Long, conversationId: Long? = null) =
            "articlechat/$articleId?conversationId=${conversationId ?: NEW_CONVERSATION_ID}"
    }
    data object CategoryChat : Screen("categorychat/{category}?conversationId={conversationId}", "Chat") {
        const val NEW_CONVERSATION_ID = -1L
        fun createRoute(category: String, conversationId: Long? = null) =
            "categorychat/${Uri.encode(category)}?conversationId=${conversationId ?: NEW_CONVERSATION_ID}"
    }
    data object LogViewer : Screen("logs", "Logs")
    data object ModelSettings : Screen("model_settings", "Model Settings")

    companion object {
        val bottomNavItems = listOf(Dashboard, Intelligence, SavedChats, Catalogue, Settings)
    }
}
