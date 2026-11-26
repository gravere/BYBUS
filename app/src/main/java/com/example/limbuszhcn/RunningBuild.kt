package com.example.limbuszhcn

import android.content.Context

/** Captured before guest Context/PackageManager hooks; never an inlined BuildConfig constant. */
internal object RunningBuild {
    @Volatile private var captured: Long? = null

    fun code(context: Context): Long = captured ?: synchronized(this) {
        captured ?: context.resources.getInteger(R.integer.app_version_code).toLong().also {
            check(it > 0)
            captured = it
        }
    }
}
