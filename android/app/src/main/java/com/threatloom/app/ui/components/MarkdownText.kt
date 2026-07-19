package com.threatloom.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier
) {
    val blocks = remember(markdown) { parseMarkdownBlocks(markdown) }
    val linkColor = MaterialTheme.colorScheme.primary

    SelectionContainer {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.H1 -> Text(
                    text = formatInlineStyles(block.text, linkColor),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                is MdBlock.H2 -> Text(
                    text = formatInlineStyles(block.text, linkColor),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                is MdBlock.H3 -> Text(
                    text = formatInlineStyles(block.text, linkColor),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MdBlock.Bullet -> Row(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        text = "•  ",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = formatInlineStyles(block.text, linkColor),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                is MdBlock.Paragraph -> Text(
                    text = formatInlineStyles(block.text, linkColor),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                is MdBlock.Blockquote -> Row(modifier = Modifier.padding(start = 4.dp).height(IntrinsicSize.Min)) {
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.outline)
                    )
                    Text(
                        text = formatInlineStyles(block.text, linkColor),
                        style = MaterialTheme.typography.bodyMedium,
                        fontStyle = FontStyle.Italic,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 8.dp)
                    )
                }
                is MdBlock.Blank -> Spacer(modifier = Modifier.height(2.dp))
                is MdBlock.CodeBlock -> Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(6.dp)),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.surface
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        if (block.language != null) {
                            Text(
                                text = block.language,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                        Text(
                            text = block.code,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                }
                is MdBlock.HorizontalRule -> HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                is MdBlock.Table -> Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    Row {
                        for (header in block.headers) {
                            Text(
                                text = formatInlineStyles(header),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.widthIn(min = 96.dp).padding(4.dp)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    for (row in block.rows) {
                        Row {
                            for (cell in row) {
                                Text(
                                    text = formatInlineStyles(cell),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.widthIn(min = 96.dp).padding(4.dp)
                                )
                            }
                        }
                    }
                }
            }
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
    data class Blockquote(val text: String) : MdBlock()
    data object Blank : MdBlock()
    data class CodeBlock(val code: String, val language: String?) : MdBlock()
    data object HorizontalRule : MdBlock()
    data class Table(val headers: List<String>, val rows: List<List<String>>) : MdBlock()
}

private fun parseMarkdownBlocks(markdown: String): List<MdBlock> {
    val lines = markdown.lines()
    val blocks = mutableListOf<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trim()
        when {
            trimmed.startsWith("```") -> {
                val language = trimmed.removePrefix("```").trim().ifEmpty { null }
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && lines[i].trim() != "```") {
                    codeLines.add(lines[i])
                    i++
                }
                blocks.add(MdBlock.CodeBlock(codeLines.joinToString("\n"), language))
                i++
            }
            trimmed.isEmpty() -> { blocks.add(MdBlock.Blank); i++ }
            trimmed == "---" || trimmed == "***" || trimmed == "___" -> { blocks.add(MdBlock.HorizontalRule); i++ }
            trimmed.startsWith("### ") -> { blocks.add(MdBlock.H3(trimmed.removePrefix("### "))); i++ }
            trimmed.startsWith("## ") -> { blocks.add(MdBlock.H2(trimmed.removePrefix("## "))); i++ }
            trimmed.startsWith("# ") -> { blocks.add(MdBlock.H1(trimmed.removePrefix("# "))); i++ }
            trimmed.startsWith("- ") -> { blocks.add(MdBlock.Bullet(trimmed.removePrefix("- "))); i++ }
            trimmed.startsWith("* ") -> { blocks.add(MdBlock.Bullet(trimmed.removePrefix("* "))); i++ }
            trimmed.startsWith("> ") || trimmed == ">" -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size) {
                    val quoteTrimmed = lines[i].trim()
                    if (quoteTrimmed.startsWith("> ")) {
                        quoteLines.add(quoteTrimmed.removePrefix("> "))
                    } else if (quoteTrimmed == ">") {
                        quoteLines.add("")
                    } else {
                        break
                    }
                    i++
                }
                blocks.add(MdBlock.Blockquote(quoteLines.joinToString("\n")))
            }
            trimmed.startsWith("|") && i + 1 < lines.size && isTableSeparatorRow(lines[i + 1].trim()) -> {
                val headers = parseTableRow(trimmed)
                i += 2
                val rows = mutableListOf<List<String>>()
                while (i < lines.size && lines[i].trim().startsWith("|")) {
                    rows.add(parseTableRow(lines[i].trim()))
                    i++
                }
                blocks.add(MdBlock.Table(headers, rows))
            }
            else -> { blocks.add(MdBlock.Paragraph(trimmed)); i++ }
        }
    }
    return blocks
}

private fun isTableSeparatorRow(line: String): Boolean =
    line.startsWith("|") && line.trim('|', ' ').split("|")
        .all { it.trim().matches(Regex("^:?-+:?$")) }

private fun parseTableRow(line: String): List<String> =
    line.trim().trim('|').split("|").map { it.trim() }

private fun formatInlineStyles(
    text: String,
    linkColor: Color = Color.Unspecified
): androidx.compose.ui.text.AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                // Bold: **text**
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end != -1) {
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                            append(formatInlineStyles(text.substring(i + 2, end), linkColor))
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
                            append(formatInlineStyles(text.substring(i + 1, end), linkColor))
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
                        withStyle(SpanStyle(fontFamily = FontFamily.Monospace)) {
                            append(text.substring(i + 1, end))
                        }
                        i = end + 1
                    } else {
                        append(text[i])
                        i++
                    }
                }
                // Markdown link: [text](url)
                text[i] == '[' -> {
                    val closeBracket = text.indexOf(']', i + 1)
                    if (closeBracket != -1 && closeBracket + 1 < text.length && text[closeBracket + 1] == '(') {
                        val closeParen = text.indexOf(')', closeBracket + 2)
                        if (closeParen != -1) {
                            val linkText = text.substring(i + 1, closeBracket)
                            val url = text.substring(closeBracket + 2, closeParen)
                            val linkStyle = TextLinkStyles(
                                style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)
                            )
                            withLink(LinkAnnotation.Url(url = url, styles = linkStyle)) {
                                append(linkText)
                            }
                            i = closeParen + 1
                        } else {
                            append(text[i])
                            i++
                        }
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
