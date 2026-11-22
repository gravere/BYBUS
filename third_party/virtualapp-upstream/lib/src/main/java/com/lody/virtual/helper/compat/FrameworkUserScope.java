package com.lody.virtual.helper.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;

/** Physical Android transport only; never apply to container registries or callback records. */
public final class FrameworkUserScope {
    private static final ConcurrentHashMap<Method, Integer> USER_ARGUMENTS = new ConcurrentHashMap<>();
    private FrameworkUserScope() { }

    @SuppressWarnings("unchecked")
    public static <T> T wrap(T base, Class<?>[] interfaces, IntSupplier virtualUser, IntSupplier physicalUser) {
        if (base == null) return null;
        return (T) Proxy.newProxyInstance(base.getClass().getClassLoader(), interfaces, (proxy, method, args) -> {
            try {
                return method.invoke(base, arguments(method, args, virtualUser.getAsInt(), physicalUser.getAsInt()));
            } catch (InvocationTargetException failure) {
                throw failure.getCause();
            }
        });
    }

    public static Object[] arguments(Method method, Object[] args, int virtualUser, int physicalUser) {
        if (args == null || virtualUser == physicalUser || physicalUser < 0) return args;
        int index = USER_ARGUMENTS.computeIfAbsent(method, FrameworkUserScope::userIndex);
        if (index < 0 || index >= args.length || !(args[index] instanceof Integer)
                || ((Integer) args[index]).intValue() != virtualUser) return args;
        Object[] routed = args.clone();
        routed[index] = physicalUser;
        return routed;
    }

    private static int userIndex(Method method) {
        switch (method.getName()) {
            case "getIntentSender":
                return match(method, 9, "int", "java.lang.String", "android.os.IBinder", "java.lang.String",
                        "int", "[Landroid.content.Intent;", "[Ljava.lang.String;", "int", "android.os.Bundle", "int");
            case "getIntentSenderWithFeature":
                return match(method, 10, "int", "java.lang.String", "java.lang.String", "android.os.IBinder",
                        "java.lang.String", "int", "[Landroid.content.Intent;", "[Ljava.lang.String;", "int", "android.os.Bundle", "int");
            case "getAppActiveNotifications": case "cancelAllNotifications":
                return match(method, 1, "java.lang.String", "int");
            case "enqueueNotificationWithTag":
                return match(method, 5, "java.lang.String", "java.lang.String", "java.lang.String", "int", "android.app.Notification", "int");
            case "cancelNotificationWithTag":
                if (method.getParameterTypes().length == 4)
                    return match(method, 3, "java.lang.String", "java.lang.String", "int", "int");
                return match(method, 4, "java.lang.String", "java.lang.String", "java.lang.String", "int", "int");
            case "getNotificationChannel":
                return match(method, 1, "java.lang.String", "int", "java.lang.String", "java.lang.String");
            case "getConversationNotificationChannel":
                return match(method, 1, "java.lang.String", "int", "java.lang.String", "java.lang.String", "boolean", "java.lang.String");
            case "getNotificationChannels":
                return match(method, 2, "java.lang.String", "java.lang.String", "int");
            case "addClient": case "removeClient":
                return match(method, 1, "android.view.accessibility.IAccessibilityManagerClient", "int");
            case "interrupt": case "getInstalledAccessibilityServiceList":
                return match(method, 0, "int");
            case "sendAccessibilityEvent":
                return match(method, 1, "android.view.accessibility.AccessibilityEvent", "int");
            case "getEnabledAccessibilityServiceList": case "getWindowToken":
                return match(method, 1, "int", "int");
            case "addAccessibilityInteractionConnection":
                int count = method.getParameterTypes().length;
                if (count == 3) return match(method, 2, "android.view.IWindow",
                        "android.view.accessibility.IAccessibilityInteractionConnection", "int");
                if (count == 4) return match(method, 3, "android.view.IWindow",
                        "android.view.accessibility.IAccessibilityInteractionConnection", "java.lang.String", "int");
                return match(method, 4, "android.view.IWindow", "android.os.IBinder",
                        "android.view.accessibility.IAccessibilityInteractionConnection", "java.lang.String", "int");
            case "resetAllModes":
                return match(method, 0, "int", "java.lang.String");
            case "whitelistAppTemporarily":
                return match(method, 2, "java.lang.String", "long", "int");
            default: return -1;
        }
    }

    private static int match(Method method, int user, String... signature) {
        Class<?>[] types = method.getParameterTypes();
        if (types.length != signature.length) return -1;
        for (int i = 0; i < types.length; i++) if (!types[i].getName().equals(signature[i])) return -1;
        return user;
    }
}
