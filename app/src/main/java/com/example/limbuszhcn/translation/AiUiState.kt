package com.example.limbuszhcn.translation

internal data class AiUiState(val config: AiConfig = AiConfig(), val hasKey: Boolean = false,
    val running: Boolean = false, val failed: Boolean = false, val message: String? = null,
    val progress: AiProgress = AiProgress(), val models: AiModelsState = AiModelsState())

internal data class AiModelsState(val request: Long = 0, val endpoint: String = "", val loading: Boolean = false,
    val values: List<String> = emptyList(), val error: String? = null)
