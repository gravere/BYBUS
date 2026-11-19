package com.example.limbuszhcn.translation

import com.example.limbuszhcn.update.sha256

internal enum class AiPublication { Generated, Official, SeparateCopy, Unchanged }

private fun AiTaskSnapshot.selectionUnchanged(sources: TranslationSources, state: SourceState) =
    binding?.unchanged(state, "ai-$outputIdentity", CoverageStore(sources.cache).sourceRevision(state.selected)) == true

/** The same destination decision is used before billing and when committing a resumed task. */
internal fun AiTaskSnapshot.writableOutput(sources: TranslationSources, state: SourceState = sources.read()): String {
    val sameSelection = selectionUnchanged(sources, state)
    val identity = if (sameSelection) outputIdentity else AiTranslationJob.digest("saved-copy|$id").take(32)
    val previous = state.sources.find { it.id == "ai-$identity" }
    require(sameSelection || previous == null) { "另存目标已存在，已保留当前汉化" }
    state.requireSlot(previous)
    return identity
}

/** Called under the operation lock. Decide from the inputs being published, not just the last batch's size. */
internal fun publishAiResult(sources: TranslationSources, tasks: AiTaskStore, task: AiTaskSnapshot,
    result: AiJobResult): AiPublication {
    val state = sources.read()
    require(task.outputIdentity == result.identity)
    val batch = result.progress.completed + result.progress.reused
    if (batch == 0 && (!task.upstream || result.carried == 0)) {
        if (!task.upstream || !task.selectionUnchanged(sources, state)) return AiPublication.Unchanged
        tasks.write(task.copy(phase = AiTaskPhase.Applying, resultHash = result.archive.sha256(), resultTarget = "zero"))
        sources.select("zero")
        return AiPublication.Official
    }
    val id = task.writableOutput(sources, state)
    val unchanged = id == task.outputIdentity
    val hash = result.archive.sha256()
    tasks.write(task.copy(phase = AiTaskPhase.Applying, resultHash = hash, resultTarget = "ai-$id"))
    tasks.retainCommittedCache(result.identity, id)
    val name = if (unchanged) task.outputName else task.outputName.take(55) + " · 另存"
    sources.addGenerated(result.archive, task.baseId, id, name, task.officialVersion, activate = unchanged, independent = !task.config.missingOnly || task.baseId == "global",
        language = task.binding?.language, aiEntryCount = result.aiEntryCount, baseName = task.baseName)
    return if (unchanged) AiPublication.Generated else AiPublication.SeparateCopy
}
