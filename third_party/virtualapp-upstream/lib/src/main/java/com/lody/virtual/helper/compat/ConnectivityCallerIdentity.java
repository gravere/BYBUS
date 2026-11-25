package com.lody.virtual.helper.compat;

import java.lang.reflect.Method;

/** Map a container caller only at the real connectivity service boundary. */
public final class ConnectivityCallerIdentity {
    private ConnectivityCallerIdentity() { }

    public static boolean rewriteCapabilitiesCaller(Method method, Object[] args,
                                                     String guestPackage, String hostPackage) {
        if ((!MicrogBillingIdentityPolicy.isGoogleComponent(guestPackage)
                && !"com.ProjectMoon.LimbusCompany".equals(guestPackage))
                || hostPackage == null || hostPackage.isEmpty() || hostPackage.equals(guestPackage)
                || method == null || args == null
                || !"android.net.IConnectivityManager".equals(method.getDeclaringClass().getName())
                || !"getNetworkCapabilities".equals(method.getName())
                || !"android.net.NetworkCapabilities".equals(method.getReturnType().getName())) return false;
        Class<?>[] types = method.getParameterTypes();
        // Android 11: Network + callingPackage; Android 12+: also attributionTag.
        // Older APIs have no caller argument. Unknown OEM signatures pass through unchanged.
        if ((types.length != 2 && types.length != 3) || args.length != types.length
                || !"android.net.Network".equals(types[0].getName()) || types[1] != String.class
                || (types.length == 3 && types[2] != String.class)
                || !guestPackage.equals(args[1])) return false;
        args[1] = hostPackage;
        return true;
    }
}
