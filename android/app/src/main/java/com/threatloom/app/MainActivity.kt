package com.threatloom.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LocalCafe
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.threatloom.app.notification.NotificationHelper
import com.threatloom.app.ui.navigation.NavGraph
import com.threatloom.app.ui.navigation.Screen
import com.threatloom.app.ui.theme.ThreatLoomTheme
import com.threatloom.app.util.AppEvent
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

private enum class AppLaunchPhase { Splash, Disclaimer, App }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var appEvent: AppEvent

    private val pendingArticleId = mutableStateOf<Long?>(null)

    private fun Intent.articleId(): Long? =
        getLongExtra(NotificationHelper.EXTRA_ARTICLE_ID, -1L).takeIf { it > 0 }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Article requested by a notification tap that cold-started this Activity
        pendingArticleId.value = intent.articleId()
        enableEdgeToEdge()
        setContent {
            ThreatLoomTheme {
                var phase by rememberSaveable { mutableStateOf(AppLaunchPhase.Splash.ordinal) }
                val launchPhase = AppLaunchPhase.entries[phase]

                when (launchPhase) {
                    AppLaunchPhase.Splash -> {
                        SplashScreen(onFinished = { phase = AppLaunchPhase.Disclaimer.ordinal })
                    }
                    AppLaunchPhase.Disclaimer -> {
                        DisclaimerDialog(onAccept = { phase = AppLaunchPhase.App.ordinal })
                    }
                    AppLaunchPhase.App -> {
                        RequestNotificationPermission()
                        MainScreen(
                            appEvent = appEvent,
                            pendingArticleId = pendingArticleId.value,
                            onArticleConsumed = { pendingArticleId.value = null }
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingArticleId.value = intent.articleId()
    }
}

@Composable
private fun RequestNotificationPermission() {
    if (Build.VERSION.SDK_INT < 33) return
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or denied — scheduling continues either way */ }
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@Composable
private fun SplashScreen(onFinished: () -> Unit) {
    val titleAlpha = remember { Animatable(0f) }
    val titleScale = remember { Animatable(0.8f) }
    val subHeaderAlpha = remember { Animatable(0f) }
    val authorAlpha = remember { Animatable(0f) }
    val lineWidth = remember { Animatable(0f) }
    val fadeOut = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            // Title fades in and scales up
            launch {
                titleAlpha.animateTo(1f, animationSpec = tween(800, easing = EaseOutCubic))
            }
            launch {
                titleScale.animateTo(1f, animationSpec = tween(800, easing = EaseOutCubic))
            }
        }
        delay(500)
        coroutineScope {
            // Decorative line draws across and sub-header fades in
            launch {
                lineWidth.animateTo(1f, animationSpec = tween(600, easing = EaseInOutCubic))
            }
            launch {
                subHeaderAlpha.animateTo(1f, animationSpec = tween(600, easing = EaseOutCubic))
            }
        }
        delay(300)
        // Author fades in
        authorAlpha.animateTo(1f, animationSpec = tween(600, easing = EaseOutCubic))
        // Hold for a moment
        delay(1000)
        // Fade everything out
        fadeOut.animateTo(0f, animationSpec = tween(400, easing = EaseInCubic))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0D1117))
            .alpha(fadeOut.value),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Threat Loom",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF58A6FF),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .alpha(titleAlpha.value)
                    .graphicsLayer { scaleX = titleScale.value; scaleY = titleScale.value }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "AI-powered threat news analysis platform",
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF8B949E),
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(subHeaderAlpha.value)
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Decorative accent line
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction = lineWidth.value * 0.4f)
                    .height(2.dp)
                    .background(
                        brush = Brush.horizontalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color(0xFF58A6FF),
                                Color(0xFF3FB950),
                                Color.Transparent
                            )
                        )
                    )
            )

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "By Nikhil Hegde",
                fontSize = 16.sp,
                fontWeight = FontWeight.Normal,
                color = Color(0xFF8B949E),
                textAlign = TextAlign.Center,
                modifier = Modifier.alpha(authorAlpha.value)
            )
        }
    }
}

@Composable
private fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = { /* non-dismissable */ },
        title = {
            Text("Disclaimer", fontWeight = FontWeight.Bold)
        },
        text = {
            Text(
                "This application was fully generated by Claude Code (Anthropic). " +
                "It is provided strictly for educational and informational purposes. " +
                "Any use of this application for malicious purposes is expressly prohibited and may violate applicable laws. " +
                "The author is not responsible for any misuse of this application. " +
                "The author provides this application \"AS-IS\" without warranty of any kind, express or implied, " +
                "and shall not be liable for any damages or consequences resulting from its use.\n\n" +
                "The Threat Loom logo was created by Nano Banana using Gemini image generation."
            )
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text("I Understand")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    appEvent: AppEvent,
    pendingArticleId: Long? = null,
    onArticleConsumed: () -> Unit = {}
) {
    val navController = rememberNavController()

    LaunchedEffect(pendingArticleId) {
        pendingArticleId?.let {
            navController.navigate(Screen.ArticleDetail.createRoute(it))
            onArticleConsumed()
        }
    }
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val isPipelineRunning by appEvent.pipelineRunning.collectAsState()
    var showSupportDialog by remember { mutableStateOf(false) }

    val showBottomBar = Screen.bottomNavItems.any { screen ->
        currentDestination?.hierarchy?.any { it.route == screen.route } == true
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Screen.bottomNavItems.forEach { screen ->
                        val isIntelligence = screen == Screen.Intelligence
                        val dimmed = isIntelligence && isPipelineRunning
                        NavigationBarItem(
                            modifier = if (dimmed) Modifier.alpha(0.4f) else Modifier,
                            icon = {
                                val tint = if (currentDestination?.hierarchy?.any { it.route == screen.route } == true) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                                if (screen.icon != null) {
                                    Icon(screen.icon, contentDescription = screen.title)
                                } else {
                                    Text(
                                        text = screen.title.take(1),
                                        color = tint,
                                        fontWeight = FontWeight.Bold,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                }
                            },
                            // Icon-only bar — no labels. Selection is shown by the highlight pill + tint;
                            // a running refresh is still signaled by the dimmed Intelligence icon above.
                            label = null,
                            selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        )
                    }
                    NavigationBarItem(
                        modifier = Modifier,
                        icon = { Icon(Icons.Default.LocalCafe, contentDescription = "Support on Ko-fi") },
                        // Icon-only like the other tabs; accent tint keeps the donate icon noticeable.
                        label = null,
                        selected = false,
                        onClick = { showSupportDialog = true },
                        colors = NavigationBarItemDefaults.colors(
                            unselectedIconColor = MaterialTheme.colorScheme.primary,
                            unselectedTextColor = MaterialTheme.colorScheme.primary,
                            indicatorColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues)) {
            NavGraph(navController = navController)
        }
    }

    if (showSupportDialog) {
        val context = LocalContext.current
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            icon = { Icon(Icons.Default.LocalCafe, contentDescription = null) },
            title = { Text("Support the Developer") },
            text = {
                Text(
                    "You'll be taken to ko-fi.com/nikhilh20 — " +
                    "an external platform where you can support this project. " +
                    "Thank you! ☕"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showSupportDialog = false
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("https://ko-fi.com/nikhilh20"))
                    )
                }) { Text("Open Ko-fi") }
            },
            dismissButton = {
                TextButton(onClick = { showSupportDialog = false }) { Text("Maybe Later") }
            }
        )
    }
}
