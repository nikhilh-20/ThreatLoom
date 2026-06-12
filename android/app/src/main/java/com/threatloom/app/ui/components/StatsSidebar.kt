package com.threatloom.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.threatloom.app.domain.model.DashboardStats

@Composable
fun StatsSidebar(
    stats: DashboardStats,
    onReScrape: (() -> Unit)? = null,
    isReScraping: Boolean = false,
    onReSummarize: (() -> Unit)? = null,
    isReSummarizing: Boolean = false,
    onSummarizeUnsummarized: (() -> Unit)? = null,
    isResummarizingUnsummarized: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Statistics", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            StatRow("Articles", stats.totalArticles.toString())
            StatRow("Sources", stats.totalSources.toString())
            StatRow("Summaries", stats.totalSummaries.toString())
            if (stats.unsummarized > 0) {
                ActionStatRow(
                    label = "Unsummarized",
                    value = stats.unsummarized.toString(),
                    onAction = onSummarizeUnsummarized,
                    isRunning = isResummarizingUnsummarized,
                    valueColor = MaterialTheme.colorScheme.tertiary
                )
            }
            if (stats.scrapeFailed > 0) {
                ActionStatRow(
                    label = "Scrape failed",
                    value = stats.scrapeFailed.toString(),
                    onAction = onReScrape,
                    isRunning = isReScraping
                )
            }
            if (stats.summaryFailed > 0) {
                ActionStatRow(
                    label = "Summary failed",
                    value = stats.summaryFailed.toString(),
                    onAction = onReSummarize,
                    isRunning = isReSummarizing
                )
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun ActionStatRow(
    label: String,
    value: String,
    onAction: (() -> Unit)?,
    isRunning: Boolean,
    valueColor: Color = MaterialTheme.colorScheme.error
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold, color = valueColor)
            if (onAction != null) {
                IconButton(
                    onClick = onAction,
                    enabled = !isRunning,
                    modifier = Modifier.size(20.dp)
                ) {
                    if (isRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 1.5.dp,
                            color = valueColor
                        )
                    } else {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Retry",
                            modifier = Modifier.size(14.dp),
                            tint = valueColor
                        )
                    }
                }
            }
        }
    }
}
