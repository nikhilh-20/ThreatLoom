package com.threatloom.app.ui.catalogue

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.ArticleCard
import com.threatloom.app.ui.components.EmptyState
import com.threatloom.app.ui.components.LoadingIndicator
import com.threatloom.app.ui.components.TlcTagFilterDropdown

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogueScreen(
    onArticleClick: (Long) -> Unit,
    viewModel: CatalogueViewModel = hiltViewModel()
) {
    val availableTags by viewModel.availableTags.collectAsState()
    val selectedTags by viewModel.selectedTags.collectAsState()
    val filteredArticles by viewModel.filteredArticles.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Catalogue") },
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
                    TlcTagFilterDropdown(
                        availableTags = availableTags,
                        selectedTags = selectedTags,
                        onToggleTag = viewModel::toggleTag
                    )
                }

                if (selectedTags.isNotEmpty()) {
                    item {
                        Text(
                            "${filteredArticles.size} article${if (filteredArticles.size == 1) "" else "s"}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (selectedTags.isEmpty()) {
                    item {
                        EmptyState("Select one or more catalogue tags above to browse articles by technique.")
                    }
                } else if (filteredArticles.isEmpty()) {
                    item {
                        EmptyState("No articles found for the selected tags.")
                    }
                } else {
                    items(filteredArticles) { article ->
                        ArticleCard(article = article, onClick = { onArticleClick(article.id) })
                    }
                }
            }
        }
    }
}
