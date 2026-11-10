package com.lody.virtual.helper.compat;

/** A device's preferred ISA may differ from the ABI chosen for the installed engine. */
public final class ContainerAbiCompat {
    private ContainerAbiCompat() {}

    public static String primaryAbi(String engineAbi, String[] supportedAbis, String fallbackAbi) {
        if (engineAbi != null && supportedAbis != null) {
            for (String supported : supportedAbis) {
                if (engineAbi.equals(supported)) return engineAbi;
            }
        }
        return fallbackAbi;
    }
}
