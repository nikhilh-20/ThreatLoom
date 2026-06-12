package com.threatloom.app.ui.intelligence

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.ChatBubble
import com.threatloom.app.ui.components.CitationCard
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Show me recent ransomware articles involving data exfiltration",
    "What are the most common initial access techniques used by threat actors?",
    "Find articles about supply chain attacks targeting open source packages",
    "What malware families have been using living-off-the-land techniques?"
)

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

    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var reportDialogIndex by remember { mutableIntStateOf(-1) }

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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
    Column(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
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
            if (messages.isNotEmpty()) {
                IconButton(onClick = { viewModel.clearConversation() }) {
                    Icon(
                        Icons.Default.RestartAlt,
                        contentDescription = "New conversation",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
                                    "Searching articles…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

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
    } // end Scaffold
}
