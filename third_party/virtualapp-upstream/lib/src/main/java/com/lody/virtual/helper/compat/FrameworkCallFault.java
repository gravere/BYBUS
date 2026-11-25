package com.lody.virtual.helper.compat;

import android.util.Log;
import com.lody.virtual.client.VClient;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Preserve a JNI caller's originating framework exception without its private message. */
public final class FrameworkCallFault {
    private static final AtomicInteger COUNT = new AtomicInteger();
    private FrameworkCallFault() { }

    public static void record(Method method, Throwable error) {
        if (!com.lody.virtual.client.core.VirtualCore.get().isVAppProcess()) return;
        if (!"com.google.android.gms".equals(VClient.get().getCurrentPackage())) return;
        if (COUNT.getAndIncrement() >= 32) return;
        Log.w("LimbusVA", describe(method, error));
    }

    public static String describe(Method method, Throwable error) {
        StringBuilder out = new StringBuilder("framework_call_failed method=");
        out.append(safeClass(method.getDeclaringClass().getName())).append('.').append(safeMethod(method.getName()));
        out.append(" type=").append(safeClass(error.getClass().getName()));
        int frames = 0;
        for (StackTraceElement frame : error.getStackTrace()) {
            String owner = safeClass(frame.getClassName());
            if ("external".equals(owner)) continue;
            out.append(frames++ == 0 ? " frames=" : ",").append(owner).append('.').append(safeMethod(frame.getMethodName()));
            if (frames == 6) break;
        }
        return out.toString();
    }

    private static String safeClass(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_.$]{1,240}")) return "external";
        return value.startsWith("java.") || value.startsWith("android.") || value.startsWith("com.android.")
                || value.startsWith("com.lody.virtual.") || value.startsWith("org.chromium.") ? value : "external";
    }
    private static String safeMethod(String value) {
        return value != null && value.matches("[A-Za-z0-9_$<>]{1,120}") ? value : "external";
    }
}
