package com.threatloom.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.threatloom.app.domain.model.ArticleWithSummary
import com.threatloom.app.ui.theme.OnSurfaceVariant
import com.threatloom.app.util.DateUtils

@Composable
fun ArticleCard(
    article: ArticleWithSummary,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = article.title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                article.sourceName?.let {
                    Text(text = it, style = MaterialTheme.typography.labelSmall, color = OnSurfaceVariant)
                }
                Text(
                    text = DateUtils.relativeTime(article.publishedDate),
                    style = MaterialTheme.typography.labelSmall,
                    color = OnSurfaceVariant
                )
            }
            article.relevanceScore?.let { score ->
                if (score > 0f) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Relevance: ${"%.1f%%".format(score * 100)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }
    }
}
