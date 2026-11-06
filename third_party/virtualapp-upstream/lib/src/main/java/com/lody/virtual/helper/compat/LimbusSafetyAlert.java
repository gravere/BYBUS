package com.lody.virtual.helper.compat;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Metadata only: never retain the original message, arbitrary extras or the protection report. */
public final class LimbusSafetyAlert {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final String ALERT = "com.inka.appsealing.AppSealingAlertDialog";
    private static final int MAX_MESSAGE = 4096;
    private static final Pattern CODE = Pattern.compile(
            "(?i)(?:error\\s*(?:code)?|code|コード|코드|错误(?:码|代码|编号)?|錯誤(?:碼|代碼)?)"
                    + "\\s*[:：#=]?\\s*[\\[(]?([1-9][0-9]{4})(?![0-9])"
                    + "|[\\[(]\\s*([1-9][0-9]{4})\\s*[\\])]");

    private LimbusSafetyAlert() { }

    public static boolean matches(String callerPackage, String callerProcess,
                                  String targetPackage, String targetClass, String action) {
        return GAME.equals(callerPackage) && GAME.equals(callerProcess)
                && isTarget(targetPackage, targetClass, action);
    }

    public static boolean isTarget(String targetPackage, String targetClass, String action) {
        return GAME.equals(targetPackage) && ALERT.equals(targetClass) && "controller".equals(action);
    }

    public static Summary summarize(int type, String message) {
        String bounded = message == null ? "" : message.substring(0, Math.min(message.length(), MAX_MESSAGE));
        Set<String> codes = new LinkedHashSet<>();
        Matcher matcher = CODE.matcher(bounded);
        while (codes.size() < 8 && matcher.find()) {
            codes.add(matcher.group(1) != null ? matcher.group(1) : matcher.group(2));
        }
        String lower = bounded.toLowerCase(Locale.ROOT);
        String category = "unknown";
        if (lower.contains("rooted") || lower.contains("rooting")) category = "root";
        else if (lower.contains("emulator")) category = "emulator";
        else if (lower.contains("debugger") || lower.contains("debugging")) category = "debugger";
        else if (lower.contains("integrity") || lower.contains("tamper")) category = "integrity";
        else if (lower.contains("blacklist")) category = "blacklist";
        else if (lower.contains("work profile")) category = "work_profile";
        return new Summary(type >= 0 && type <= 255 ? type : -1, String.join(",", codes), category,
                !bounded.isEmpty(), message != null && message.length() > MAX_MESSAGE);
    }

    public static final class Summary {
        public final int type;
        public final String codes;
        public final String category;
        public final boolean messagePresent;
        public final boolean messageTruncated;

        private Summary(int type, String codes, String category, boolean present, boolean truncated) {
            this.type = type;
            this.codes = codes;
            this.category = category;
            this.messagePresent = present;
            this.messageTruncated = truncated;
        }

        @Override public String toString() {
            return "type=" + type + " codes=" + (codes.isEmpty() ? "unknown" : codes)
                    + " category=" + category + " messagePresent=" + messagePresent
                    + " messageTruncated=" + messageTruncated;
        }
    }
}
