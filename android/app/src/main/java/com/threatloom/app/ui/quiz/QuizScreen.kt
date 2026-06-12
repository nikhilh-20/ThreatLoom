package com.threatloom.app.ui.quiz

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.ui.theme.Secondary
import com.threatloom.app.ui.theme.Error

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuizScreen(
    articleId: Long,
    onBack: () -> Unit,
    viewModel: QuizViewModel = hiltViewModel()
) {
    val phase by viewModel.phase.collectAsState()
    val questions by viewModel.questions.collectAsState()
    val currentIndex by viewModel.currentIndex.collectAsState()
    val timeRemaining by viewModel.timeRemaining.collectAsState()
    val currentInput by viewModel.currentInput.collectAsState()
    val userAnswers by viewModel.userAnswers.collectAsState()
    val evaluations by viewModel.evaluations.collectAsState()
    val score by viewModel.score.collectAsState()
    val debateTopic by viewModel.debateTopic.collectAsState()
    val globalBestScore by viewModel.globalBestScore.collectAsState()
    val error by viewModel.error.collectAsState()
    val evalCost by viewModel.evalCost.collectAsState()
    val articleSources by viewModel.articleSources.collectAsState()

    LaunchedEffect(articleId) { viewModel.init(articleId) }

    val isGlobal = articleId == QuizViewModel.GLOBAL_ARTICLE_ID

    evalCost?.let { info ->
        AlertDialog(
            onDismissRequest = { viewModel.dismissEvalCost() },
            title = { Text("Quiz Evaluated") },
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
                TextButton(onClick = { viewModel.dismissEvalCost() }) {
                    Text("OK")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (isGlobal) "Global Quiz" else "Article Quiz") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when (phase) {
                QuizPhase.LOADING -> {
                    if (error != null) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(error!!, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                            Spacer(Modifier.height(16.dp))
                            OutlinedButton(onClick = onBack) { Text("Go Back") }
                        }
                    } else {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                }

                QuizPhase.ANSWERING -> {
                    if (questions.isNotEmpty()) {
                        AnsweringPhase(
                            questionNumber = currentIndex + 1,
                            totalQuestions = questions.size,
                            questionText = questions[currentIndex].question,
                            hint = questions[currentIndex].hint,
                            articleSource = articleSources.getOrNull(currentIndex),
                            currentInput = currentInput,
                            timeRemaining = timeRemaining,
                            onInputChanged = viewModel::onInputChanged,
                            onSubmit = viewModel::submitOrTimeout
                        )
                    }
                }

                QuizPhase.EVALUATING -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            CircularProgressIndicator()
                            Text("Evaluating your answers…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                QuizPhase.COMPLETE -> {
                    if (evaluations != null) {
                        ResultsPhase(
                            questions = questions,
                            userAnswers = userAnswers,
                            evaluations = evaluations!!,
                            score = score,
                            debateTopic = debateTopic,
                            isGlobal = isGlobal,
                            globalBestScore = globalBestScore,
                            onRetry = viewModel::retry
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnsweringPhase(
    questionNumber: Int,
    totalQuestions: Int,
    questionText: String,
    hint: String,
    articleSource: com.threatloom.app.domain.usecase.GetGlobalQuizQuestionsUseCase.QuizArticleSource?,
    currentInput: String,
    timeRemaining: Int,
    onInputChanged: (String) -> Unit,
    onSubmit: () -> Unit
) {
    val timerFraction = timeRemaining.toFloat() / QuizViewModel.TIMER_SECONDS
    val timerColor by animateColorAsState(
        targetValue = if (timerFraction > 0.33f) MaterialTheme.colorScheme.primary else Error,
        animationSpec = tween(300),
        label = "timerColor"
    )
    val animatedFraction by animateFloatAsState(
        targetValue = timerFraction,
        animationSpec = tween(900),
        label = "timerFraction"
    )
    var hintRevealed by remember(questionNumber) { mutableStateOf(false) }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Progress header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Question $questionNumber / $totalQuestions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                "${timeRemaining}s",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = timerColor
            )
        }

        // Timer bar
        LinearProgressIndicator(
            progress = animatedFraction,
            modifier = Modifier.fillMaxWidth(),
            color = timerColor,
            trackColor = MaterialTheme.colorScheme.surfaceVariant
        )

        // Question card
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = questionText,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
        }

        // Source article link (global quiz only) and hint button
        if (articleSource != null || hint.isNotBlank()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (articleSource != null) {
                    TextButton(
                        onClick = {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(articleSource.url)))
                        },
                        modifier = Modifier.weight(1f, fill = false)
                    ) {
                        Text(
                            text = articleSource.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
                if (hint.isNotBlank()) {
                    TextButton(onClick = { hintRevealed = !hintRevealed }) {
                        Text(if (hintRevealed) "Hide hint" else "Show hint")
                    }
                }
            }
        }

        // Revealed hint card
        if (hintRevealed && hint.isNotBlank()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = MaterialTheme.shapes.small
            ) {
                Text(
                    text = hint,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        // Answer input
        OutlinedTextField(
            value = currentInput,
            onValueChange = onInputChanged,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            label = { Text("Your answer") },
            placeholder = { Text("Type your answer…") },
            minLines = 4
        )

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (questionNumber >= totalQuestions) "Submit" else "Submit & Next")
        }
    }
}

@Composable
private fun ResultsPhase(
    questions: List<com.threatloom.app.data.remote.dto.QuizQuestionDto>,
    userAnswers: List<String>,
    evaluations: List<com.threatloom.app.data.remote.dto.QuizEvaluationItem>,
    score: Int,
    debateTopic: String?,
    isGlobal: Boolean,
    globalBestScore: Int,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Score header
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.medium
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Quiz Complete", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "$score / ${questions.size}",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = scoreColor(score, questions.size)
                )
                if (isGlobal && globalBestScore > 0) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(16.dp))
                        Text("Best: $globalBestScore / ${questions.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                    }
                }
            }
        }

        // Per-question results
        questions.forEachIndexed { i, question ->
            val eval = evaluations.getOrNull(i)
            val userAnswer = userAnswers.getOrElse(i) { "" }
            QuizResultItem(
                number = i + 1,
                question = question.question,
                userAnswer = userAnswer,
                modelAnswer = question.modelAnswer,
                verdict = eval?.verdict ?: "incorrect",
                feedback = eval?.feedback ?: ""
            )
        }

        // Debate topic
        debateTopic?.let { topic ->
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(18.dp))
                        Text("Think About This", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.tertiary)
                    }
                    Text(topic, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic)
                }
            }
        }

        Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
            Text("Retry")
        }

        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun QuizResultItem(
    number: Int,
    question: String,
    userAnswer: String,
    modelAnswer: String,
    verdict: String,
    feedback: String
) {
    val verdictColor = when (verdict) {
        "correct" -> Secondary
        "partial" -> MaterialTheme.colorScheme.tertiary
        else -> Error
    }
    val verdictLabel = when (verdict) {
        "correct" -> "Correct"
        "partial" -> "Partial"
        else -> "Incorrect"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 1.dp
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Q$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Surface(color = verdictColor.copy(alpha = 0.15f), shape = MaterialTheme.shapes.extraSmall) {
                    Text(verdictLabel, modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = verdictColor, fontWeight = FontWeight.Bold)
                }
            }
            Text(question, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Your answer:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(userAnswer.ifBlank { "(no answer)" }, style = MaterialTheme.typography.bodySmall)
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Model answer:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(modelAnswer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (feedback.isNotBlank()) {
                Text(feedback, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = verdictColor)
            }
        }
    }
}

@Composable
private fun scoreColor(score: Int, total: Int): Color {
    if (total == 0) return MaterialTheme.colorScheme.onSurface
    return when {
        score.toFloat() / total >= 0.7f -> Secondary
        score.toFloat() / total >= 0.4f -> MaterialTheme.colorScheme.tertiary
        else -> Error
    }
}
