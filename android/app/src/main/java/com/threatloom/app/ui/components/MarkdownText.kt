package com.threatloom.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.H1 -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                is MdBlock.H2 -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                is MdBlock.H3 -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MdBlock.Bullet -> Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = "\u2022  ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formatInlineStyles(block.text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = formatInlineStyles(block.text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MdBlock.Blank -> Spacer(modifier = Modifier.height(2.dp))
            }
        }
    }
}

private sealed class MdBlock {
    data class H1(val text: String) : MdBlock()
    data class H2(val text: String) : MdBlock()
    data class H3(val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data object Blank : MdBlock()
}

private fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    for (line in markdown.lines()) {
        val trimmed = line.trim()
        when {
            trimmed.isEmpty() -> blocks.add(MdBlock.Blank)
            trimmed.startsWith("### ") -> blocks.add(MdBlock.H3(trimmed.removePrefix("### ")))
            trimmed.startsWith("## ") -> blocks.add(MdBlock.H2(trimmed.removePrefix("## ")))
            trimmed.startsWith("# ") -> blocks.add(MdBlock.H1(trimmed.removePrefix("# ")))
            trimmed.startsWith("- ") -> blocks.add(MdBlock.Bullet(trimmed.removePrefix("- ")))
            trimmed.startsWith("* ") -> blocks.add(MdBlock.Bullet(trimmed.removePrefix("* ")))
            else -> blocks.add(MdBlock.Paragraph(trimmed))
        }
    }
    return blocks
}

private fun formatInlineStyles(text: String): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold: **text**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(text.substring(i + 2, end))
                        }
                        i = end + 2
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Italic: *text*
                text[i] == '*' && (i == 0 || text[i - 1] != '*') -> {
                    val end = text.indexOf('*', i + 1)
                    if (end != -1 && !text.startsWith("**", end)) {
                        withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Inline code: `text`
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end != -1) {
                        withStyle(SpanStyle(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                else -> {
                    append(text[i])
                    i++
                }
            }
        }
    }
}
