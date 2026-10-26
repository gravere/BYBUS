package com.lody.virtual.helper.compat;

import android.content.ComponentName;
import android.os.IBinder;
import java.util.concurrent.ConcurrentHashMap;

/** Identity for a known authentication Activity token, never a process-wide caller override. */
public final class MicrogActivityIdentity {
    private static final ConcurrentHashMap<IBinder, ComponentName> CALLERS = new ConcurrentHashMap<>();
    private MicrogActivityIdentity() { }
    public static void remember(IBinder token, ComponentName caller) {
        if (token != null && caller != null) CALLERS.put(token, caller);
    }
    public static ComponentName caller(IBinder token) { return token == null ? null : CALLERS.get(token); }
    public static void forget(IBinder token) { if (token != null) CALLERS.remove(token); }
}
