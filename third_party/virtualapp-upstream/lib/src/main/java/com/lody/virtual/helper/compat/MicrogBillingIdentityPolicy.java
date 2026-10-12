package com.lody.virtual.helper.compat;

/** Exact identity boundaries for the two Google components installed inside BYBUS. */
public final class MicrogBillingIdentityPolicy {
    private MicrogBillingIdentityPolicy() { }

    public static boolean isGoogleComponent(String packageName) {
        return "com.google.android.gms".equals(packageName)
                || "com.android.vending".equals(packageName);
    }

    public static boolean isGoogleAuthenticator(String packageName, String className) {
        return "com.google.android.gms".equals(packageName)
                && "org.microg.gms.auth.loginservice.GoogleLoginService".equals(className);
    }

    public static boolean isGoogleRegistrationBroadcast(String caller, String targetPackage,
                                                          String targetClass, String action) {
        return "com.google.android.gms".equals(caller)
                && caller.equals(targetPackage)
                && "org.microg.gms.checkin.TriggerReceiver".equals(targetClass)
                && action == null;
    }

    public static boolean isVirtualAccountManagerCaller(String permission, int uid, int pid,
                                                        int hostUid, int serverPid) {
        return "android.permission.ACCOUNT_MANAGER".equals(permission)
                && hostUid > 0 && serverPid > 0 && uid == hostUid && pid == serverPid;
    }

    public static boolean isGoogleSettingsProvider(String providerPackage, String className, String authority) {
        return "com.google.android.gms".equals(providerPackage)
                && "org.microg.gms.settings.SettingsProvider".equals(className)
                && "com.google.android.gms.microg.settings".equals(authority);
    }

    public static boolean isGoogleGservicesProvider(String providerPackage, String className, String authority) {
        return "com.google.android.gms".equals(providerPackage)
                && "org.microg.gms.gservices.GServicesProvider".equals(className)
                && "com.google.android.gsf.gservices".equals(authority);
    }

    public static boolean isRegistrationIdSelection(String[] selection) {
        return selection != null && selection.length == 1 && "android_id".equals(selection[0]);
    }

    public static String registeredId(boolean enabled, long id, long lastCheckin) {
        if (!enabled || id == 0 || lastCheckin <= 0) return null;
        return Long.toUnsignedString(id);
    }

    public static boolean preservesGoogleProviderCaller(String providerPackage, String authority,
                                                   String callerPackage) {
        return "com.google.android.gms".equals(providerPackage)
                && ("com.google.android.gsf.gservices".equals(authority)
                    || "com.google.android.gms.microg.settings".equals(authority))
                && isGoogleComponent(callerPackage);
    }
}
