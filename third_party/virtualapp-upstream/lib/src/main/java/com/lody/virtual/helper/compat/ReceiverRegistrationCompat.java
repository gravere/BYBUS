package com.lody.virtual.helper.compat;

/** Locate nullable registration arguments by the Binder signature, never by their current value. */
public final class ReceiverRegistrationCompat {
    private ReceiverRegistrationCompat() { }

    public static void clearRequiredPermission(Class<?>[] parameterTypes, Object[] arguments) {
        if (parameterTypes == null || arguments == null || parameterTypes.length != arguments.length) return;
        for (int i = 0; i + 1 < parameterTypes.length; i++) {
            if (parameterTypes[i] != null
                    && "android.content.IntentFilter".equals(parameterTypes[i].getName())
                    && parameterTypes[i + 1] == String.class) {
                // In the modern ABI, index 4 is the receiver, NOT the permission. A null
                // permission is legitimate and must not trigger the legacy-index fallback.
                if (arguments[i + 1] == null || arguments[i + 1] instanceof String) {
                    arguments[i + 1] = null;
                }
                return;
            }
        }
    }
}
