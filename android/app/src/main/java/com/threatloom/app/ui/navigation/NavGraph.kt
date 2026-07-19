package com.threatloom.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.threatloom.app.ui.article.ArticleDetailScreen
import com.threatloom.app.ui.articlechat.ArticleChatScreen
import com.threatloom.app.ui.categorychat.CategoryChatScreen
import com.threatloom.app.ui.dashboard.DashboardScreen
import com.threatloom.app.ui.dashboard.DrilldownScreen
import com.threatloom.app.ui.dashboard.SubcategoryDrilldownScreen
import com.threatloom.app.ui.discuss.DiscussScreen
import com.threatloom.app.ui.intelligence.IntelligenceScreen
import com.threatloom.app.ui.quiz.QuizScreen
import com.threatloom.app.ui.logs.LogViewerScreen
import com.threatloom.app.ui.settings.ModelSettingsScreen
import com.threatloom.app.ui.settings.SettingsScreen

@Composable
fun NavGraph(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Dashboard.route) {
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onCategoryClick = { category ->
                    navController.navigate(Screen.Drilldown.createRoute(category))
                },
                onArticleClick = { articleId ->
                    navController.navigate(Screen.ArticleDetail.createRoute(articleId))
                },
                onGlobalQuizClick = {
                    navController.navigate(Screen.Quiz.globalRoute())
                }
            )
        }

        composable(
            route = Screen.Drilldown.route,
            arguments = listOf(navArgument("category") { type = NavType.StringType })
        ) { backStackEntry ->
            val category = backStackEntry.arguments?.getString("category") ?: ""
            DrilldownScreen(
                categoryName = category,
                onArticleClick = { articleId ->
                    navController.navigate(Screen.ArticleDetail.createRoute(articleId))
                },
                onSubcategoryClick = { cat, tag ->
                    navController.navigate(Screen.SubcategoryDrilldown.createRoute(cat, tag))
                },
                onChatClick = { cat ->
                    navController.navigate(Screen.CategoryChat.createRoute(cat))
                },
                onOpenChatClick = { cat, convId ->
                    navController.navigate(Screen.CategoryChat.createRoute(cat, convId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.SubcategoryDrilldown.route,
            arguments = listOf(
                navArgument("category") { type = NavType.StringType },
                navArgument("tag") { type = NavType.StringType }
            )
        ) { backStackEntry ->
            val category = backStackEntry.arguments?.getString("category") ?: ""
            val tag = backStackEntry.arguments?.getString("tag") ?: ""
            SubcategoryDrilldownScreen(
                categoryName = category,
                subcategoryTag = tag,
                onArticleClick = { articleId ->
                    navController.navigate(Screen.ArticleDetail.createRoute(articleId))
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ArticleDetail.route,
            arguments = listOf(navArgument("articleId") { type = NavType.LongType })
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            ArticleDetailScreen(
                articleId = articleId,
                onBack = { navController.popBackStack() },
                onQuizClick = { id -> navController.navigate(Screen.Quiz.createRoute(id)) },
                onChatClick = { id -> navController.navigate(Screen.ArticleChat.createRoute(id)) },
                onOpenChatClick = { id, convId -> navController.navigate(Screen.ArticleChat.createRoute(id, convId)) },
                onDiscussClick = { id -> navController.navigate(Screen.Discuss.createRoute(id)) }
            )
        }

        composable(
            route = Screen.Quiz.route,
            arguments = listOf(navArgument("articleId") { type = NavType.LongType })
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            QuizScreen(
                articleId = articleId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.Discuss.route,
            arguments = listOf(navArgument("articleId") { type = NavType.LongType })
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            DiscussScreen(
                articleId = articleId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.ArticleChat.route,
            arguments = listOf(
                navArgument("articleId") { type = NavType.LongType },
                navArgument("conversationId") { type = NavType.LongType; defaultValue = Screen.ArticleChat.NEW_CONVERSATION_ID }
            )
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            val rawConversationId = backStackEntry.arguments?.getLong("conversationId") ?: Screen.ArticleChat.NEW_CONVERSATION_ID
            val conversationId = rawConversationId.takeIf { it != Screen.ArticleChat.NEW_CONVERSATION_ID }
            ArticleChatScreen(
                articleId = articleId,
                conversationId = conversationId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = Screen.CategoryChat.route,
            arguments = listOf(
                navArgument("category") { type = NavType.StringType },
                navArgument("conversationId") { type = NavType.LongType; defaultValue = Screen.CategoryChat.NEW_CONVERSATION_ID }
            )
        ) { backStackEntry ->
            val category = backStackEntry.arguments?.getString("category") ?: ""
            val rawConversationId = backStackEntry.arguments?.getLong("conversationId") ?: Screen.CategoryChat.NEW_CONVERSATION_ID
            val conversationId = rawConversationId.takeIf { it != Screen.CategoryChat.NEW_CONVERSATION_ID }
            CategoryChatScreen(
                categoryName = category,
                conversationId = conversationId,
                onBack = { navController.popBackStack() }
            )
        }

        composable(Screen.Intelligence.route) {
            IntelligenceScreen(
                onArticleClick = { articleId ->
                    navController.navigate(Screen.ArticleDetail.createRoute(articleId))
                }
            )
        }

        composable(Screen.Settings.route) {
            SettingsScreen(
                onViewLogsClick = { navController.navigate(Screen.LogViewer.route) },
                onModelSettingsClick = { navController.navigate(Screen.ModelSettings.route) }
            )
        }

        composable(Screen.LogViewer.route) {
            LogViewerScreen(onBack = { navController.popBackStack() })
        }

        composable(Screen.ModelSettings.route) {
            ModelSettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}
