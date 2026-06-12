package com.threatloom.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material.icons.filled.BugReport
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onViewLogsClick: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val apiKey by viewModel.openaiApiKey.collectAsState()
    val malpediaKey by viewModel.malpediaApiKey.collectAsState()
    val model by viewModel.openaiModel.collectAsState()
    val lookbackDays by viewModel.lookbackDays.collectAsState()
    val parallelRequests by viewModel.parallelRequests.collectAsState()
    val sources by viewModel.sources.collectAsState()
    val testResult by viewModel.testResult.collectAsState()
    val llmProvider by viewModel.llmProvider.collectAsState()
    val anthropicApiKey by viewModel.anthropicApiKey.collectAsState()
    val anthropicModel by viewModel.anthropicModel.collectAsState()
    val backendUrl by viewModel.backendUrl.collectAsState()
    val reportToken by viewModel.reportToken.collectAsState()
    val dedupEnabled by viewModel.dedupEnabled.collectAsState()
    val dedupThreshold by viewModel.dedupThreshold.collectAsState()

    var showAddFeedDialog by remember { mutableStateOf(false) }
    var showClearDbDialog by remember { mutableStateOf(false) }
    var showReportIssueDialog by remember { mutableStateOf(false) }
    var showRequestFeatureDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(16.dp)
        )

        testResult?.let {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }

        SettingsSection("LLM Provider") {
            val providers = listOf("openai" to "OpenAI", "anthropic" to "Anthropic")
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                providers.forEachIndexed { index, (value, label) ->
                    SegmentedButton(
                        selected = llmProvider == value,
                        onClick = { viewModel.setLlmProvider(value) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = providers.size)
                    ) {
                        Text(label)
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (llmProvider == "openai") {
                OutlinedTextField(
                    value = apiKey, onValueChange = { viewModel.setOpenaiApiKey(it) },
                    label = { Text("OpenAI API Key") }, placeholder = { Text("sk-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))

                val openaiModels = listOf("gpt-5.4-nano", "gpt-5-mini")
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = model, onValueChange = {},
                        readOnly = true, label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        openaiModels.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { viewModel.setOpenaiModel(m); expanded = false })
                        }
                    }
                }
            } else {
                OutlinedTextField(
                    value = anthropicApiKey, onValueChange = { viewModel.setAnthropicApiKey(it) },
                    label = { Text("Anthropic API Key") }, placeholder = { Text("sk-ant-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(8.dp))

                val anthropicModels = listOf("claude-haiku-4-5-20251001", "claude-sonnet-4-6")
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                    OutlinedTextField(
                        value = anthropicModel, onValueChange = {},
                        readOnly = true, label = { Text("Model") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                        modifier = Modifier.fillMaxWidth().menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        anthropicModels.forEach { m ->
                            DropdownMenuItem(text = { Text(m) }, onClick = { viewModel.setAnthropicModel(m); expanded = false })
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Embeddings and semantic search require an OpenAI API key (set below).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (llmProvider == "openai") {
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.testApiKey() }, modifier = Modifier.fillMaxWidth()) { Text("Test API Key") }
            }
        }

        if (llmProvider == "anthropic") {
            SettingsSection("OpenAI (Embeddings)") {
                OutlinedTextField(
                    value = apiKey, onValueChange = { viewModel.setOpenaiApiKey(it) },
                    label = { Text("OpenAI API Key") }, placeholder = { Text("sk-…") },
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Optional. Used only for article embeddings and semantic search in the Intelligence tab.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.testApiKey() }, modifier = Modifier.fillMaxWidth()) { Text("Test API Keys") }
            }
        }

        SettingsSection("Malpedia") {
            OutlinedTextField(
                value = malpediaKey, onValueChange = { viewModel.setMalpediaApiKey(it) },
                label = { Text("Malpedia API Key") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(modifier = Modifier.height(4.dp))
            Button(onClick = { viewModel.testMalpediaApiKey() }, modifier = Modifier.fillMaxWidth()) { Text("Test Malpedia Key") }
        }

        SettingsSection("Lookback Days") {
            var lookbackText by remember(lookbackDays) { mutableStateOf(lookbackDays.toString()) }
            OutlinedTextField(
                value = lookbackText,
                onValueChange = { input ->
                    val filtered = input.filter { it.isDigit() }.take(3)
                    lookbackText = filtered
                    val parsed = filtered.toIntOrNull()
                    if (parsed != null && parsed in 1..365) {
                        viewModel.setLookbackDays(parsed)
                    }
                },
                label = { Text("Days") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                isError = lookbackText.toIntOrNull()?.let { it !in 1..365 } ?: lookbackText.isNotEmpty()
            )
            Text("How far back to look for articles on a full refresh (1–365)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsSection("Parallel Requests") {
            Text("$parallelRequests concurrent requests", style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = parallelRequests.toFloat(), onValueChange = { viewModel.setParallelRequests(it.toInt()) },
                valueRange = 1f..15f, steps = 13
            )
            Text("Number of articles scraped/summarized in parallel. Higher values are faster but may hit API rate limits.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SettingsSection("Deduplication") {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Skip duplicate coverage", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = dedupEnabled, onCheckedChange = { viewModel.setDedupEnabled(it) })
            }
            Text(
                "Detects articles covering the same topic within 24h and summarizes only the longest one, saving cost. Requires an OpenAI key for embeddings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (dedupEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text("Similarity threshold: ${"%.2f".format(dedupThreshold)}", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = dedupThreshold, onValueChange = { viewModel.setDedupThreshold(it) },
                    valueRange = 0.70f..0.95f, steps = 24
                )
                Text("Higher = stricter (fewer merges, fewer false positives). Lower = more aggressive collapsing.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        SettingsSection("RSS Feeds") {
            sources.filter { it.name != "Malpedia" && it.name != "Manual" && it.name != "Cyber Defense Magazine" }.forEach { source ->
                FeedItem(source = source, onToggle = { viewModel.toggleSource(source.id, it) }, onDelete = { viewModel.deleteSource(source.id) })
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = { showAddFeedDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Add Feed")
            }
        }

        // Reporting section hidden until a hosted backend is available
        if (false) {
        SettingsSection("Reporting") {
            OutlinedTextField(
                value = backendUrl,
                onValueChange = { viewModel.setBackendUrl(it) },
                label = { Text("Backend URL") },
                placeholder = { Text("http://192.168.1.x:5000") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = reportToken,
                onValueChange = { viewModel.setReportToken(it) },
                label = { Text("Report Token (optional)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Used to send LLM output reports directly from the app. Configure in your Threat Loom backend under `report_token`.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        } // end if (false)

        SettingsSection("Developer Tools") {
            OutlinedButton(
                onClick = onViewLogsClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.BugReport, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("View App Logs")
            }
            Text(
                "Live in-memory log buffer from the current session (last 500 entries).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsSection("Danger Zone") {
            OutlinedButton(
                onClick = { showClearDbDialog = true },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteForever, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Clear Database")
            }
            Text(
                "Removes all articles, summaries, embeddings, and insights. Feed sources are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        SettingsSection("Feedback") {
            OutlinedButton(
                onClick = { showReportIssueDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Report Issue")
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showRequestFeatureDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Request Feature")
            }
        }

        val context = LocalContext.current
        SettingsSection("About") {
            Text("Threat Loom", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "AI-powered threat news analysis platform",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/nikhilh-20/ThreatLoom")))
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("View Source Code on GitHub")
            }
        }

        Spacer(modifier = Modifier.height(80.dp))
    }

    if (showClearDbDialog) {
        var clearDays by remember { mutableIntStateOf(-1) }  // -1 = All
        val periodOptions = listOf(
            -1 to "All",
            1 to "Last 24h",
            7 to "Last 7d",
            30 to "Last 30d",
            90 to "Last 90d"
        )
        AlertDialog(
            onDismissRequest = { showClearDbDialog = false },
            title = { Text("Clear Database?") },
            text = {
                Column {
                    Text("Select which articles to remove. Feed sources and settings will be kept.")
                    Spacer(modifier = Modifier.height(12.dp))
                    periodOptions.forEach { (days, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { clearDays = days }
                                .padding(vertical = 2.dp)
                        ) {
                            RadioButton(
                                selected = clearDays == days,
                                onClick = { clearDays = days }
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (clearDays == -1) viewModel.clearDatabase()
                    else viewModel.clearDatabaseSince(clearDays)
                    showClearDbDialog = false
                }) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showClearDbDialog = false }) { Text("Cancel") } }
        )
    }

    if (showReportIssueDialog) {
        var issueMessage by remember { mutableStateOf("") }
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showReportIssueDialog = false },
            title = { Text("Report Issue") },
            text = {
                OutlinedTextField(
                    value = issueMessage,
                    onValueChange = { issueMessage = it },
                    label = { Text("Describe the issue") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = Uri.parse("mailto:nikhilhegde.play@gmail.com" +
                        "?subject=${Uri.encode("Bug Report: Threat Loom")}" +
                        "&body=${Uri.encode(issueMessage)}")
                    val intent = Intent(Intent.ACTION_SENDTO, uri)
                    context.startActivity(intent)
                    Toast.makeText(context, "Email client opened", Toast.LENGTH_SHORT).show()
                    showReportIssueDialog = false
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { showReportIssueDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showRequestFeatureDialog) {
        var featureMessage by remember { mutableStateOf("") }
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showRequestFeatureDialog = false },
            title = { Text("Request Feature") },
            text = {
                OutlinedTextField(
                    value = featureMessage,
                    onValueChange = { featureMessage = it },
                    label = { Text("Describe the feature") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                    maxLines = 8
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val uri = Uri.parse("mailto:nikhilhegde.play@gmail.com" +
                        "?subject=${Uri.encode("Feature Request: Threat Loom")}" +
                        "&body=${Uri.encode(featureMessage)}")
                    val intent = Intent(Intent.ACTION_SENDTO, uri)
                    context.startActivity(intent)
                    Toast.makeText(context, "Email client opened", Toast.LENGTH_SHORT).show()
                    showRequestFeatureDialog = false
                }) { Text("Send") }
            },
            dismissButton = {
                TextButton(onClick = { showRequestFeatureDialog = false }) { Text("Cancel") }
            }
        )
    }

    if (showAddFeedDialog) {
        var feedName by remember { mutableStateOf("") }
        var feedUrl by remember { mutableStateOf("") }
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showAddFeedDialog = false },
            title = { Text("Add Feed") },
            text = {
                Column {
                    OutlinedTextField(value = feedName, onValueChange = { feedName = it }, label = { Text("Name") }, singleLine = true)
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(value = feedUrl, onValueChange = { feedUrl = it }, label = { Text("URL") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val trimmedUrl = feedUrl.trim()
                    if (feedName.isNotBlank() && trimmedUrl.isNotBlank()) {
                        if (trimmedUrl.startsWith("https://") || trimmedUrl.startsWith("http://")) {
                            viewModel.addFeed(feedName, trimmedUrl)
                            showAddFeedDialog = false
                        } else {
                            Toast.makeText(context, "Only http:// and https:// URLs are allowed", Toast.LENGTH_SHORT).show()
                        }
                    }
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { showAddFeedDialog = false }) { Text("Cancel") } }
        )
    }
}
