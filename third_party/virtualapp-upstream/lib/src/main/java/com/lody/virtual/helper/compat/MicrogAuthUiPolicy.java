package com.lody.virtual.helper.compat;

/** Exact UI boundaries for the bundled microG, independent of device manufacturer. */
public final class MicrogAuthUiPolicy {
    public static final String GMS = "com.google.android.gms";
    public static final String GAME = "com.ProjectMoon.LimbusCompany";
    public static final String ASSISTED = "org.microg.gms.auth.signin.AssistedSignInActivity";
    public static final String AUTH = "org.microg.gms.auth.signin.AuthSignInActivity";
    public static final String LOGIN = "org.microg.gms.auth.login.LoginActivity";
    public static final String BRIDGED_LOGIN = "_VA_|_google_identity_bridge_";

    private MicrogAuthUiPolicy() { }

    public static boolean useClientLocal(String currentPackage, String currentProcess,
            String targetPackage, String targetProcess, String activity) {
        return GMS.equals(currentPackage) && GMS.equals(targetPackage)
                && currentProcess != null && currentProcess.equals(targetProcess)
                && (ASSISTED.equals(activity) || AUTH.equals(activity) || LOGIN.equals(activity)
                    || "org.microg.gms.accountsettings.ui.MainActivity".equals(activity));
    }

    public static boolean isGameIdentityBridge(String creator, String targetPackage,
            String activity, String action, String clientPackage) {
        return GMS.equals(creator) && GMS.equals(targetPackage) && ASSISTED.equals(activity)
                && GAME.equals(clientPackage)
                && "com.google.android.gms.auth.api.credentials.ASSISTED_SIGNIN".equals(action);
    }
}
