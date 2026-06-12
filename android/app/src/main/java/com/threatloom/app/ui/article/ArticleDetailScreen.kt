package com.threatloom.app.ui.article

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.components.*
import com.threatloom.app.util.DateUtils
import com.threatloom.app.util.TtsState
import com.threatloom.app.util.rememberArticleTtsController
import com.threatloom.app.util.sectionToUtterances
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArticleDetailScreen(
    articleId: Long,
    onBack: () -> Unit,
    onQuizClick: (articleId: Long) -> Unit = {},
    onChatClick: (articleId: Long) -> Unit = {},
    onDiscussClick: (articleId: Long) -> Unit = {},
    viewModel: ArticleDetailViewModel = hiltViewModel()
) {
    val article by viewModel.article.collectAsState()
    val attackFlow by viewModel.attackFlow.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val duplicates by viewModel.duplicates.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val summaryDeleted by viewModel.summaryDeleted.collectAsState()
    val reportStatus by viewModel.reportStatus.collectAsState()
    val quizData by viewModel.quizData.collectAsState()
    val isGeneratingQuiz by viewModel.isGeneratingQuiz.collectAsState()
    val quizError by viewModel.quizError.collectAsState()
    val isResummarizing by viewModel.isResummarizing.collectAsState()
    val resummarizeError by viewModel.resummarizeError.collectAsState()
    val resummarizeCost by viewModel.resummarizeCost.collectAsState()
    val resummarizeEstimate by viewModel.resummarizeEstimate.collectAsState()
    val quizCost by viewModel.quizCost.collectAsState()
    val debateExists by viewModel.debateExists.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var showDeleteSummaryDialog by remember { mutableStateOf(false) }
    var showReportDialog by remember { mutableStateOf(false) }
    var showDeleteDebateDialog by remember { mutableStateOf(false) }

    // Parsed summary sections, hoisted so both the top-bar audio menu and the content
    // body can use them.
    val sections = remember(article?.summaryText) {
        article?.summaryText?.let { parseSummarySections(it) } ?: emptyList()
    }
    val tts = rememberArticleTtsController()
    var showAudioMenu by remember { mutableStateOf(false) }

    LaunchedEffect(reportStatus) {
        reportStatus?.let { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) }
    }

    LaunchedEffect(quizError) {
        quizError?.let { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) }
    }

    LaunchedEffect(resummarizeError) {
        resummarizeError?.let { snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short) }
    }

    LaunchedEffect(articleId) { viewModel.loadArticle(articleId) }

    resummarizeEstimate?.let { estimate ->
        AlertDialog(
            onDismissRequest = { viewModel.declineResummarize() },
            title = { Text("Re-summarize Cost Estimate") },
            text = {
                Column {
                    Text("${estimate.articleCount} article to summarize")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Estimated cost: ${"$"}${"%.4f".format(estimate.estimatedCost)}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Model: ${estimate.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmResummarize() }) { Text("Summarize") }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.declineResummarize() }) { Text("Cancel") }
            }
        )
    }

    resummarizeCost?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissResummarizeCost() },
            title = { Text("Re-summarized") },
            text = {
                Column {
                    Text("Cost: ${"$"}${"%.4f".format(info.cost)}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Model: ${info.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissResummarizeCost() }) { Text("OK") }
            }
        )
    }

    quizCost?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissQuizCost() },
            title = { Text("Quiz Created") },
            text = {
                Column {
                    Text("${info.questionCount} questions generated")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Cost: ${"$"}${"%.4f".format(info.cost)}")
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Model: ${info.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissQuizCost() }) {
                    Text("OK")
                }
            }
        )
    }

    if (showDeleteSummaryDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteSummaryDialog = false },
            title = { Text("Delete Summary?") },
            text = { Text("This will permanently delete the AI summary and embedding for this article. The article itself is kept and will be re-summarized on the next refresh.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSummaryAndEmbedding(articleId)
                    showDeleteSummaryDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteSummaryDialog = false }) { Text("Cancel") } }
        )
    }

    if (showDeleteDebateDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDebateDialog = false },
            title = { Text("Delete Discussion?") },
            text = { Text("This will permanently delete the saved debate history for this article.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteDebate(articleId)
                    showDeleteDebateDialog = false
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { showDeleteDebateDialog = false }) { Text("Cancel") } }
        )
    }

    if (showReportDialog) {
        var userNote by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showReportDialog = false },
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
                        val preview = article?.summaryText ?: ""
                        Text(
                            preview.take(500) + if (preview.length > 500) "…" else "",
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
                TextButton(onClick = { viewModel.sendReport(userNote); showReportDialog = false }) {
                    Text("Send Report")
                }
            },
            dismissButton = {
                TextButton(onClick = { showReportDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    val titleText = if (tts.state != TtsState.Idle && tts.currentTitle != null) {
                        "Reciting: ${tts.currentTitle}"
                    } else {
                        "Article"
                    }
                    Text(titleText, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    article?.let { art ->
                        if (art.summaryText != null) {
                            // Text-to-speech: pick a section to recite, or pause/resume/stop
                            when (tts.state) {
                                TtsState.Idle -> {
                                    Box {
                                        TooltipBox(
                                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                            tooltip = { PlainTooltip { Text("Listen to a section") } },
                                            state = rememberTooltipState()
                                        ) {
                                            IconButton(onClick = { showAudioMenu = true }) {
                                                Icon(Icons.Default.VolumeUp, contentDescription = "Listen")
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = showAudioMenu,
                                            onDismissRequest = { showAudioMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Read all sections") },
                                                onClick = {
                                                    showAudioMenu = false
                                                    val started = tts.speak(
                                                        "Full summary",
                                                        sections.flatMap { sectionToUtterances(it.title, it.content) }
                                                    )
                                                    if (!started) {
                                                        coroutineScope.launch {
                                                            snackbarHostState.showSnackbar(TTS_UNAVAILABLE_MESSAGE)
                                                        }
                                                    }
                                                }
                                            )
                                            sections.forEach { section ->
                                                DropdownMenuItem(
                                                    text = { Text(section.title) },
                                                    onClick = {
                                                        showAudioMenu = false
                                                        val started = tts.speak(
                                                            section.title,
                                                            sectionToUtterances(section.title, section.content)
                                                        )
                                                        if (!started) {
                                                            coroutineScope.launch {
                                                                snackbarHostState.showSnackbar(TTS_UNAVAILABLE_MESSAGE)
                                                            }
                                                        }
                                                    }
                                                )
                                            }
                                        }
                                    }
                                }
                                TtsState.Playing -> {
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                        tooltip = { PlainTooltip { Text("Pause") } },
                                        state = rememberTooltipState()
                                    ) {
                                        IconButton(onClick = { tts.pause() }) {
                                            Icon(Icons.Default.Pause, contentDescription = "Pause")
                                        }
                                    }
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                        tooltip = { PlainTooltip { Text("Stop") } },
                                        state = rememberTooltipState()
                                    ) {
                                        IconButton(onClick = { tts.stop() }) {
                                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                                        }
                                    }
                                }
                                TtsState.Paused -> {
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                        tooltip = { PlainTooltip { Text("Resume") } },
                                        state = rememberTooltipState()
                                    ) {
                                        IconButton(onClick = { tts.resume() }) {
                                            Icon(Icons.Default.PlayArrow, contentDescription = "Resume")
                                        }
                                    }
                                    TooltipBox(
                                        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                        tooltip = { PlainTooltip { Text("Stop") } },
                                        state = rememberTooltipState()
                                    ) {
                                        IconButton(onClick = { tts.stop() }) {
                                            Icon(Icons.Default.Stop, contentDescription = "Stop")
                                        }
                                    }
                                }
                            }
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Chat about this article") } },
                                state = rememberTooltipState()
                            ) {
                                IconButton(onClick = { onChatClick(articleId) }) {
                                    Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat about this")
                                }
                            }
                            // Report button hidden until a hosted backend is available
                            if (false) {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Report LLM output") } },
                                state = rememberTooltipState()
                            ) {
                                IconButton(onClick = { showReportDialog = true }) {
                                    Icon(Icons.Default.Flag, contentDescription = "Report LLM output")
                                }
                            }
                            } // end if (false)
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Delete summary & embedding") } },
                                state = rememberTooltipState()
                            ) {
                                IconButton(onClick = { showDeleteSummaryDialog = true }) {
                                    Icon(Icons.Default.DeleteSweep, contentDescription = "Delete summary")
                                }
                            }
                        }
                        if (isResummarizing) {
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        } else {
                            TooltipBox(
                                positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                                tooltip = { PlainTooltip { Text("Re-summarize article") } },
                                state = rememberTooltipState()
                            ) {
                                IconButton(onClick = { viewModel.resummarize(articleId) }) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Re-summarize")
                                }
                            }
                        }
                        TooltipBox(
                            positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
                            tooltip = { PlainTooltip { Text("Open original article") } },
                            state = rememberTooltipState()
                        ) {
                            IconButton(onClick = {
                                val url = art.url
                                if (url.startsWith("https://") || url.startsWith("http://")) {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }
                            }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = "Open source")
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        if (isLoading) {
            LoadingIndicator(modifier = Modifier.padding(paddingValues))
        } else if (article == null) {
            Column(
                modifier = Modifier.fillMaxSize().padding(paddingValues).padding(16.dp)
            ) {
                Text("Article not found", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.height(8.dp))
                Text("This article may have been deleted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            val art = article!!

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
            ) {
                // Title
                Text(art.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))

                // Source + date
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    art.sourceName?.let {
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(DateUtils.formatDisplay(art.publishedDate), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                // Tags
                if (tags.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        tags.forEach { tag -> TagPill(tag = tag) }
                    }
                }

                // Also reported by — outlets whose near-duplicate coverage was folded into
                // this article during deduplication.
                if (duplicates.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    AlsoReportedBySection(
                        duplicates = duplicates,
                        onOpen = { url ->
                            if (url.startsWith("https://") || url.startsWith("http://")) {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                            }
                        }
                    )
                }

                // Sections: first one (Executive Summary) always expanded,
                // the rest are collapsible
                sections.forEachIndexed { index, section ->
                    Spacer(modifier = Modifier.height(if (index == 0) 16.dp else 4.dp))
                    if (index == 0) {
                        // Executive Summary — always visible
                        Text(
                            text = section.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        MarkdownText(section.content)
                    } else {
                        CollapsibleSection(
                            title = section.title,
                            onExpandChanged = { yPos ->
                                coroutineScope.launch {
                                    scrollState.animateScrollTo(yPos.toInt())
                                }
                            }
                        ) {
                            MarkdownText(section.content)
                        }
                    }
                }

                // Attack Flow (kill chain)
                if (attackFlow.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    CollapsibleSection(
                        title = "MITRE ATT\u0026CK Flow",
                        initiallyExpanded = true,
                        onExpandChanged = { yPos ->
                            coroutineScope.launch {
                                scrollState.animateScrollTo(yPos.toInt())
                            }
                        }
                    ) {
                        AttackFlowTimeline(steps = attackFlow)
                    }
                }

                // LLM disclaimer at bottom of page
                if (sections.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    LlmDisclaimer()
                }

                // Quiz Prep section
                Spacer(modifier = Modifier.height(16.dp))
                QuizPrepSection(
                    hasSummary = art.summaryText != null,
                    quizData = quizData,
                    isGeneratingQuiz = isGeneratingQuiz,
                    debateExists = debateExists,
                    onCreateQuiz = { viewModel.generateQuiz(articleId) },
                    onPlayQuiz = { onQuizClick(articleId) },
                    onDiscuss = { onDiscussClick(articleId) },
                    onDeleteDebate = { showDeleteDebateDialog = true }
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun AlsoReportedBySection(
    duplicates: List<com.threatloom.app.domain.model.ArticleWithSummary>,
    onOpen: (String) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "Also reported by",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                "Other outlets covered the same topic within 24h; only the most detailed article was summarized.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            duplicates.forEach { dup ->
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpen(dup.url) },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.OpenInBrowser,
                        contentDescription = "Open original article",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        dup.sourceName?.let {
                            Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                        }
                        Text(dup.title, style = MaterialTheme.typography.bodySmall, maxLines = 2)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuizPrepSection(
    hasSummary: Boolean,
    quizData: com.threatloom.app.data.local.entity.QuizEntity?,
    isGeneratingQuiz: Boolean,
    debateExists: Boolean,
    onCreateQuiz: () -> Unit,
    onPlayQuiz: () -> Unit,
    onDiscuss: () -> Unit,
    onDeleteDebate: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Quiz,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "Quiz",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(12.dp))

            when {
                isGeneratingQuiz -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("Generating quiz…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                quizData?.questions != null -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        quizData.scoreBest?.let { best ->
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                                Text(
                                    "Best: $best / ${quizData.scoreTotal ?: "?"}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        } ?: Spacer(Modifier.weight(1f))
                        Button(onClick = onPlayQuiz) {
                            Text("Play Quiz")
                        }
                    }

                    if (!quizData.debateTopic.isNullOrBlank()) {
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                            Text("Think About This", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(quizData.debateTopic, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                        Spacer(Modifier.height(12.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (debateExists) {
                                IconButton(onClick = onDeleteDebate) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete discussion history",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                            Button(onClick = onDiscuss) {
                                Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(if (debateExists) "Continue Discussion" else "Discuss")
                            }
                        }
                    }
                }

                !hasSummary -> {
                    Text(
                        "Run the pipeline to generate a summary before creating a quiz.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth()) {
                        Text("Create Quiz")
                    }
                }

                else -> {
                    Button(onClick = onCreateQuiz, modifier = Modifier.fillMaxWidth()) {
                        Text("Create Quiz")
                    }
                }
            }
        }
    }
}

@Composable
private fun CollapsibleSection(
    title: String,
    initiallyExpanded: Boolean = false,
    onExpandChanged: ((Float) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    var yPosition by remember { mutableFloatStateOf(0f) }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coords ->
                yPosition = coords.positionInParent().y
            },
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        shape = MaterialTheme.shapes.small
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        if (expanded) onExpandChanged?.invoke(yPosition)
                    }
                    .padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Box(modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                    content()
                }
            }
        }
    }
}

private const val TTS_UNAVAILABLE_MESSAGE =
    "Text-to-speech isn't available. Install a voice in Settings → Accessibility → Text-to-speech."

private data class SummarySection(val title: String, val content: String)

private fun parseSummarySections(markdown: String): List<SummarySection> {
    val sections = mutableListOf<SummarySection>()
    var currentTitle: String? = null
    val currentContent = StringBuilder()

    for (line in markdown.lines()) {
        val trimmed = line.trim()
        if (trimmed.startsWith("# ")) {
            if (currentTitle != null) {
                sections.add(SummarySection(currentTitle, currentContent.toString().trim()))
            }
            currentTitle = trimmed.removePrefix("# ")
            currentContent.clear()
        } else {
            if (currentContent.isNotEmpty() || trimmed.isNotEmpty()) {
                currentContent.appendLine(trimmed)
            }
        }
    }

    if (currentTitle != null) {
        sections.add(SummarySection(currentTitle, currentContent.toString().trim()))
    }

    return sections
}
