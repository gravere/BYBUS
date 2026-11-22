package com.lody.virtual.helper.compat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.IntSupplier;
import java.util.concurrent.ConcurrentHashMap;

/** Applies only at the real PackageManager boundary, after virtual lookup has finished. */
public final class PackageManagerUserScope {
    private static final ConcurrentHashMap<Method, Integer> USER_ARGUMENTS = new ConcurrentHashMap<>();
    private PackageManagerUserScope() { }

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
        int index = USER_ARGUMENTS.computeIfAbsent(method, PackageManagerUserScope::userIndex);
        if (index < 0 || index >= args.length || !(args[index] instanceof Integer)
                || ((Integer) args[index]).intValue() != virtualUser) return args;
        Object[] routed = args.clone();
        routed[index] = physicalUser;
        return routed;
    }

    private static int userIndex(Method method) {
        String[] signature;
        switch (method.getName()) {
            case "getPackageInfo": case "getApplicationInfo":
            case "getPackageUid": case "getPackageUidEtc":
            case "getPackageGids": case "getPackageGidsEtc": case "resolveContentProvider":
                signature = new String[]{"java.lang.String", "flags", "int"}; break;
            case "getPackageInfoVersioned":
                signature = new String[]{"android.content.pm.VersionedPackage", "flags", "int"}; break;
            case "getActivityInfo": case "getReceiverInfo": case "getServiceInfo": case "getProviderInfo":
            case "getInstrumentationInfoAsUser":
                signature = new String[]{"android.content.ComponentName", "flags", "int"}; break;
            case "resolveIntent": case "resolveService": case "queryIntentActivities":
            case "queryIntentReceivers": case "queryIntentServices": case "queryIntentContentProviders":
                signature = new String[]{"android.content.Intent", "java.lang.String", "flags", "int"}; break;
            case "queryIntentActivityOptions":
                signature = new String[]{"android.content.ComponentName", "[Landroid.content.Intent;",
                        "[Ljava.lang.String;", "android.content.Intent", "java.lang.String", "flags", "int"}; break;
            case "getInstalledPackages": case "getInstalledApplications":
                signature = new String[]{"flags", "int"}; break;
            case "getPackagesHoldingPermissions":
                signature = new String[]{"[Ljava.lang.String;", "flags", "int"}; break;
            case "checkPackageStartable": case "isPackageAvailable": case "getInstallSourceInfo":
            case "getApplicationEnabledSetting": case "getApplicationBlockedSettingAsUser":
            case "getApplicationHiddenSettingAsUser": case "isPackageStoppedForUser":
                signature = new String[]{"java.lang.String", "int"}; break;
            case "getComponentEnabledSetting":
                signature = new String[]{"android.content.ComponentName", "int"}; break;
            case "checkPermission": case "getPermissionFlags": case "checkSignatures":
                signature = new String[]{"java.lang.String", "java.lang.String", "int"}; break;
            case "findPersistentPreferredActivity":
                signature = new String[]{"android.content.Intent", "int"}; break;
            case "activitySupportsIntentAsUser":
                signature = new String[]{"android.content.ComponentName", "android.content.Intent", "java.lang.String", "int"}; break;
            default: return -1;
        }
        Class<?>[] types = method.getParameterTypes();
        if (types.length != signature.length) return -1;
        for (int i = 0; i < types.length; i++) {
            if ("flags".equals(signature[i])) {
                if (types[i] != int.class && types[i] != long.class) return -1;
            } else if (!types[i].getName().equals(signature[i])) return -1;
        }
        return types.length - 1;
    }
}
