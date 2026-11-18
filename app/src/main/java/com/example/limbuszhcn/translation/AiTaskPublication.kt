package com.example.limbuszhcn.translation

import com.example.limbuszhcn.update.sha256

internal enum class AiPublication { Generated, Official, SeparateCopy, Unchanged }

/** Called under the operation lock. Decide from the inputs being published, not just the last batch's size. */
internal fun publishAiResult(sources: TranslationSources, tasks: AiTaskStore, task: AiTaskSnapshot,
    result: AiJobResult): AiPublication {
    val state = sources.read()
    val target = "ai-${result.identity}"
    val unchanged = task.binding?.unchanged(state, target, CoverageStore(sources.cache).sourceRevision(state.selected)) == true
    val batch = result.progress.completed + result.progress.reused
    if (batch == 0 && (!task.upstream || result.carried == 0)) {
        if (!task.upstream || !unchanged) return AiPublication.Unchanged
        tasks.write(task.copy(phase = AiTaskPhase.Applying, resultHash = result.archive.sha256(), resultTarget = "zero"))
        sources.select("zero")
        return AiPublication.Official
    }
    val id = if (unchanged) result.identity else AiTranslationJob.digest("saved-copy|${task.id}").take(32)
    val hash = result.archive.sha256()
    if (!unchanged) require(state.sources.none { it.id == "ai-$id" }) { "另存目标已存在，已保留当前汉化" }
    tasks.write(task.copy(phase = AiTaskPhase.Applying, resultHash = hash, resultTarget = "ai-$id"))
    tasks.retainCommittedCache(result.identity, id)
    val name = if (unchanged) task.outputName else task.outputName.take(55) + " · 另存"
    sources.addGenerated(result.archive, task.baseId, id, name, task.officialVersion, activate = unchanged, independent = !task.config.missingOnly)
    return if (unchanged) AiPublication.Generated else AiPublication.SeparateCopy
}
