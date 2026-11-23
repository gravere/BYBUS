package com.lody.virtual.helper.compat;

import android.os.IBinder;
import com.lody.virtual.remote.IntentSenderData;

/** Recover only the recorded creator of a container-owned PendingIntent. */
public final class PendingIntentCreatorCompat {
    private PendingIntentCreatorCompat() { }

    public static boolean matches(IntentSenderData record, IBinder token, int userId) {
        return record != null && token != null && token.equals(record.token)
                && record.userId == userId && record.creator != null && !record.creator.isEmpty();
    }

    public static Object restoreInfo(Object systemInfo, String hostPackage, int hostUid,
            IntentSenderData record, IBinder token, int userId, int virtualUid) {
        if (systemInfo == null || virtualUid < 0 || !matches(record, token, userId)) return systemInfo;
        try {
            Class<?> type = systemInfo.getClass();
            if (!"android.app.ActivityManager$PendingIntentInfo".equals(type.getName())) return systemInfo;
            if (!hostPackage.equals(type.getMethod("getCreatorPackage").invoke(systemInfo))
                    || ((Integer) type.getMethod("getCreatorUid").invoke(systemInfo)) != hostUid) return systemInfo;
            // Keep the framework's mutability and sender type; never infer or relax either.
            return type.getConstructor(String.class, int.class, boolean.class, int.class).newInstance(
                    record.creator, virtualUid, type.getMethod("isImmutable").invoke(systemInfo),
                    type.getMethod("getIntentSenderType").invoke(systemInfo));
        } catch (ReflectiveOperationException | SecurityException error) {
            return systemInfo;
        }
    }
}
