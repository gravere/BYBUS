package com.lody.virtual.helper.compat;

import java.net.URI;

/** The only store page a Limbus guest may open outside its container. */
public final class LimbusPlayStoreCompat {
    public static final String GAME_PACKAGE = "com.ProjectMoon.LimbusCompany";
    public static final String STORE_PACKAGE = "com.android.vending";
    public static final String DETAILS_URL =
            "https://play.google.com/store/apps/details?id=" + GAME_PACKAGE;

    private LimbusPlayStoreCompat() { }

    public static boolean isGameDetailsRequest(String caller, String action, String data,
                                                String targetPackage, String componentPackage) {
        if (!GAME_PACKAGE.equals(caller) || !"android.intent.action.VIEW".equals(action)
                || data == null || data.length() > 1024
                || (targetPackage != null && !STORE_PACKAGE.equals(targetPackage))
                || (componentPackage != null && !STORE_PACKAGE.equals(componentPackage))) return false;
        try {
            URI uri = new URI(data);
            if (uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getPort() != -1
                    || !("id=" + GAME_PACKAGE).equals(uri.getRawQuery())) return false;
            return ("market".equals(uri.getScheme()) && "details".equals(uri.getRawAuthority())
                    && "".equals(uri.getRawPath()))
                    || ("https".equals(uri.getScheme()) && "play.google.com".equals(uri.getRawAuthority())
                    && "/store/apps/details".equals(uri.getRawPath()));
        } catch (Exception ignored) {
            return false;
        }
    }
}
