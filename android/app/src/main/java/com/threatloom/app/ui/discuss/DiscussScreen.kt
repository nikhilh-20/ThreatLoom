package com.threatloom.app.ui.discuss

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.ChatBubble

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscussScreen(
    articleId: Long,
    onBack: () -> Unit,
    viewModel: DiscussViewModel = hiltViewModel()
) {
    val debateTopic by viewModel.debateTopic.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val input by viewModel.input.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val sessionCost by viewModel.sessionCost.collectAsState()
    val concluded by viewModel.concluded.collectAsState()

    val listState = rememberLazyListState()

    LaunchedEffect(articleId) { viewModel.init(articleId) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Discuss", style = MaterialTheme.typography.titleMedium)
                        debateTopic?.let { topic ->
                            Text(
                                topic,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    sessionCost?.let { cost ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                "$${"%.4f".format(cost)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (!concluded) {
                        TextButton(onClick = viewModel::endDebate, enabled = messages.isNotEmpty() && !isLoading) {
                            Text("End")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty() && isLoading) {
                    item {
                        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                                Text("Opening the debate…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                itemsIndexed(messages) { _, message ->
                    ChatBubble(message = message)
                }

                if (isLoading && messages.isNotEmpty()) {
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
                                Text("Thinking…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            if (concluded) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        "Discussion concluded.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
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
                        placeholder = { Text("Share your perspective…") },
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
