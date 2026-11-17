package com.lody.virtual.helper.compat;

/** Pure identity/scope rules; deliberately independent of manufacturer and SDK integers. */
public final class ProviderRoutePolicy {
    private ProviderRoutePolicy() { }

    public static boolean supportedCaller(String pkg) {
        return "com.ProjectMoon.LimbusCompany".equals(pkg)
                || MicrogBillingIdentityPolicy.isGoogleComponent(pkg);
    }

    /** Return a same-physical-user authority; leave explicit cross-user access to Android. */
    public static String localAuthority(String raw, int physicalUser) {
        if (raw == null || raw.isEmpty() || physicalUser < 0) return null;
        int separator = raw.indexOf('@');
        String authority = raw;
        if (separator >= 0) {
            String user = raw.substring(0, separator);
            if (user.isEmpty() || user.length() > 9) return null;
            for (int i = 0; i < user.length(); i++) if (user.charAt(i) < '0' || user.charAt(i) > '9') return null;
            if (Integer.parseInt(user) != physicalUser) return null;
            authority = raw.substring(separator + 1);
        }
        if (authority.isEmpty() || authority.indexOf('@') >= 0 || authority.indexOf('/') >= 0
                || authority.indexOf(':') >= 0 || authority.indexOf(';') >= 0
                || "com.android.externalstorage.documents".equals(authority)) return null;
        for (int i = 0; i < authority.length(); i++) if (Character.isWhitespace(authority.charAt(i))) return null;
        return authority;
    }
}
