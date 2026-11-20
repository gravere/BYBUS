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
    // A paused task can outlive replacement/deletion of its source. Inherit the
    // provenance of the frozen revision, never the currently registered package.
    val inputSource = if (tasks.hasFrozenSource()) tasks.frozenSource() else task.binding?.let { binding ->
        TranslationSource(binding.selected, task.sourceName, "ai", binding.revision,
            baseId = task.baseId, officialVersion = task.officialVersion)
    }?.takeIf { task.binding?.selectedKind == "ai" }
    val previousSource = inputSource?.takeIf { it.kind == "ai" }
    val snapshots = AiBaseSnapshots(sources.cache)
    val base = if (task.upstream) tasks.prepared("reference") ?: tasks.prepared("base")
        else if (previousSource != null) snapshots.previous(previousSource) else tasks.prepared("base")
    base?.let { snapshots.remember("ai-$id", hash, it) }
    if (previousSource != null) {
        val replaced = task.plan?.selected?.values?.flatten()?.toSet().orEmpty()
        AiProvenanceExclusions.write(sources.cache, "ai-$id", hash, AiProvenanceExclusions.read(sources.cache, previousSource) - replaced)
    }
    sources.addGenerated(result.archive, task.baseId, id, name, task.officialVersion, activate = unchanged, independent = !task.config.missingOnly || task.baseId == "global",
        language = task.binding?.language, aiEntryCount = result.aiEntryCount, baseName = task.baseName)
    return if (unchanged) AiPublication.Generated else AiPublication.SeparateCopy
}
