package com.threatloom.app.ui.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PostAdd
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.*
import com.threatloom.app.ui.theme.CategoryColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onCategoryClick: (String) -> Unit,
    onArticleClick: (Long) -> Unit,
    onGlobalQuizClick: () -> Unit = {},
    viewModel: DashboardViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val refreshStatus by viewModel.refreshStatus.collectAsState()
    val refreshProgress by viewModel.refreshProgress.collectAsState()
    val animatedProgress by animateFloatAsState(targetValue = refreshProgress, label = "progress")
    val isLoading by viewModel.isLoading.collectAsState()
    val costConfirmation by viewModel.costConfirmation.collectAsState()
    val actualCost by viewModel.actualCost.collectAsState()
    val selectedDays by viewModel.selectedDays.collectAsState()
    val isEmbedding by viewModel.isEmbedding.collectAsState()
    val globalQuizAvailable by viewModel.globalQuizAvailable.collectAsState()
    val globalQuizBestScore by viewModel.globalQuizBestScore.collectAsState()
    val isReScraping by viewModel.isReScraping.collectAsState()
    val isReSummarizing by viewModel.isReSummarizing.collectAsState()

    var showAddUrlsDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        viewModel.snackbarMessage.collect { message ->
            snackbarHostState.showSnackbar(message = message, duration = SnackbarDuration.Long)
        }
    }

    actualCost?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissActualCost() },
            title = { Text("Summarization Complete") },
            text = {
                Column {
                    Text("${info.articleCount} articles summarized")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Actual cost: ${"$"}${"%.2f".format(info.actualCost)}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Model: ${info.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissActualCost() }) {
                    Text("OK")
                }
            }
        )
    }

    costConfirmation?.let { estimate ->
        AlertDialog(
            onDismissRequest = { viewModel.declineCost() },
            title = { Text("Summarization Cost Estimate") },
            text = {
                Column {
                    Text("${estimate.articleCount} articles to summarize")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Estimated cost: ${"$"}${"%.2f".format(estimate.estimatedCost)}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Model: ${estimate.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmCost() }) {
                    Text("Proceed")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.declineCost() }) {
                    Text("Skip")
                }
            }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Threat Loom",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Process article URLs") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(
                        onClick = { showAddUrlsDialog = true },
                        enabled = !isRefreshing && !isEmbedding
                    ) {
                        Icon(
                            Icons.Default.PostAdd,
                            contentDescription = "Process article URLs",
                            tint = if (!isRefreshing && !isEmbedding)
                                MaterialTheme.colorScheme.onBackground
                            else
                                MaterialTheme.colorScheme.onBackground.copy(alpha = 0.38f)
                        )
                    }
                }
                RefreshControls(
                    isRefreshing = isRefreshing,
                    onRefresh = { viewModel.refresh() },
                    onRefreshSinceLast = { viewModel.refreshSinceLast() },
                    onAbortRefresh = { viewModel.abortRefresh() },
                    isEmbedding = isEmbedding,
                    onEmbed = { viewModel.embedArticles() },
                    onAbortEmbed = { viewModel.abortEmbed() }
                )
            }
        }

        SearchBar(
            query = searchQuery,
            onQueryChange = { viewModel.onSearchQueryChange(it) },
            modifier = Modifier.padding(bottom = 8.dp)
        )

        AnimatedVisibility(visible = refreshStatus != null) {
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.small
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isRefreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            refreshStatus ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (isRefreshing) {
                            Text(
                                "${(animatedProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    if (isRefreshing) {
                        Spacer(modifier = Modifier.height(6.dp))
                        LinearProgressIndicator(
                            progress = animatedProgress,
                            modifier = Modifier.fillMaxWidth(),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.outline
                        )
                    }
                }
            }
        }

        if (searchQuery.isNotBlank()) {
            if (searchResults.isEmpty()) {
                EmptyState("No matching articles found.")
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(searchResults) { article ->
                        ArticleCard(article = article, onClick = { onArticleClick(article.id) })
                    }
                }
            }
        } else if (isLoading) {
            LoadingIndicator()
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StatsSidebar(
                        stats = stats,
                        onReScrape = if (stats.scrapeFailed > 0) viewModel::reScrapeFailures else null,
                        isReScraping = isReScraping,
                        onReSummarize = if (stats.summaryFailed > 0) viewModel::reSummarizeFailures else null,
                        isReSummarizing = isReSummarizing,
                        onSummarizeUnsummarized = if (stats.unsummarized > 0) viewModel::summarizeUnsummarized else null,
                        isResummarizingUnsummarized = isReSummarizing
                    )
                }
                item {
                    GlobalQuizCard(
                        available = globalQuizAvailable,
                        bestScore = globalQuizBestScore,
                        onClick = onGlobalQuizClick
                    )
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Threat Categories",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for ((label, days) in listOf("All" to 0, "24h" to 1, "7d" to 7, "30d" to 30, "90d" to 90)) {
                            FilterChip(
                                selected = selectedDays == days,
                                onClick = { viewModel.setTimeFilter(days) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
                if (categories.isEmpty()) {
                    item {
                        EmptyState("No articles yet. Tap refresh or configure feeds in Settings.")
                    }
                } else {
                    items(categories.size) { index ->
                        val category = categories[index]
                        val color = CategoryColors.getOrElse(index) { CategoryColors.last() }
                        CategoryCard(
                            category = category,
                            color = color,
                            onClick = { onCategoryClick(category.name) }
                        )
                    }
                }
            }
        }
    }
    SnackbarHost(
        hostState = snackbarHostState,
        modifier = Modifier.align(Alignment.BottomCenter)
    )
    }

    if (showAddUrlsDialog) {
        var urlText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddUrlsDialog = false },
            title = { Text("Process Article URLs") },
            text = {
                Column {
                    Text(
                        "Enter one URL per line. Articles will be scraped and summarized using your configured LLM.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("URLs (one per line)") },
                        placeholder = { Text("https://example.com/article-one\nhttps://example.com/article-two") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 140.dp),
                        maxLines = 12
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val urls = urlText.lines().map { it.trim() }.filter { it.isNotBlank() }
                    if (urls.isNotEmpty()) {
                        viewModel.processCustomUrls(urls)
                    }
                    showAddUrlsDialog = false
                }) { Text("Process") }
            },
            dismissButton = {
                TextButton(onClick = { showAddUrlsDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun GlobalQuizCard(
    available: Boolean,
    bestScore: Int,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Quiz, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
                Column {
                    Text("Global Quiz", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    if (available && bestScore > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(14.dp))
                            Text("Best: $bestScore / 50", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                    } else if (!available) {
                        Text("Create quizzes from articles to unlock", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Button(onClick = onClick, enabled = available) {
                Text("Start")
            }
        }
    }
}
