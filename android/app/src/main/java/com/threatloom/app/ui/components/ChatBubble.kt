package com.threatloom.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.threatloom.app.domain.model.ChatMessage

@Composable
fun ChatBubble(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val alignment = if (isUser) Alignment.End else Alignment.Start
    val bgColor = if (isUser) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            shape = MaterialTheme.shapes.medium,
            color = bgColor
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (isUser) {
                    Text(message.content, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                } else {
                    MarkdownText(message.content)
                    LlmDisclaimer()
                }
            }
        }
    }
}
