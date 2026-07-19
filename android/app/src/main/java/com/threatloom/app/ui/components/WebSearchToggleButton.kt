package com.threatloom.app.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun WebSearchToggleButton(enabled: Boolean, onToggle: (Boolean) -> Unit) {
    IconToggleButton(checked = enabled, onCheckedChange = onToggle) {
        Icon(
            imageVector = Icons.Default.Public,
            contentDescription = if (enabled) "Web search enabled" else "Web search disabled",
            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
