package com.lody.virtual.helper.compat;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import java.io.File;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Verifies the Android publisher, complete APK set and native load alignment. */
public final class LimbusNativeProfile {
    private LimbusNativeProfile() {}
    public static final String LOADABLE = "official-arm64";
    public static final String GAME_PACKAGE = "com.ProjectMoon.LimbusCompany";
    public static final String OFFICIAL_CERTIFICATE = "9ea88fdc27195dfef99050f88a889cad86cdf1a80faca2e22d6464d47f973145";
    public static final int SIGNATURE_FLAGS = Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    private static String cachedIdentity;
    private static Match cachedMatch;

    public static final class Match {
        public final String id;
        public final String failureReason;
        private Match(String id, String failureReason) {
            this.id = id;
            this.failureReason = failureReason;
        }
        public boolean approved() { return !id.isEmpty(); }
    }
    private static final Match NONE = new Match("", "");

    public static String findApproved(Context host, PackageInfo info, ApplicationInfo app) {
        return inspect(host, info, app).id;
    }

    public static synchronized Match inspect(Context host, PackageInfo info, ApplicationInfo app) {
        try {
            if (!GAME_PACKAGE.equals(info.packageName) || !GAME_PACKAGE.equals(app.packageName)
                    || !hasOfficialSigner(info)) {
                android.util.Log.w("LimbusVA", "Native qualification rejected: package identity or official signer");
                return NONE;
            }
            long version = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            long page = android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE);
            if (!android.os.Process.is64Bit() || !LimbusNativeStructure.supportsEnvironment(
                    Build.VERSION.SDK_INT, "arm64-v8a", page)) return NONE;
            ArrayList<String> paths = paths(app);
            String identity = version + ":" + info.lastUpdateTime + ":" + Build.VERSION.SDK_INT + ":" + page;
            for (String path : paths) {
                File file = new File(path);
                if (!file.isFile() || file.length() <= 0) return NONE;
                identity += "|" + path.length() + ":" + path + ":" + file.length() + ":" + file.lastModified();
            }
            if (identity.equals(cachedIdentity)) return cachedMatch;
            try {
                requireLibraryAlignment(paths, page, "com.inka.appsealing.AppSealingApplication".equals(app.className));
            } catch (java.io.IOException invalid) {
                return new Match("", "游戏安装包缺少启动组件，或不满足本机 " + (page / 1024) + " KB 加载条件。请核对已安装的官方游戏。");
            }
            Match result = new Match(LOADABLE, "");
            cachedIdentity = identity;
            cachedMatch = result;
            return result;
        } catch (Exception error) {
            // Never retain a partial profile or layout following an interrupted package update.
            android.util.Log.w("LimbusVA", "Native qualification rejected: " + error.getClass().getSimpleName());
            return NONE;
        }
    }

    static void requireLibraryAlignment(java.util.List<String> paths, long page, boolean declaresProtection) throws Exception {
        java.util.HashSet<String> found = new java.util.HashSet<>();
        for (String path : paths) try (ZipFile zip = new ZipFile(path)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("lib/arm64-v8a/") || !name.endsWith(".so")) continue;
                if (!found.add(name) || found.size() > 128)
                    throw new java.io.IOException("Duplicate or excessive native libraries");
                try (InputStream input = zip.getInputStream(entry)) {
                    LimbusNativeStructure.requireLoadAlignment(input, entry.getSize(), page);
                }
            }
        }
        if (declaresProtection && !found.contains("lib/arm64-v8a/libcovault-appsec.so"))
            throw new java.io.IOException("Missing declared startup library");
        for (String required : new String[]{"libil2cpp.so", "libunity.so", "libmain.so"})
            if (!found.contains("lib/arm64-v8a/" + required))
                throw new java.io.IOException("Incomplete native libraries");
    }

    @SuppressWarnings("deprecation")
    private static boolean hasOfficialSigner(PackageInfo info) throws Exception {
        Signature[] signers = Build.VERSION.SDK_INT >= 28 && info.signingInfo != null
                ? info.signingInfo.getApkContentsSigners() : info.signatures;
        if (signers == null || signers.length != 1) return false;
        return OFFICIAL_CERTIFICATE.equals(LimbusNativeStructure.hex(MessageDigest.getInstance("SHA-256")
                .digest(signers[0].toByteArray())));
    }

    private static ArrayList<String> paths(ApplicationInfo app) throws Exception {
        ArrayList<String> paths = new ArrayList<>();
        paths.add(app.sourceDir);
        if (app.splitSourceDirs != null) Collections.addAll(paths, app.splitSourceDirs);
        if (paths.size() > 32 || paths.contains(null) || new java.util.HashSet<>(paths).size() != paths.size())
            throw new java.io.IOException("Invalid APK sources");
        Collections.sort(paths);
        return paths;
    }
}
