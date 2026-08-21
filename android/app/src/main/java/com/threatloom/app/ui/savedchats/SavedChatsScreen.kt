package com.threatloom.app.ui.savedchats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.util.DateUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedChatsScreen(
    onArticleChatClick: (articleId: Long, conversationId: Long) -> Unit,
    onCategoryChatClick: (category: String, conversationId: Long) -> Unit,
    onDebateClick: (articleId: Long) -> Unit,
    onIntelligenceChatClick: (conversationId: Long) -> Unit,
    viewModel: SavedChatsViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsState()
    var deleteTarget by remember { mutableStateOf<SavedChatListItem?>(null) }

    // Reload whenever the tab is (re)entered — the underlying DAO reads are one-shot suspend calls.
    LaunchedEffect(Unit) { viewModel.refresh() }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete saved chat?") },
            text = { Text("This saved conversation will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(target); deleteTarget = null }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Text(
                "Saved Chats",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp)
            )

            if (items.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        Icons.Default.Bookmarks,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Text(
                        "No saved chats yet",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Save a conversation from an article, category, the Intelligence tab, or a debate, and it will show up here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(items, key = { "${it.kind}-${it.conversationId}" }) { item ->
                        SavedChatRow(
                            item = item,
                            onClick = {
                                when (item.kind) {
                                    SavedChatKind.ARTICLE ->
                                        item.articleId?.let { onArticleChatClick(it, item.conversationId) }
                                    SavedChatKind.CATEGORY ->
                                        item.categoryName?.let { onCategoryChatClick(it, item.conversationId) }
                                    SavedChatKind.INTELLIGENCE -> {
                                        // Queue the resume first, then let the caller switch to the Intelligence tab.
                                        viewModel.requestResumeIntelligence(item.conversationId)
                                        onIntelligenceChatClick(item.conversationId)
                                    }
                                    SavedChatKind.DEBATE ->
                                        item.articleId?.let { onDebateClick(it) }
                                }
                            },
                            onDeleteRequest = { deleteTarget = item }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedChatRow(
    item: SavedChatListItem,
    onClick: () -> Unit,
    onDeleteRequest: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) onDeleteRequest()
            // Never let the box auto-commit the dismiss; the confirm dialog decides.
            false
        }
    )
    SwipeToDismissBox(
        state = dismissState,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.errorContainer, MaterialTheme.shapes.medium)
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Icon(
                    imageVector = item.kind.icon(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(22.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2
                    )
                    item.subtitle?.let {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TypeTag(item.kind)
                        Text(
                            buildString {
                                append(DateUtils.relativeTime(item.date))
                                if ((item.totalCost ?: 0.0) > 0.0) {
                                    append(" · $")
                                    append("%.4f".format(item.totalCost))
                                }
                                item.modelUsed?.let { append(" · $it") }
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TypeTag(kind: SavedChatKind) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Text(
            kind.label(),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

private fun SavedChatKind.label(): String = when (this) {
    SavedChatKind.ARTICLE -> "Article"
    SavedChatKind.CATEGORY -> "Category"
    SavedChatKind.INTELLIGENCE -> "Intelligence"
    SavedChatKind.DEBATE -> "Debate"
}

private fun SavedChatKind.icon(): ImageVector = when (this) {
    SavedChatKind.ARTICLE -> Icons.AutoMirrored.Outlined.Article
    SavedChatKind.CATEGORY -> Icons.Default.Category
    SavedChatKind.INTELLIGENCE -> Icons.Default.Psychology
    SavedChatKind.DEBATE -> Icons.Default.Forum
}
