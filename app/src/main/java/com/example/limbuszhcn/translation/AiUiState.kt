package com.example.limbuszhcn.translation

internal data class AiUiState(val config: AiConfig = AiConfig(), val hasKey: Boolean = false,
    val running: Boolean = false, val failed: Boolean = false, val message: String? = null,
    val progress: AiProgress = AiProgress())
