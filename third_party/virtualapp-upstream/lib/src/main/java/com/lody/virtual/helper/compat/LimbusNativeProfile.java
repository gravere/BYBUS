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
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Exact legacy profiles plus a shipped, independently verified structural family.
 * A new version number alone grants no compatibility. Original verifier CRCs remain authoritative.
 */
public final class LimbusNativeProfile {
    private LimbusNativeProfile() {}
    public static final String GAME_PACKAGE = "com.ProjectMoon.LimbusCompany";
    public static final String OFFICIAL_CERTIFICATE = "9ea88fdc27195dfef99050f88a889cad86cdf1a80faca2e22d6464d47f973145";
    public static final int SIGNATURE_FLAGS = Build.VERSION.SDK_INT >= 28
            ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES;
    private static String cachedIdentity;
    private static Match cachedMatch;

    public static final class Match {
        public final String id;
        public final String layout;
        private Match(String id, String layout) { this.id = id; this.layout = layout; }
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
            if (!android.os.Process.is64Bit()) return NONE;
            ArrayList<String> paths = paths(app);
            String identity = version + ":" + info.lastUpdateTime + ":" + Build.VERSION.SDK_INT + ":" + page;
            for (String path : paths) {
                File file = new File(path);
                if (!file.isFile() || file.length() <= 0) return NONE;
                identity += "|" + path.length() + ":" + path + ":" + file.length() + ":" + file.lastModified();
            }
            if (identity.equals(cachedIdentity)) return cachedMatch;
            LimbusNativeStructure.Image protection = library(paths, "libcovault-appsec.so", true);
            LimbusNativeStructure.Image game = library(paths, "libil2cpp.so", false);
            Match result = NONE;
            // 471 retains its separately verified transient-instruction workaround.
            if (version != 471) {
                String layout = LimbusNativeStructure.qualify(protection, game,
                        Build.VERSION.SDK_INT, "arm64-v8a", page);
                if (!layout.isEmpty()) result = new Match(LimbusNativeStructure.FAMILY, layout);
            }
            if (!result.approved()) try (InputStream input = host.getAssets().open("compatibility/native-profiles.properties")) {
                Properties properties = new Properties();
                properties.load(input);
                String exact = selectVerifiedProfile(properties, version, Build.VERSION.SDK_INT,
                        "arm64-v8a", page, name -> "libcovault-appsec.so".equals(name) ? protection.sha256 : game.sha256);
                if (exact.endsWith("-full")) result = new Match(exact, "");
            }
            cachedIdentity = identity;
            cachedMatch = result;
            return result;
        } catch (Exception error) {
            // Never retain a partial profile or layout following an interrupted package update.
            android.util.Log.w("LimbusVA", "Native qualification rejected: " + error.getClass().getSimpleName());
            return NONE;
        }
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

    private static LimbusNativeStructure.Image library(ArrayList<String> paths, String name,
                                                       boolean protection) throws Exception {
        LimbusNativeStructure.Image result = null;
        for (String path : paths) try (ZipFile zip = new ZipFile(path)) {
            ZipEntry entry = zip.getEntry("lib/arm64-v8a/" + name);
            if (entry == null) continue;
            if (result != null) throw new java.io.IOException("Duplicate native library");
            try (InputStream input = zip.getInputStream(entry)) {
                result = LimbusNativeStructure.read(input, entry.getSize(), protection);
            }
        }
        if (result == null) throw new java.io.IOException("Missing native library");
        return result;
    }

    /* Pure legacy selector retained for regression coverage and independently approved old builds. */
    public interface LibraryHashes { String sha256(String library) throws Exception; }

    public static String selectVerifiedProfile(Properties properties, long version, int sdk,
                                               String abi, long pageSize, LibraryHashes hashes) {
        try {
            int count = Integer.parseInt(properties.getProperty("profiles.count", "0"));
            if (count < 0 || count > 32) return "";
            for (int i = 0; i < count; i++) {
                String prefix = "profile." + i + ".";
                String id = properties.getProperty(prefix + "id", "");
                if (!id.matches("[a-zA-Z0-9._-]{1,64}")) continue;
                if (!Long.toString(version).equals(properties.getProperty(prefix + "versionCode"))) continue;
                int min = Integer.parseInt(properties.getProperty(prefix + "minSdk"));
                int max = Integer.parseInt(properties.getProperty(prefix + "maxSdk"));
                if (sdk < min || sdk > max) continue;
                if (!abi.equals(properties.getProperty(prefix + "abi"))) continue;
                if (!Long.toString(pageSize).equals(properties.getProperty(prefix + "pageSize"))) continue;
                String covault = properties.getProperty(prefix + "libcovault.sha256", "");
                String il2cpp = properties.getProperty(prefix + "libil2cpp.sha256", "");
                if (!covault.matches("[a-f0-9]{64}") || !il2cpp.matches("[a-f0-9]{64}")) continue;
                if (covault.equals(hashes.sha256("libcovault-appsec.so")) &&
                        il2cpp.equals(hashes.sha256("libil2cpp.so"))) return id;
            }
        } catch (Exception ignored) { }
        return "";
    }

}
