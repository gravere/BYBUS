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
        public final String failureReason;
        private Match(String id, String layout) { this(id, layout, ""); }
        private Match(String id, String layout, String reason) {
            this.id = id; this.layout = layout; this.failureReason = reason;
        }
        public boolean approved() { return !id.isEmpty(); }
    }
    private static final Match NONE = new Match("", "");

    public static String findApproved(Context host, PackageInfo info, ApplicationInfo app) {
        return inspect(host, info, app).id;
    }

    public static Match inspect(Context host, PackageInfo info, ApplicationInfo app) {
        return inspect(host, info, app, true);
    }

    /** Staging filenames can be reused. Inspect this archive's own manifest and files every time. */
    public static Match inspectArchive(Context host, PackageInfo archive, String base, String[] splits) {
        if (archive.applicationInfo == null) return NONE;
        ApplicationInfo app = new ApplicationInfo(archive.applicationInfo);
        app.sourceDir = base;
        app.splitSourceDirs = splits;
        return inspect(host, archive, app, false);
    }

    private static synchronized Match inspect(Context host, PackageInfo info, ApplicationInfo app, boolean cacheInstalled) {
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
            boolean declaresProtection = "com.inka.appsealing.AppSealingApplication".equals(app.className);
            String identity = version + ":" + info.lastUpdateTime + ":" + Build.VERSION.SDK_INT + ":" + page
                    + ":" + declaresProtection;
            for (String path : paths) {
                File file = new File(path);
                if (!file.isFile() || file.length() <= 0) return NONE;
                identity += "|" + path.length() + ":" + path + ":" + file.length() + ":" + file.lastModified();
            }
            if (cacheInstalled && identity.equals(cachedIdentity)) return cachedMatch;
            String durableIdentity = cacheInstalled ? installedIdentity(host, identity, paths) : null;
            Match saved = durableIdentity == null ? null : readInstalledMatch(host, durableIdentity);
            if (saved != null) { cachedIdentity = identity; cachedMatch = saved; return saved; }
            try {
                requireLibraryAlignment(paths, page, declaresProtection);
            } catch (java.io.IOException invalid) {
                return new Match("", "", "游戏安装包的原生库不满足当前设备的 " + (page / 1024) +
                        " KB 加载条件，请先更新或重新安装官方游戏；已有容器数据保留。");
            }
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
            if (durableIdentity != null && !durableIdentity.equals(installedIdentity(host, identity, paths))) return NONE;
            if (cacheInstalled) {
                cachedIdentity = identity;
                cachedMatch = result;
                if (durableIdentity != null && result.approved()) saveInstalledMatch(host, durableIdentity, result);
            }
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

    // Cache only immutable APKs installed by Android, never user-selected/staging archives.
    private static String installedIdentity(Context host, String identity, ArrayList<String> paths) {
        try {
            StringBuilder value = new StringBuilder("installed-profile-v1|").append(identity)
                    .append('|').append(Build.FINGERPRINT).append('|').append(host.getApplicationInfo().sourceDir);
            for (String path : paths) {
                File file = new File(path);
                if (!file.getCanonicalPath().equals(path) || !path.startsWith("/data/app/") || file.canWrite()) return null;
                android.system.StructStat stat = android.system.Os.stat(path);
                if (stat.st_uid == android.os.Process.myUid()) return null;
                value.append('|').append(stat.st_dev).append(':').append(stat.st_ino).append(':')
                    .append(stat.st_ctime).append(':').append(stat.st_mtime).append(':').append(stat.st_size);
            }
            return LimbusNativeStructure.hex(MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes("UTF-8")));
        } catch (Exception unavailable) { return null; }
    }
    private static File profileCache(Context host) { return new File(host.getFilesDir(), "installed-native-profile.properties"); }
    private static String receiptDigest(String key, String id, String layout) throws Exception {
        return LimbusNativeStructure.hex(MessageDigest.getInstance("SHA-256").digest((key+"|"+id+"|"+layout).getBytes("UTF-8")));
    }
    private static Match readInstalledMatch(Context host, String identity) {
        try {
            File file = profileCache(host);
            if (!file.isFile() || file.length() > 16384) return null;
            Properties values = new Properties();
            try (InputStream input = new java.io.FileInputStream(file)) { values.load(input); }
            String id = values.getProperty("id", ""), layout = values.getProperty("layout", "");
            if (!identity.equals(values.getProperty("identity")) || !id.matches("[a-zA-Z0-9._-]{1,64}") || layout.length() > 8192 ||
                    !receiptDigest(identity,id,layout).equals(values.getProperty("digest"))) return null;
            return new Match(id, layout);
        } catch (Exception invalid) { return null; }
    }
    private static void saveInstalledMatch(Context host, String identity, Match match) {
        File file = profileCache(host), temporary = new File(file.getPath()+".tmp");
        try {
            Properties values = new Properties(); values.setProperty("identity", identity);
            values.setProperty("id", match.id); values.setProperty("layout", match.layout);
            values.setProperty("digest", receiptDigest(identity,match.id,match.layout));
            try (java.io.FileOutputStream output = new java.io.FileOutputStream(temporary)) {
                values.store(output, "Verified installed APK structure"); output.getFD().sync();
            }
            if (!temporary.renameTo(file)) temporary.delete();
        } catch (Exception ignored) { temporary.delete(); }
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
