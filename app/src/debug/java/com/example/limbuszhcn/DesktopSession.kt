package com.example.limbuszhcn

import android.content.Context
import com.example.limbuszhcn.container.ContainerRuntime
import com.example.limbuszhcn.container.VirtualAppSession

/** Debug-only AVD fixture substitutes the guest package, retaining the real Activity and task route. */
internal fun resumeDesktopSession(context: Context, runtime: ContainerRuntime, allowMove: () -> Boolean): Boolean =
    if (context.getSharedPreferences("session_probe", Context.MODE_PRIVATE).getBoolean("google_guest", false))
        VirtualAppSession(context, "com.google.android.gms").resume(allowMove)
    else runtime.resumeGameIfRunning(allowMove)
