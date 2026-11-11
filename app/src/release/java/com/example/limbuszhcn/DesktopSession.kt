package com.example.limbuszhcn

import android.content.Context
import com.example.limbuszhcn.container.ContainerRuntime

internal fun resumeDesktopSession(context: Context, runtime: ContainerRuntime, allowMove: () -> Boolean): Boolean =
    runtime.resumeGameIfRunning(allowMove)
