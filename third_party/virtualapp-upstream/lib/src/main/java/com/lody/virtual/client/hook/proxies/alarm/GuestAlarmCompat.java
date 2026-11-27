package com.lody.virtual.client.hook.proxies.alarm;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Reconnect timers need delivery, not an exact alarm-clock entitlement. */
public final class GuestAlarmCompat {
    private GuestAlarmCompat() { }

    public static Object invoke(String guest, Object service, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(service, args);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            Object[] fallback = inexactRetry(guest, method.getParameterTypes(), args, cause);
            if (fallback == null) throw cause;
            try {
                return method.invoke(service, fallback);
            } catch (InvocationTargetException retryFailure) {
                // Never report a successfully scheduled timer if the fallback also failed.
                throw retryFailure.getCause();
            }
        }
    }

    static Object[] inexactRetry(String guest, Class<?>[] types, Object[] args, Throwable failure) {
        if (!"com.google.android.gms".equals(guest) || !(failure instanceof SecurityException)) return null;
        String message = failure.getMessage();
        if (message == null || (!message.contains("SCHEDULE_EXACT_ALARM") && !message.contains("USE_EXACT_ALARM"))) return null;
        if (types.length < 11 || args.length != types.length || types[0] != String.class
                || types[1] != int.class || types[2] != long.class || types[3] != long.class
                || types[4] != long.class || types[5] != int.class
                || !(args[3] instanceof Long) || (Long) args[3] != 0L
                || !(args[5] instanceof Integer) || args[6] == null || args[7] != null || args[10] != null) return null;
        Object[] retry = args.clone();
        retry[3] = -1L; // AlarmManager.WINDOW_HEURISTIC, as used by setAndAllowWhileIdle.
        retry[5] = (Integer) args[5] & ~1; // Remove FLAG_STANDALONE; keep wake-up/idle semantics.
        return retry;
    }
}
