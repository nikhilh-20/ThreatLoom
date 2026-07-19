package com.threatloom.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.threatloom.app.data.preferences.SettingsDataStore
import com.threatloom.app.domain.model.LlmFeature
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Features exposed on the per-feature Model Settings screen (a subset of all [LlmFeature] values). */
val CONFIGURABLE_LLM_FEATURES = listOf(
    LlmFeature.ARTICLE_CHAT,
    LlmFeature.CATEGORY_CHAT,
    LlmFeature.INTELLIGENCE_CHAT,
    LlmFeature.DISCUSS,
    LlmFeature.CATEGORY_INSIGHT,
    LlmFeature.TREND_ANALYSIS,
    LlmFeature.SUMMARIZATION
)

data class FeatureOverride(
    val feature: LlmFeature,
    val provider: String, // "" | "openai" | "anthropic"
    val model: String      // "" | explicit model string
)

@HiltViewModel
class ModelSettingsViewModel @Inject constructor(
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    val overrides: StateFlow<List<FeatureOverride>> =
        combine(
            CONFIGURABLE_LLM_FEATURES.map { feature ->
                combine(
                    settingsDataStore.featureProvider(feature),
                    settingsDataStore.featureModel(feature)
                ) { provider, model -> FeatureOverride(feature, provider, model) }
            }
        ) { it.toList() }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5000),
                CONFIGURABLE_LLM_FEATURES.map { FeatureOverride(it, "", "") }
            )

    fun setProvider(feature: LlmFeature, provider: String) = viewModelScope.launch {
        settingsDataStore.setFeatureProvider(feature, provider)
        // Clear any model chosen under the previous provider's list so it can't survive a switch.
        settingsDataStore.setFeatureModel(feature, "")
    }

    fun setModel(feature: LlmFeature, model: String) = viewModelScope.launch {
        settingsDataStore.setFeatureModel(feature, model)
    }

    fun resetToGlobal(feature: LlmFeature) = viewModelScope.launch {
        settingsDataStore.setFeatureProvider(feature, "")
        settingsDataStore.setFeatureModel(feature, "")
    }
}
