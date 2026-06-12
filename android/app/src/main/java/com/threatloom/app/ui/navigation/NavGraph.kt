package com.threatloom.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.threatloom.app.ui.article.ArticleDetailScreen
import com.threatloom.app.ui.articlechat.ArticleChatScreen
import com.threatloom.app.ui.dashboard.DashboardScreen
import com.threatloom.app.ui.dashboard.DrilldownScreen
import com.threatloom.app.ui.dashboard.SubcategoryDrilldownScreen
import com.threatloom.app.ui.discuss.DiscussScreen
import com.threatloom.app.ui.intelligence.IntelligenceScreen
import com.threatloom.app.ui.quiz.QuizScreen
import com.threatloom.app.ui.logs.LogViewerScreen
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
            arguments = listOf(navArgument("articleId") { type = NavType.LongType })
        ) { backStackEntry ->
            val articleId = backStackEntry.arguments?.getLong("articleId") ?: 0L
            ArticleChatScreen(
                articleId = articleId,
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
                onViewLogsClick = { navController.navigate(Screen.LogViewer.route) }
            )
        }

        composable(Screen.LogViewer.route) {
            LogViewerScreen(onBack = { navController.popBackStack() })
        }
    }
}
