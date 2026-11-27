package com.lody.virtual.helper.compat;

import android.content.Intent;

/** Narrow exceptions needed for system-owned activities started by Limbus. */
public final class LimbusActivityCompat {
    private static final String ACTION_REQUEST_PERMISSIONS =
            "android.content.pm.action.REQUEST_PERMISSIONS";
    private static final String ACTION_GOOGLE_SIGN_IN =
            "com.google.android.gms.auth.api.credentials.GOOGLE_SIGN_IN";
    private static final String ACTION_ASSISTED_SIGN_IN =
            "com.google.android.gms.auth.api.credentials.ASSISTED_SIGNIN";
    private static final String EXTRA_GOOGLE_CLIENT_PACKAGE = "client_package_name";

    private LimbusActivityCompat() {
    }

    public static boolean isRuntimePermissionRequest(Intent intent) {
        return intent != null && isRuntimePermissionRequestAction(intent.getAction());
    }

    public static boolean isRuntimePermissionRequestAction(String action) {
        return ACTION_REQUEST_PERMISSIONS.equals(action);
    }

    /**
     * Restore the OAuth client identity after the GMS broker request has crossed the host Binder
     * boundary on the legacy system-service route. Bundled microG identity and authorization
     * services retain the game package end to end, including account selection and sign-out.
     * A real system service still requires the physical host caller; its OAuth UI restores
     * the game identity here without changing account or credential data.
     */
    public static boolean restoreGoogleOAuthClientPackage(
            Intent intent,
            String hostPackage,
            String virtualPackage
    ) {
        if (intent == null || hostPackage == null || virtualPackage == null) {
            return false;
        }
        if (!shouldRestoreGoogleOAuthClientPackage(
                intent.getAction(),
                intent.getStringExtra(EXTRA_GOOGLE_CLIENT_PACKAGE),
                hostPackage)) {
            return false;
        }
        intent.putExtra(EXTRA_GOOGLE_CLIENT_PACKAGE, virtualPackage);
        return true;
    }

    public static boolean shouldRestoreGoogleOAuthClientPackage(
            String action,
            String currentPackage,
            String hostPackage
    ) {
        return hostPackage != null
                && hostPackage.equals(currentPackage)
                && (ACTION_GOOGLE_SIGN_IN.equals(action) || ACTION_ASSISTED_SIGN_IN.equals(action));
    }
}
