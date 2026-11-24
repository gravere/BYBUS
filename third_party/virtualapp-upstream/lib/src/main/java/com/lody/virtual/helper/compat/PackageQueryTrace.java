package com.lody.virtual.helper.compat;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Bounded, argument-free summaries. Never retain package names, UIDs or exception messages. */
public final class PackageQueryTrace {
    private final ArrayDeque<String> recent = new ArrayDeque<>(32);
    private int total;
    private final Map<String, Integer> counts = new LinkedHashMap<>();
    private long timelineStart = -1;
    private int timelineCount;

    public static final class PendingQuery {
        public final int sequence;
        public final String method;
        public final boolean proxied;
        public final long started;
        private PendingQuery(int sequence, String method, boolean proxied, long started) {
            this.sequence = sequence; this.method = method; this.proxied = proxied; this.started = started;
        }
        public String beginning() { return prefix() + " phase=begin"; }
        private String prefix() { return "seq=" + sequence + " method=" + method + " route=" + (proxied ? "container" : "platform"); }
        public String finished(Object result, Throwable error, long now) {
            return prefix() + " phase=end durationMs=" + Math.max(0, now - started)
                    + " result=" + outcome(method, result, error);
        }
    }

    /** A bounded startup trace, even when the process dies before a Java safety dialog. */
    public synchronized PendingQuery begin(String method, boolean proxied, long now) {
        if (!supported(method) || now < 0) return null;
        if (timelineStart < 0) timelineStart = now;
        if (timelineCount >= 64 || now - timelineStart < 0 || now - timelineStart > 90_000) return null;
        return new PendingQuery(++timelineCount, method, proxied, now);
    }

    public synchronized void record(String method, boolean proxied, Object result, Throwable error) {
        if (!supported(method)) return;
        String route = method + ":" + (proxied ? "container" : "platform");
        int count = counts.containsKey(route) ? counts.get(route) : 0;
        counts.put(route, count == Integer.MAX_VALUE ? count : count + 1);
        String outcome = outcome(method, result, error);
        if (recent.size() == 32) recent.removeFirst();
        recent.addLast(route + ":" + outcome);
        if (total < Integer.MAX_VALUE) total++;
    }

    private static String outcome(String method, Object result, Throwable error) {
        String outcome;
        if (error != null) {
            outcome = error instanceof SecurityException ? "security_error"
                    : error instanceof IllegalArgumentException ? "argument_error"
                    : error instanceof ClassCastException ? "type_error"
                    : error instanceof NullPointerException ? "null_error" : "exception";
        } else if (result == null) {
            outcome = "null";
        } else if (result instanceof Integer) {
            int value = (Integer) result;
            // Preserve result codes, never positive UIDs.
            outcome = method.equals("getPackageUid") ? (value < 0 ? "absent" : "uid")
                    : value >= -8 && value <= 8 ? "code_" + value : "integer";
        } else if (result instanceof String[]) {
            outcome = ((String[]) result).length == 0 ? "empty" : "packages";
        } else if ((method.equals("getInstalledPackages") || method.equals("getInstalledApplications"))
                && result instanceof List) {
            int size = ((List<?>) result).size();
            outcome = size == 0 ? "list_empty" : size == 1 ? "list_single" : "list_multiple";
        } else {
            outcome = "present";
        }
        return outcome;
    }

    public synchronized String snapshot() {
        StringBuilder summary = new StringBuilder("calls=").append(total).append(" totals=");
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (summary.charAt(summary.length() - 1) != '=') summary.append(',');
            summary.append(entry.getKey()).append(':').append(entry.getValue());
        }
        return summary.append(" recent=").append(String.join(",", recent)).toString();
    }

    private static boolean supported(String method) {
        if (method == null) return false;
        switch (method) {
            case "getPackageInfo": case "getPackageInfoVersioned": case "getApplicationInfo":
            case "getPackageUid": case "getPackagesForUid": case "getNameForUid":
            case "getInstalledPackages": case "getInstalledApplications":
            case "getInstallerPackageName": case "getInstallSourceInfo": case "getApplicationEnabledSetting":
            case "getComponentEnabledSetting": case "setComponentEnabledSetting":
            case "checkPermission": case "checkUidPermission":
            case "checkSignatures": case "checkUidSignatures": return true;
            default: return false;
        }
    }
}
