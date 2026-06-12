package com.threatloom.app.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrilldownScreen(
    categoryName: String,
    onArticleClick: (Long) -> Unit,
    onSubcategoryClick: (String, String) -> Unit,
    onBack: () -> Unit,
    viewModel: DrilldownViewModel = hiltViewModel()
) {
    val articles by viewModel.articles.collectAsState()
    val subcategories by viewModel.subcategories.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val quarterlyTrends by viewModel.quarterlyTrends.collectAsState()
    val yearlyTrends by viewModel.yearlyTrends.collectAsState()
    val isTrendLoading by viewModel.isTrendLoading.collectAsState()
    val trendProgress by viewModel.trendProgress.collectAsState()
    val forecastText by viewModel.forecastText.collectAsState()
    val isForecastLoading by viewModel.isForecastLoading.collectAsState()
    val selectedDays by viewModel.selectedDays.collectAsState()
    val insightCostEstimate by viewModel.insightCostEstimate.collectAsState()
    val insightActualCost by viewModel.insightActualCost.collectAsState()
    val reportStatus by viewModel.reportStatus.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var showReportTrendDialog by remember { mutableStateOf(false) }
    var showReportForecastDialog by remember { mutableStateOf(false) }

    LaunchedEffect(reportStatus) {
        reportStatus?.let { coroutineScope.launch { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) } }
    }

    LaunchedEffect(categoryName) {
        viewModel.loadCategory(categoryName)
    }

    insightCostEstimate?.let { est ->
        AlertDialog(
            onDismissRequest = { viewModel.declineInsightCost() },
            title = { Text(if (est.nQuarters != null) "Trend Analysis Cost Estimate" else "Forecast Cost Estimate") },
            text = {
                Column {
                    Text("${est.articleCount} articles to analyze")
                    if (est.nQuarters != null)
                        Text("${est.nQuarters} quarters · ${est.nYears} years of data")
                    Spacer(Modifier.height(4.dp))
                    Text("Estimated cost: ${"$"}${"%.4f".format(est.estimatedCost)}")
                    Text(
                        "Model: ${est.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.confirmInsightCost() }) { Text("Proceed") } },
            dismissButton = { TextButton(onClick = { viewModel.declineInsightCost() }) { Text("Cancel") } }
        )
    }

    insightActualCost?.let { cost ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissInsightActualCost() },
            title = { Text(if (cost.type == "trend") "Trend Analysis Complete" else "Forecast Complete") },
            text = {
                Column {
                    Text("${cost.articleCount} articles analyzed")
                    Spacer(Modifier.height(4.dp))
                    Text("Actual cost: ${"$"}${"%.4f".format(cost.actualCost)}")
                    Text(
                        "Model: ${cost.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.dismissInsightActualCost() }) { Text("OK") } }
        )
    }

    if (showReportTrendDialog) {
        var userNote by remember { mutableStateOf("") }
        val allTrends = (quarterlyTrends + yearlyTrends)
            .joinToString("\n\n---\n\n") { "[${it.periodLabel}]\n${it.trendText}" }
        AlertDialog(
            onDismissRequest = { showReportTrendDialog = false },
            title = { Text("Report Trend Analysis") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "The following AI-generated content will be sent to the developer. You cannot edit it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            allTrends.take(500) + if (allTrends.length > 500) "…" else "",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = userNote,
                        onValueChange = { userNote = it },
                        label = { Text("Optional note (your comments)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.sendTrendReport(userNote); showReportTrendDialog = false }) {
                    Text("Send Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportTrendDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showReportForecastDialog) {
        var userNote by remember { mutableStateOf("") }
        val preview = forecastText ?: ""
        AlertDialog(
            onDismissRequest = { showReportForecastDialog = false },
            title = { Text("Report Forecast") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "The following AI-generated content will be sent to the developer. You cannot edit it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.small
                    ) {
                        Text(
                            preview.take(500) + if (preview.length > 500) "…" else "",
                            modifier = Modifier.padding(8.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = userNote,
                        onValueChange = { userNote = it },
                        label = { Text("Optional note (your comments)") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.sendForecastReport(userNote); showReportForecastDialog = false }) {
                    Text("Send Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportForecastDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text(categoryName) },
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
                        onGenerateForecast = { viewModel.generateForecast(categoryName) },
                        // Report callbacks hidden until a hosted backend is available
                        onReportTrend = null,
                        onReportForecast = null
                    )
                }

                if (subcategories.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Subcategories", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    items(subcategories) { sub ->
                        SubcategoryCard(
                            subcategory = sub,
                            onClick = { onSubcategoryClick(categoryName, sub.tag) }
                        )
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("All Articles", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }

                items(articles) { article ->
                    ArticleCard(article = article, onClick = { onArticleClick(article.id) })
                }
            }
        }
    }
}
