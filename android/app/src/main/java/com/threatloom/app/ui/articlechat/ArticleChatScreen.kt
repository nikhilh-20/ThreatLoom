package com.threatloom.app.ui.articlechat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.ChatBubble
import com.threatloom.app.ui.components.WebSearchToggleButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleChatScreen(
    articleId: Long,
    conversationId: Long? = null,
    onBack: () -> Unit,
    viewModel: ArticleChatViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val input by viewModel.input.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val sessionCost by viewModel.sessionCost.collectAsState()
    val sessionWebSearchCost by viewModel.sessionWebSearchCost.collectAsState()
    val sessionModel by viewModel.sessionModel.collectAsState()
    val isSaved by viewModel.isSaved.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()
    val loadingStage by viewModel.loadingStage.collectAsState()

    val listState = rememberLazyListState()
    var showExitConfirmDialog by remember { mutableStateOf(false) }
    var showCostDialog by remember { mutableStateOf(false) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }

    val handleBack = {
        if (hasUnsavedChanges) {
            showExitConfirmDialog = true
        } else {
            val cost = sessionCost
            if (cost != null && cost > 0.0) showCostDialog = true else onBack()
        }
    }

    BackHandler(enabled = true) { handleBack() }

    LaunchedEffect(articleId, conversationId) { viewModel.init(articleId, conversationId) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    if (showExitConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showExitConfirmDialog = false },
            title = { Text("Close chat?") },
            text = { Text("You have unsaved changes. Closing will discard this conversation unless you save it.") },
            confirmButton = {
                TextButton(onClick = {
                    showExitConfirmDialog = false
                    val cost = sessionCost
                    if (cost != null && cost > 0.0) showCostDialog = true else onBack()
                }) { Text("Discard") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { showExitConfirmDialog = false }) { Text("Cancel") }
                    TextButton(onClick = {
                        viewModel.save()
                        showExitConfirmDialog = false
                        val cost = sessionCost
                        if (cost != null && cost > 0.0) showCostDialog = true else onBack()
                    }) { Text("Save") }
                }
            }
        )
    }

    if (showCostDialog) {
        AlertDialog(
            onDismissRequest = { showCostDialog = false; onBack() },
            title = { Text("Chat Session Cost") },
            text = {
                Column {
                    Text("Cost: ${"$"}${"%.4f".format(sessionCost ?: 0.0)}")
                    sessionWebSearchCost?.takeIf { it > 0.0 }?.let { webCost ->
                        Text(
                            "includes web search: ${"$"}${"%.4f".format(webCost)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Model: ${sessionModel ?: "Unknown"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showCostDialog = false; onBack() }) { Text("OK") }
            }
        )
    }

    Scaffold(
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = { Text("Chat", style = MaterialTheme.typography.titleMedium) },
                    navigationIcon = {
                        IconButton(onClick = { handleBack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    },
                    actions = {
                        WebSearchToggleButton(webSearchEnabled, viewModel::setWebSearchEnabled)
                        IconButton(
                            onClick = viewModel::save,
                            enabled = messages.any { it.role == "user" } && hasUnsavedChanges
                        ) {
                            Icon(
                                if (isSaved && !hasUnsavedChanges) Icons.Default.Bookmark else Icons.Default.Save,
                                contentDescription = "Save conversation"
                            )
                        }
                        IconButton(onClick = { isFullscreen = true }) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Enter fullscreen")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(messages) { _, message ->
                        ChatBubble(message = message)
                    }

                    if (isLoading) {
                        item {
                            Surface(
                                shape = MaterialTheme.shapes.medium,
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                                    Text(loadingStage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }

                AnimatedVisibility(
                    visible = !isFullscreen,
                    enter = expandVertically(),
                    exit = shrinkVertically()
                ) {
                    Column {
                        HorizontalDivider()

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = input,
                                onValueChange = viewModel::onInputChanged,
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Ask about this article…") },
                                maxLines = 4,
                                shape = MaterialTheme.shapes.medium,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant,
                                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                                    unfocusedBorderColor = MaterialTheme.colorScheme.outline
                                )
                            )
                            FilledIconButton(
                                onClick = viewModel::send,
                                enabled = input.isNotBlank() && !isLoading
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }

                        Text(
                            text = "Responses are generated using LLMs and may contain errors. Always verify against original sources.",
                            style = MaterialTheme.typography.labelSmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 8.dp)
                        )
                    }
                }
            }

            if (isFullscreen) {
                IconButton(
                    onClick = { isFullscreen = false },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                ) {
                    Icon(
                        Icons.Default.FullscreenExit,
                        contentDescription = "Exit fullscreen",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
