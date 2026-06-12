package com.threatloom.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.domain.category.CategoryRules
import com.threatloom.app.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubcategoryDrilldownScreen(
    categoryName: String,
    subcategoryTag: String,
    onArticleClick: (Long) -> Unit,
    onBack: () -> Unit,
    viewModel: DrilldownViewModel = hiltViewModel()
) {
    val displayName = remember(subcategoryTag) { CategoryRules.formatEntityName(subcategoryTag) }
    val articles by viewModel.articles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val quarterlyTrends by viewModel.quarterlyTrends.collectAsState()
    val yearlyTrends by viewModel.yearlyTrends.collectAsState()
    val isTrendLoading by viewModel.isTrendLoading.collectAsState()
    val trendProgress by viewModel.trendProgress.collectAsState()
    val forecastText by viewModel.forecastText.collectAsState()
    val isForecastLoading by viewModel.isForecastLoading.collectAsState()

    LaunchedEffect(categoryName, subcategoryTag) {
        viewModel.loadSubcategory(categoryName, subcategoryTag)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(displayName) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            LoadingIndicator(modifier = Modifier.padding(paddingValues))
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(paddingValues),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Text(
                        "$categoryName > $displayName",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                item {
                    Text(
                        "${articles.size} articles",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                item {
                    InsightPanel(
                        quarterlyTrends = quarterlyTrends,
                        yearlyTrends = yearlyTrends,
                        isTrendLoading = isTrendLoading,
                        trendProgress = trendProgress,
                        onGenerateTrends = { viewModel.generateTrends(categoryName) },
                        forecastText = forecastText,
                        isForecastLoading = isForecastLoading,
                        onGenerateForecast = { viewModel.generateForecast(categoryName) }
                    )
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Articles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                items(articles) { article ->
                    ArticleCard(article = article, onClick = { onArticleClick(article.id) })
                }
            }
        }
    }
}
