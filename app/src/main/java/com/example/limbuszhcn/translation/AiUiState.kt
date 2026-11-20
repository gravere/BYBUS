package com.example.limbuszhcn.translation

internal data class AiUiState(val config: AiConfig = AiConfig(), val hasKey: Boolean = false,
    val running: Boolean = false, val settingsBusy: Boolean = false, val failed: Boolean = false, val message: String? = null,
    val progress: AiProgress = AiProgress(), val models: AiModelsState = AiModelsState(),
    val task: AiTaskSnapshot? = null, val stopping: Boolean = false, val recoveryIssue: Boolean = false,
    val keys: List<AiKeyChoice> = emptyList(), val activeKeyId: String? = null, val savedKeyRevision: Long = 0,
    val library: AiLibraryState = AiLibraryState(), val pricing: AiPricingState = AiPricingState(), val baseUpdate: AiBaseState = AiBaseState()) {
    val hasPendingTask get() = task != null || recoveryIssue
}

internal data class AiModelsState(val request: Long = 0, val endpoint: String = "", val loading: Boolean = false,
    val values: List<String> = emptyList(), val error: String? = null)
