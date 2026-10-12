package com.lody.virtual.helper.compat;

/** Preserve the result chain only for the installed Companion's checkout component. */
public final class LimbusBillingUiPolicy {
    private LimbusBillingUiPolicy() { }

    public static boolean isCheckoutTarget(String creator, String targetPackage, String targetClass) {
        return "com.android.vending".equals(creator) && creator.equals(targetPackage)
                && "org.microg.vending.billing.ui.InAppBillingHostActivity".equals(targetClass);
    }

    public static boolean canRelayResult(String sourcePackage, boolean hasResultToken, int requestCode) {
        return "com.ProjectMoon.LimbusCompany".equals(sourcePackage)
                && hasResultToken && requestCode >= 0;
    }
}
