package com.threatloom.app.ui.logs

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.util.LogEntry
import com.threatloom.app.util.LogLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit,
    viewModel: LogViewerViewModel = hiltViewModel()
) {
    val entries by viewModel.filteredEntries.collectAsState()
    val levelFilter by viewModel.levelFilter.collectAsState()
    val context = LocalContext.current
    var autoScroll by remember { mutableStateOf(true) }
    val listState = rememberLazyListState()

    LaunchedEffect(entries.size) {
        if (autoScroll && entries.isNotEmpty()) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { shareLog(context, entries) }) {
                        Icon(Icons.Default.Share, contentDescription = "Share logs")
                    }
                    IconButton(onClick = { viewModel.clearLogs() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear logs")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                FilterChip(
                    selected = levelFilter == null,
                    onClick = { viewModel.setLevelFilter(null) },
                    label = { Text("All") }
                )
                LogLevel.entries.forEach { level ->
                    FilterChip(
                        selected = levelFilter == level,
                        onClick = { viewModel.setLevelFilter(if (levelFilter == level) null else level) },
                        label = { Text(level.label) }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(checked = autoScroll, onCheckedChange = { autoScroll = it })
                Spacer(Modifier.width(8.dp))
                Text("Auto-scroll", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(
                    "${entries.size} entries",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "No log entries yet.\nRun the pipeline to see logs here.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(entries) { entry ->
                        LogEntryRow(entry)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val timeStr = remember(entry.timestampMs) {
        SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date(entry.timestampMs))
    }
    val badgeColor = when (entry.level) {
        LogLevel.ERROR -> Color(0xFFF85149)
        LogLevel.WARN  -> Color(0xFFD29922)
        LogLevel.INFO  -> Color(0xFF58A6FF)
        LogLevel.DEBUG -> Color(0xFF8B949E)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .background(badgeColor, shape = MaterialTheme.shapes.extraSmall)
                .padding(horizontal = 4.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                entry.level.label,
                style = MaterialTheme.typography.labelSmall,
                color = Color(0xFF0D1117),
                fontFamily = FontFamily.Monospace
            )
        }
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row {
                Text(
                    entry.tag,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF3FB950),
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    timeStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace
                )
            }
            Text(
                entry.message,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace
            )
        }
    }
    HorizontalDivider(color = Color(0xFF30363D).copy(alpha = 0.5f))
}

private fun shareLog(context: Context, entries: List<LogEntry>) {
    val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    val text = entries.joinToString("\n") { e ->
        "${e.level.label}/${e.tag} ${fmt.format(Date(e.timestampMs))}: ${e.message}"
    }
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, "ThreatLoom App Logs")
    }
    context.startActivity(Intent.createChooser(intent, "Share logs"))
}
