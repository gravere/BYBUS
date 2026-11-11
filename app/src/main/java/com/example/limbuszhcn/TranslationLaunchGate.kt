package com.example.limbuszhcn

import com.example.limbuszhcn.gamefs.PatchInstallSummary

internal enum class TranslationLaunchMode { Localized, NeedsResources }

/** Live resources are ready before the first download; a startup-only index is insufficient. */
internal fun translationLaunchMode(active: PatchInstallSummary?, prepareResources: Boolean): TranslationLaunchMode =
    when {
        active?.liveResourcesReady == true || active?.indexActivated == true -> TranslationLaunchMode.Localized
        else -> TranslationLaunchMode.NeedsResources
    }
