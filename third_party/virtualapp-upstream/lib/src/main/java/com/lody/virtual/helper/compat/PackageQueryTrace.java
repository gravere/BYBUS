package com.lody.virtual.helper.compat;

import java.util.ArrayDeque;

/** Bounded, argument-free summaries. Never retain package names, UIDs or exception messages. */
public final class PackageQueryTrace {
    private final ArrayDeque<String> recent = new ArrayDeque<>(32);
    private int total;

    public synchronized void record(String method, boolean proxied, Object result, Throwable error) {
        if (!supported(method)) return;
        String outcome;
        if (error != null) {
            outcome = error instanceof SecurityException ? "security_error" : "exception";
        } else if (result == null) {
            outcome = "null";
        } else if (result instanceof Integer) {
            int value = (Integer) result;
            // Preserve result codes, never positive UIDs.
            outcome = method.equals("getPackageUid") ? (value < 0 ? "absent" : "uid")
                    : value >= -8 && value <= 8 ? "code_" + value : "integer";
        } else if (result instanceof String[]) {
            outcome = ((String[]) result).length == 0 ? "empty" : "packages";
        } else {
            outcome = "present";
        }
        if (recent.size() == 32) recent.removeFirst();
        recent.addLast(method + ":" + (proxied ? "container" : "platform") + ":" + outcome);
        if (total < Integer.MAX_VALUE) total++;
    }

    public synchronized String snapshot() {
        return "calls=" + total + " recent=" + String.join(",", recent);
    }

    private static boolean supported(String method) {
        if (method == null) return false;
        switch (method) {
            case "getPackageInfo": case "getPackageInfoVersioned": case "getApplicationInfo":
            case "getPackageUid": case "getPackagesForUid": case "getNameForUid":
            case "getInstalledPackages": case "getInstalledApplications":
            case "checkPermission": case "checkUidPermission":
            case "checkSignatures": case "checkUidSignatures": return true;
            default: return false;
        }
    }
}
