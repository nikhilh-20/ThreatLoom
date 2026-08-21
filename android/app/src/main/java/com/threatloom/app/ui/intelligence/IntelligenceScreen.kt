package com.threatloom.app.ui.intelligence

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.ChatBubble
import com.threatloom.app.ui.components.CitationCard
import com.threatloom.app.ui.components.WebSearchToggleButton
import com.threatloom.app.util.DateUtils
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Show me recent ransomware articles involving data exfiltration",
    "What are the most common initial access techniques used by threat actors?",
    "Find articles about supply chain attacks targeting open source packages",
    "What malware families have been using living-off-the-land techniques?"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IntelligenceScreen(
    onArticleClick: (Long) -> Unit,
    viewModel: IntelligenceViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val query by viewModel.query.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val embeddingStatus by viewModel.embeddingStatus.collectAsState()
    val reportStatus by viewModel.reportStatus.collectAsState()
    val webSearchEnabled by viewModel.webSearchEnabled.collectAsState()
    val loadingStage by viewModel.loadingStage.collectAsState()
    val isSaved by viewModel.isSaved.collectAsState()
    val hasUnsavedChanges by viewModel.hasUnsavedChanges.collectAsState()
    val savedChats by viewModel.savedChats.collectAsState()
    val sessionCost by viewModel.sessionCost.collectAsState()
    val sessionWebSearchCost by viewModel.sessionWebSearchCost.collectAsState()
    val sessionModel by viewModel.sessionModel.collectAsState()

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var reportDialogIndex by remember { mutableIntStateOf(-1) }
    var isFullscreen by rememberSaveable { mutableStateOf(false) }
    var showSavedSheet by remember { mutableStateOf(false) }
    var deleteChatId by remember { mutableStateOf<Long?>(null) }
    var showCostDialog by remember { mutableStateOf(false) }

    val hasUserMessage = messages.any { it.role == "user" }

    LaunchedEffect(showSavedSheet) {
        if (showSavedSheet) viewModel.refreshSavedChats()
    }

    // Refresh embedding count each time this screen enters the composition
    LaunchedEffect(Unit) {
        viewModel.refreshEmbeddingStatus()
    }

    // Scroll to bottom when a new message arrives
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    LaunchedEffect(reportStatus) {
        reportStatus?.let { coroutineScope.launch { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) } }
    }

    if (reportDialogIndex >= 0) {
        val msgIndex = reportDialogIndex
        val assistantContent = messages.getOrNull(msgIndex)?.content ?: ""
        var userNote by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { reportDialogIndex = -1 },
            title = { Text("Report LLM Output") },
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
                            assistantContent.take(500) + if (assistantContent.length > 500) "…" else "",
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
                TextButton(onClick = { viewModel.sendMessageReport(msgIndex, userNote); reportDialogIndex = -1 }) {
                    Text("Send Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { reportDialogIndex = -1 }) { Text("Cancel") }
            }
        )
    }

    if (showSavedSheet) {
        ModalBottomSheet(onDismissRequest = { showSavedSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp)
            ) {
                Text(
                    "Saved Chats",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(8.dp))
                if (savedChats.isEmpty()) {
                    Text(
                        "No saved chats yet. Use the save icon to keep a conversation.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp)
                    )
                } else {
                    savedChats.forEach { chat ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.resume(chat.id)
                                    showSavedSheet = false
                                }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Chat,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(chat.title ?: "Chat", style = MaterialTheme.typography.bodyMedium, maxLines = 2)
                                Text(
                                    DateUtils.relativeTime(chat.updatedDate) +
                                        (chat.modelUsed?.let { " · $it" } ?: ""),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { deleteChatId = chat.id }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete chat",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    deleteChatId?.let { id ->
        AlertDialog(
            onDismissRequest = { deleteChatId = null },
            title = { Text("Delete saved chat?") },
            text = { Text("This saved conversation will be permanently removed.") },
            confirmButton = {
                TextButton(onClick = { viewModel.deleteSavedChat(id); deleteChatId = null }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteChatId = null }) { Text("Cancel") }
            }
        )
    }

    if (showCostDialog) {
        AlertDialog(
            onDismissRequest = { showCostDialog = false; viewModel.clearConversation() },
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
                TextButton(onClick = { showCostDialog = false; viewModel.clearConversation() }) { Text("OK") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                if (!isFullscreen) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Intelligence",
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        WebSearchToggleButton(webSearchEnabled, viewModel::setWebSearchEnabled)
                        IconButton(
                            onClick = { viewModel.save() },
                            enabled = hasUserMessage && hasUnsavedChanges
                        ) {
                            Icon(
                                if (isSaved && !hasUnsavedChanges) Icons.Default.Bookmark else Icons.Default.Save,
                                contentDescription = "Save chat",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(onClick = { showSavedSheet = true }) {
                            Icon(
                                Icons.Default.History,
                                contentDescription = "Saved chats",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (messages.isNotEmpty()) {
                            IconButton(onClick = {
                                val cost = sessionCost
                                if (cost != null && cost > 0.0) showCostDialog = true else viewModel.clearConversation()
                            }) {
                                Icon(
                                    Icons.Default.RestartAlt,
                                    contentDescription = "New conversation",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        IconButton(onClick = { isFullscreen = true }) {
                            Icon(Icons.Default.Fullscreen, contentDescription = "Enter fullscreen")
                        }
                    }
                }

                // Chat area
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (messages.isEmpty()) {
                        // Welcome screen
                        item {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Text(
                                    "🔍",
                                    style = MaterialTheme.typography.displayMedium
                                )
                                Text(
                                    "Intelligence Search & Analysis",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "Ask questions about your threat intelligence database. Search for articles by meaning or get analytical insights synthesized from your collected data.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                embeddingStatus?.let { status ->
                                    val isWarning = status.startsWith("No articles")
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = if (isWarning)
                                            MaterialTheme.colorScheme.errorContainer
                                        else
                                            MaterialTheme.colorScheme.secondaryContainer
                                    ) {
                                        Text(
                                            status,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isWarning)
                                                MaterialTheme.colorScheme.onErrorContainer
                                            else
                                                MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(4.dp))
                                SUGGESTIONS.forEach { suggestion ->
                                    SuggestionChip(
                                        onClick = { viewModel.useSuggestion(suggestion) },
                                        label = { Text(suggestion, style = MaterialTheme.typography.bodySmall) },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    itemsIndexed(messages) { index, message ->
                        ChatBubble(message = message)
                        // Report button hidden until a hosted backend is available
                        if (false && message.role == "assistant") {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                IconButton(
                                    onClick = { reportDialogIndex = index },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        Icons.Default.Flag,
                                        contentDescription = "Report this response",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                                    )
                                }
                            }
                        }
                        // Citation cards below each assistant message that has articles
                        if (message.role == "assistant" && !message.articles.isNullOrEmpty()) {
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                contentPadding = PaddingValues(vertical = 4.dp)
                            ) {
                                items(message.articles) { article ->
                                    CitationCard(
                                        article = article,
                                        onClick = { onArticleClick(article.id) }
                                    )
                                }
                            }
                        }
                    }

                    // Loading indicator
                    if (isLoading) {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = MaterialTheme.shapes.medium,
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(14.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                        Text(
                                            loadingStage,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
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

                        // Input row
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.Bottom,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { viewModel.updateQuery(it) },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Ask about your threat intelligence…") },
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
                                onClick = { viewModel.sendMessage() },
                                enabled = query.isNotBlank() && !isLoading
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                            }
                        }

                        // Disclaimer at the very bottom
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
