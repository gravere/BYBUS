package com.example.limbuszhcn.container

/** Process-local boot result, never inferred from the presence of a class. */
internal object ContainerEngineHealth {
    @Volatile var initialized = false
    @Volatile var failure: String? = null
}
