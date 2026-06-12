package com.threatloom.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.threatloom.app.domain.model.TrendAnalysis

@Composable
fun InsightPanel(
    quarterlyTrends: List<TrendAnalysis>,
    yearlyTrends: List<TrendAnalysis>,
    isTrendLoading: Boolean,
    trendProgress: String,
    onGenerateTrends: () -> Unit,
    forecastText: String?,
    isForecastLoading: Boolean,
    onGenerateForecast: () -> Unit,
    onReportTrend: (() -> Unit)? = null,
    onReportForecast: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Category Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))

            // Trend analysis section
            if (isTrendLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        trendProgress,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (quarterlyTrends.isNotEmpty() || yearlyTrends.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Trend Analysis",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    if (onReportTrend != null) {
                        IconButton(onClick = onReportTrend, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Flag, contentDescription = "Report trend analysis", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                // Quarter-by-Quarter section
                if (quarterlyTrends.isNotEmpty()) {
                    CollapsibleSection(
                        title = "Quarter-by-Quarter Analysis",
                        subtitle = "${quarterlyTrends.size} quarters"
                    ) {
                        quarterlyTrends.forEach { trend ->
                            CollapsibleSection(
                                title = trend.periodLabel.replace("-", " "),
                                subtitle = "${trend.articleCount} articles",
                                nested = true
                            ) {
                                MarkdownText(trend.trendText)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Year-by-Year section
                if (yearlyTrends.isNotEmpty()) {
                    CollapsibleSection(
                        title = "Year-by-Year Analysis",
                        subtitle = "${yearlyTrends.size} years"
                    ) {
                        yearlyTrends.forEach { trend ->
                            CollapsibleSection(
                                title = trend.periodLabel,
                                subtitle = "${trend.articleCount} articles",
                                nested = true
                            ) {
                                MarkdownText(trend.trendText)
                            }
                        }
                    }
                }

                LlmDisclaimer()
            } else {
                Button(onClick = onGenerateTrends) { Text("Generate Trend Analysis") }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            // Forecast section
            if (isForecastLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text(
                        "Generating forecast...",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else if (!forecastText.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Forecast",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                    if (onReportForecast != null) {
                        IconButton(onClick = onReportForecast, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Flag, contentDescription = "Report forecast", modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                MarkdownText(forecastText)
                LlmDisclaimer()
            } else {
                Button(onClick = onGenerateForecast) { Text("Generate Forecast") }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    subtitle: String,
    nested: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (nested) 12.dp else 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (expanded) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = if (expanded) "Collapse" else "Expand",
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                title,
                style = if (nested) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column(
                modifier = Modifier.padding(start = 28.dp, bottom = 8.dp),
                content = content
            )
        }

        if (!nested) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
        }
    }
}
