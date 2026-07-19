package com.threatloom.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.threatloom.app.domain.model.LlmModelCatalog
import com.threatloom.app.ui.components.SettingsSection

private const val GLOBAL_DEFAULT_LABEL = "Use global default"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSettingsScreen(
    onBack: () -> Unit,
    viewModel: ModelSettingsViewModel = hiltViewModel()
) {
    val overrides by viewModel.overrides.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            Text(
                "Choose a different provider and model for each feature, or leave it on " +
                    "\"$GLOBAL_DEFAULT_LABEL\" to use the provider/model set above in Settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            overrides.forEach { override ->
                FeatureOverrideSection(
                    override = override,
                    onProviderSelected = { provider ->
                        if (provider.isBlank()) {
                            viewModel.resetToGlobal(override.feature)
                        } else {
                            viewModel.setProvider(override.feature, provider)
                        }
                    },
                    onModelSelected = { model -> viewModel.setModel(override.feature, model) }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeatureOverrideSection(
    override: FeatureOverride,
    onProviderSelected: (String) -> Unit,
    onModelSelected: (String) -> Unit
) {
    SettingsSection(override.feature.displayName) {
        val providerOptions = listOf("" to GLOBAL_DEFAULT_LABEL, "openai" to "OpenAI", "anthropic" to "Anthropic")
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            providerOptions.forEachIndexed { index, (value, label) ->
                SegmentedButton(
                    selected = override.provider == value,
                    onClick = { onProviderSelected(value) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = providerOptions.size)
                ) {
                    Text(label)
                }
            }
        }

        if (override.provider.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            val models = if (override.provider == "anthropic") LlmModelCatalog.ANTHROPIC_MODELS else LlmModelCatalog.OPENAI_MODELS
            val selectedLabel = override.model.ifBlank { GLOBAL_DEFAULT_LABEL }
            var expanded by remember { mutableStateOf(false) }
            ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
                OutlinedTextField(
                    value = selectedLabel, onValueChange = {},
                    readOnly = true, label = { Text("Model") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor()
                )
                ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    DropdownMenuItem(
                        text = { Text(GLOBAL_DEFAULT_LABEL) },
                        onClick = { onModelSelected(""); expanded = false }
                    )
                    models.forEach { m ->
                        DropdownMenuItem(text = { Text(m) }, onClick = { onModelSelected(m); expanded = false })
                    }
                }
            }
        } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                "Using global default (Settings > LLM Provider)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
