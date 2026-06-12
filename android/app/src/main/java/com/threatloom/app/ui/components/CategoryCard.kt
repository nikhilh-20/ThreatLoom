package com.threatloom.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.threatloom.app.domain.model.CategoryGroup

@Composable
fun CategoryCard(
    category: CategoryGroup,
    color: Color,
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = category.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = color,
                    fontWeight = FontWeight.Bold
                )
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = color.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = "${category.count}",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = color
                    )
                }
            }
            if (category.articles.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                category.articles.take(3).forEach { article ->
                    Text(
                        text = "• ${article.title}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
