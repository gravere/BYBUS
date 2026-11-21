package com.example.limbuszhcn.translation

import java.util.concurrent.Executor

/** The system start callback releases exactly one operation, regardless of callback ordering. */
internal class AiTaskDispatch {
    private var operation: Pair<Executor, Runnable>? = null
    private var ready = false
    private var submitted = false

    @Synchronized fun execute(executor: Executor, work: Runnable) {
        check(operation == null) { "Translation operation already attached" }
        operation = executor to work
        dispatch()
    }

    @Synchronized fun release() { ready = true; dispatch() }

    private fun dispatch() {
        val (executor, work) = operation ?: return
        if (!ready || submitted) return
        submitted = true
        executor.execute(work)
    }
}
