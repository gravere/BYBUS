package com.lody.virtual.server.pm;

import android.content.Context;
import android.os.Build;
import android.os.Parcel;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.DurableFile;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.am.BroadcastSystem;
import com.lody.virtual.server.pm.parser.PackageParserEx;
import org.json.JSONObject;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Production scan/Parcel/write paths on an explicitly enabled disposable emulator only. */
public final class PackageRetentionProbe {
    public static JSONObject inspect(Context context) throws Exception {
        if (!(Build.HARDWARE.equals("ranchu") || Build.HARDWARE.equals("goldfish"))
                || !new File(context.getFilesDir(), "package-retention-probe.allowed").isFile()
                || !VirtualCore.get().isServerProcess()) {
            throw new SecurityException("Disposable emulator server and explicit marker required");
        }
        VAppManagerService service = VAppManagerService.get();
        synchronized (service) { return inspectLocked(context, service); }
    }

    @SuppressWarnings("unchecked")
    private static JSONObject inspectLocked(Context context, VAppManagerService service) throws Exception {
        String missing = "io.github.gravere.retention.fixture";
        String host = context.getPackageName();
        require(PackageCacheManager.get(missing) == null && PackageCacheManager.get(host) == null, "unused fixture names");
        Field layerField = VAppManagerService.class.getDeclaredField("mPersistenceLayer");
        layerField.setAccessible(true);
        PackagePersistenceLayer live = (PackagePersistenceLayer) layerField.get(service);
        Field retainedField = PackagePersistenceLayer.class.getDeclaredField("retained");
        retainedField.setAccessible(true);
        Map<String, PackageSetting> liveRecords = (Map<String, PackageSetting>) retainedField.get(live);
        Map<String, PackageSetting> savedRecords = new LinkedHashMap<>(liveRecords);
        File dir = Files.createTempDirectory(context.getCacheDir().toPath(), "package-retention-").toFile();
        File missingRoot = VEnvironment.getDataUserPackageDirectory(0, missing);
        File externalRoot = VEnvironment.getExternalStorageAppDataDir(0, missing);
        File hostCache = VEnvironment.getPackageCacheFile(host);
        File hostSignature = VEnvironment.getSignatureFile(host);
        byte[] oldCache = readOptional(hostCache), oldSignature = readOptional(hostSignature);
        File[] markers = {new File(missingRoot, "files/resource.probe"), new File(missingRoot, "shared_prefs/login.probe"),
                new File(externalRoot, "files/resource.probe")};
        byte[] marker = {4, 8, 15, 16, 23, 42};
        JSONObject checks = new JSONObject();
        try {
            for (File file : markers) {
                require(!file.exists(), "fresh data marker");
                DurableFile.write(file, marker);
            }
            PackageSetting missingSetting = setting(missing);
            check(checks, "missing_apk_scan_deferred", !service.loadPackage(missingSetting));
            for (File file : markers) require(Arrays.equals(marker, Files.readAllBytes(file.toPath())), "guest data survived failed scan");
            check(checks, "resources_and_login_survive_failed_scan", true);
            check(checks, "failed_scan_identity_retained", live.retainedSetting(missing) == missingSetting);

            File registry = new File(dir, "packages.ini");
            DurableFile.write(registry, encode(missingSetting));
            PackagePersistenceLayer layer = new PackagePersistenceLayer(service, registry);
            layer.read();
            layer.save();
            PackagePersistenceLayer reread = new PackagePersistenceLayer(service, registry);
            reread.read();
            PackageSetting restored = reread.retainedSetting(missing);
            check(checks, "deferred_registration_survives_save_and_restart", restored != null
                    && restored.appId == missingSetting.appId && restored.firstInstallTime == 100
                    && restored.isInstalled(0) && restored.isLaunched(0) && restored.isHidden(7));

            byte[] valid = encode(missingSetting);
            for (String fault : new String[]{"bad_magic", "truncated_record", "future_version"}) {
                byte[] broken = valid.clone();
                if (fault.equals("bad_magic")) broken[4] ^= 1;
                if (fault.equals("truncated_record")) broken = Arrays.copyOf(valid, valid.length - 12);
                if (fault.equals("future_version")) {
                    Parcel p = Parcel.obtain();
                    try { p.unmarshall(broken, 0, broken.length); p.setDataPosition(0); p.createCharArray(); p.writeInt(99); broken=p.marshall(); }
                    finally { p.recycle(); }
                }
                DurableFile.write(registry, broken);
                PackagePersistenceLayer damaged = new PackagePersistenceLayer(service, registry);
                damaged.read();
                damaged.save();
                check(checks, fault + "_preserves_registry", !damaged.canWrite()
                        && Arrays.equals(broken, Files.readAllBytes(registry.toPath())));
                for (File file : markers) require(Arrays.equals(marker, Files.readAllBytes(file.toPath())), "guest data survived registry error");
            }

            // Outside-source installs intentionally have no internal base APK.
            require(!VEnvironment.getPackageResourcePath(host).isFile(), "no copied host APK");
            DurableFile.write(hostCache, new byte[]{0,0,0,0});
            PackageSetting hostSetting = setting(host);
            check(checks, "outside_apk_reparsed_from_real_source", service.loadPackage(hostSetting)
                    && PackageParserEx.readPackageCache(host) != null);
            check(checks, "reparse_preserves_virtual_identity", PackageCacheManager.getSetting(host) == hostSetting
                    && hostSetting.appId == 19420 && hostSetting.isHidden(7));
            byte[] cacheBytes = Files.readAllBytes(hostCache.toPath());
            try { DurableFile.write(hostCache, output -> { output.write(1); throw new IOException("injected interrupted write"); }); }
            catch (IOException expected) { }
            check(checks, "interrupted_write_retains_previous_cache", Arrays.equals(cacheBytes, Files.readAllBytes(hostCache.toPath()))
                    && PackageParserEx.readPackageCache(host) != null && !new File(hostCache + ".new").exists());
            return new JSONObject().put("passed", true).put("checks", checks).put("check_count", checks.length());
        } finally {
            BroadcastSystem.get().stopApp(host);
            PackageCacheManager.remove(host);
            PackageCacheManager.remove(missing);
            liveRecords.clear(); liveRecords.putAll(savedRecords);
            restore(hostCache, oldCache); restore(hostSignature, oldSignature);
            for (File file : markers) Files.deleteIfExists(file.toPath());
            for (File file : dir.listFiles()) Files.deleteIfExists(file.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }

    private static PackageSetting setting(String name) {
        PackageSetting setting = new PackageSetting();
        setting.packageName=name; setting.appMode=InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK;
        setting.appId=19420; setting.flag=PackageSetting.FLAG_RUN_BOTH_32BIT_64BIT;
        setting.firstInstallTime=100; setting.lastUpdateTime=200;
        setting.setUserState(0, true, false, true);
        setting.setUserState(7, true, true, true);
        return setting;
    }

    private static byte[] encode(PackageSetting setting) {
        Parcel p=Parcel.obtain();
        try { p.writeCharArray(new char[]{'v','p','k','g'}); p.writeInt(PackageSetting.CURRENT_VERSION);
            p.writeInt(1); setting.writeToParcel(p,0); return p.marshall(); }
        finally { p.recycle(); }
    }
    private static byte[] readOptional(File file) throws IOException { return file.isFile()?Files.readAllBytes(file.toPath()):null; }
    private static void restore(File file, byte[] bytes) throws IOException {
        if (bytes==null) Files.deleteIfExists(file.toPath()); else DurableFile.write(file,bytes);
    }
    private static void require(boolean ok,String message) { if(!ok)throw new IllegalStateException(message); }
    private static void check(JSONObject checks,String name,boolean ok) throws Exception { checks.put(name,ok); require(ok,name); }
}
