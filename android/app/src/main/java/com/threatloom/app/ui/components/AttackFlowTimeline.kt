package com.threatloom.app.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.threatloom.app.domain.model.AttackFlowStep
import com.threatloom.app.ui.theme.*
import kotlinx.coroutines.delay

// Phase colors matching official MITRE ATT&CK tactics
private fun phaseColor(phase: String): Color {
    val lower = phase.lowercase()
    return when {
        lower == "reconnaissance" || "recon" in lower -> Color(0xFF58A6FF)              // Blue
        lower == "resource development" || "resource" in lower -> Color(0xFFBC8CFF)     // Purple
        lower == "initial access" || "initial" in lower -> Color(0xFFD29922)            // Orange
        lower == "execution" || "execut" in lower -> Color(0xFFF85149)                  // Red
        lower == "persistence" || "persist" in lower -> Color(0xFFF778BA)               // Pink
        lower == "privilege escalation" || "privilege" in lower -> Color(0xFFE3B341)    // Yellow
        lower == "defense evasion" || "evasion" in lower -> Color(0xFF8B949E)           // Gray
        lower == "credential access" || "credential" in lower -> Color(0xFFBC8CFF)     // Purple
        lower == "discovery" -> Color(0xFF3FB950)                                       // Green
        lower == "lateral movement" || "lateral" in lower -> Color(0xFF39D2C0)          // Cyan
        lower == "collection" -> Color(0xFF39D2C0)                                      // Cyan
        lower == "command and control" || "command" in lower || "c2" in lower -> Color(0xFFDB6D28) // Dark orange
        lower == "exfiltration" || "exfil" in lower -> Color(0xFFDA3633)                // Dark red
        lower == "impact" -> Color(0xFFF85149)                                          // Red
        else -> Primary
    }
}

@Composable
fun AttackFlowTimeline(
    steps: List<AttackFlowStep>,
    modifier: Modifier = Modifier
) {
    var visibleCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(steps) {
        visibleCount = 0
        for (i in steps.indices) {
            delay(400L)
            visibleCount = i + 1
        }
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        steps.forEachIndexed { index, step ->
            val color = phaseColor(step.phase)

            AnimatedVisibility(
                visible = index < visibleCount,
                enter = fadeIn(animationSpec = spring(stiffness = Spring.StiffnessLow)) +
                        expandVertically(animationSpec = spring(stiffness = Spring.StiffnessLow))
            ) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    // Timeline rail
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.width(32.dp)
                    ) {
                        // Step number circle
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${index + 1}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                        // Connecting line
                        if (index < steps.lastIndex) {
                            Box(
                                modifier = Modifier
                                    .width(3.dp)
                                    .height(80.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(color.copy(alpha = 0.3f))
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Step card
                    Surface(
                        modifier = Modifier
                            .weight(1f)
                            .padding(bottom = if (index < steps.lastIndex) 8.dp else 0.dp),
                        color = color.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.25f))
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            // Phase badge
                            Surface(
                                color = color.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = step.phase.uppercase(),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = color,
                                    letterSpacing = 0.5.sp
                                )
                            }

                            Spacer(modifier = Modifier.height(6.dp))

                            // Title
                            Text(
                                text = step.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )

                            Spacer(modifier = Modifier.height(4.dp))

                            // Description
                            Text(
                                text = step.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = OnSurfaceVariant
                            )

                            // MITRE technique ID
                            if (step.technique.isNotBlank()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Surface(
                                    color = Color.Transparent,
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline,
                                        RoundedCornerShape(4.dp)
                                    )
                                ) {
                                    Text(
                                        text = step.technique,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.secondary
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
