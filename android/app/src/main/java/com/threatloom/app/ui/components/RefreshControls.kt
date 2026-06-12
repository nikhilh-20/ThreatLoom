package com.threatloom.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshControls(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    onRefreshSinceLast: (() -> Unit)? = null,
    onAbortRefresh: (() -> Unit)? = null,
    isEmbedding: Boolean = false,
    onEmbed: (() -> Unit)? = null,
    onAbortEmbed: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isRefreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Refreshing…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (onAbortRefresh != null) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Abort refresh") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onAbortRefresh) {
                        Icon(Icons.Default.Stop, contentDescription = "Abort refresh", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        } else {
            if (onRefreshSinceLast != null) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                    tooltip = { PlainTooltip { Text("Quick refresh since last fetch") } },
                    state = rememberTooltipState()
                ) {
                    IconButton(onClick = onRefreshSinceLast, enabled = !isEmbedding) {
                        Icon(Icons.Default.Update, contentDescription = "Refresh since last", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            TooltipBox(
                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                tooltip = { PlainTooltip { Text("Full refresh") } },
                state = rememberTooltipState()
            ) {
                IconButton(onClick = onRefresh, enabled = !isEmbedding) {
                    Icon(Icons.Default.Refresh, contentDescription = "Full refresh", tint = MaterialTheme.colorScheme.primary)
                }
            }
            if (onEmbed != null) {
                if (isEmbedding) {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Abort embedding") } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = { onAbortEmbed?.invoke() }) {
                            Icon(Icons.Default.Stop, contentDescription = "Abort embedding", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    TooltipBox(
                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                        tooltip = { PlainTooltip { Text("Generate embeddings for semantic search") } },
                        state = rememberTooltipState()
                    ) {
                        IconButton(onClick = onEmbed) {
                            Icon(
                                Icons.Default.AutoAwesome,
                                contentDescription = "Generate embeddings",
                                tint = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }
                }
            }
        }
    }
}
