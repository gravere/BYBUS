package com.lody.virtual.helper.compat;

/** A device's preferred ISA may differ from the ABI chosen for the installed engine. */
public final class ContainerAbiCompat {
    private ContainerAbiCompat() {}

    /** ARM libc hooks do not intercept the x86 ART/libcore file system. */
    public static boolean requiresHostContextPaths(String[] platformAbis) {
        if (platformAbis == null || platformAbis.length == 0) return false;
        return "x86".equals(platformAbis[0]) || "x86_64".equals(platformAbis[0]);
    }

    public static String primaryAbi(String engineAbi, String[] supportedAbis, String fallbackAbi) {
        if (engineAbi != null && supportedAbis != null) {
            for (String supported : supportedAbis) {
                if (engineAbi.equals(supported)) return engineAbi;
            }
        }
        return fallbackAbi;
    }
}
