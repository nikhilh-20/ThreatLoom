package com.threatloom.app.ui.components

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier

/**
 * Multi-select dropdown for filtering by one or more tlc- catalogue tags. No existing
 * multi-select component to reuse in this app (only single-select FilterChip rows and
 * single-select ExposedDropdownMenuBox usages), so this composes those primitives directly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TlcTagFilterDropdown(
    availableTags: List<String>,
    selectedTags: Set<String>,
    onToggleTag: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    val summary = when {
        selectedTags.isEmpty() -> "Select catalogue tags"
        selectedTags.size == 1 -> selectedTags.first()
        else -> "${selectedTags.size} tags selected"
    }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = summary,
            onValueChange = {},
            readOnly = true,
            label = { Text("Filter by technique") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor()
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (availableTags.isEmpty()) {
                DropdownMenuItem(text = { Text("No catalogue tags yet") }, onClick = {}, enabled = false)
            }
            availableTags.forEach { tag ->
                DropdownMenuItem(
                    text = { Text(tag) },
                    onClick = { onToggleTag(tag) },
                    leadingIcon = {
                        Checkbox(checked = tag in selectedTags, onCheckedChange = { onToggleTag(tag) })
                    }
                )
            }
        }
    }
}
