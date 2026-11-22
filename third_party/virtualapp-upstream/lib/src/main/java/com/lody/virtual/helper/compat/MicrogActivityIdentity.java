package com.lody.virtual.helper.compat;

import android.content.ComponentName;
import android.os.IBinder;
import android.app.Activity;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/** Identity for a known authentication Activity token, never a process-wide caller override. */
public final class MicrogActivityIdentity {
    private static final ConcurrentHashMap<IBinder, ComponentName> CALLERS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<IBinder, WeakReference<Activity>> LOGIN_CHILDREN = new ConcurrentHashMap<>();
    private MicrogActivityIdentity() { }
    public static void remember(IBinder token, ComponentName caller) {
        if (token != null && caller != null) CALLERS.put(token, caller);
    }
    public static ComponentName caller(IBinder token) { return token == null ? null : CALLERS.get(token); }
    public static void rememberLogin(Activity activity) {
        if (!MicrogAuthUiPolicy.GMS.equals(activity.getPackageName())
                || !MicrogAuthUiPolicy.LOGIN.equals(activity.getClass().getName())) return;
        IBinder token = mirror.android.app.Activity.mToken.get(activity);
        if (token != null) LOGIN_CHILDREN.put(token, new WeakReference<>(activity));
    }
    public static int finishTaskMode(IBinder token, int requested) {
        WeakReference<Activity> entry = token == null ? null : LOGIN_CHILDREN.get(token);
        Activity activity = entry == null ? null : entry.get();
        if (activity == null || activity.isDestroyed()) return requested;
        return MicrogAuthUiPolicy.finishTaskMode(activity.getPackageName(), activity.getClass().getName(),
                activity.isTaskRoot(), requested);
    }
    public static void forget(IBinder token) {
        if (token != null) { CALLERS.remove(token); LOGIN_CHILDREN.remove(token); }
    }
}
