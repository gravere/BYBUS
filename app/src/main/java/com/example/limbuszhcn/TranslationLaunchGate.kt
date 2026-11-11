package com.example.limbuszhcn

import com.example.limbuszhcn.gamefs.PatchInstallSummary

internal enum class TranslationLaunchMode { Localized, NeedsResources, ResourcePreparation }

/** A downloaded archive or startup-only index is never a localized game launch. */
internal fun translationLaunchMode(active: PatchInstallSummary?, prepareResources: Boolean): TranslationLaunchMode =
    when {
        active?.indexActivated == true -> TranslationLaunchMode.Localized
        prepareResources -> TranslationLaunchMode.ResourcePreparation
        else -> TranslationLaunchMode.NeedsResources
    }
