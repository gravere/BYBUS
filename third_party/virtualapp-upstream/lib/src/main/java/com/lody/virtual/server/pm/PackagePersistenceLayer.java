package com.lody.virtual.server.pm;

import android.os.Parcel;

import com.lody.virtual.helper.PersistenceLayer;
import com.lody.virtual.os.VEnvironment;
import com.lody.virtual.server.pm.legacy.PackageSettingV1;
import com.lody.virtual.server.pm.parser.VPackage;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.lody.virtual.remote.InstalledAppInfo.MODE_APP_COPY_APK;
import static com.lody.virtual.remote.InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK;

/**
 * @author Lody
 */

class PackagePersistenceLayer extends PersistenceLayer {

    private static final char[] MAGIC = {'v', 'p', 'k', 'g'};
    private static final int CURRENT_VERSION = PackageSetting.CURRENT_VERSION;
    public boolean changed = false;

    private VAppManagerService mService;
    // Unavailable packages are still installed records. Never drop them just
    // because their APK/cache could not be read during this process's startup.
    private final Map<String, PackageSetting> retained = new LinkedHashMap<>();
    private boolean readFailed;

    PackagePersistenceLayer(VAppManagerService service) {
        this(service, VEnvironment.getPackageListFile());
    }

    PackagePersistenceLayer(VAppManagerService service, java.io.File file) {
        super(file);
        mService = service;
    }

    @Override
    public int getCurrentVersion() {
        return CURRENT_VERSION;
    }

    @Override
    public void writeMagic(Parcel p) {
        p.writeCharArray(MAGIC);
    }

    @Override
    public boolean verifyMagic(Parcel p) {
        char[] magic = p.createCharArray();
        return Arrays.equals(magic, MAGIC);
    }


    @Override
    public void writePersistenceData(Parcel p) {
        if (readFailed) throw new IllegalStateException("Package records could not be read; original data retained");
        synchronized (PackageCacheManager.PACKAGE_CACHE) {
            Map<String, PackageSetting> records = new LinkedHashMap<>(retained);
            for (VPackage pkg : PackageCacheManager.PACKAGE_CACHE.values()) {
                PackageSetting ps = (PackageSetting) pkg.mExtras;
                records.put(ps.packageName, ps);
            }
            p.writeInt(records.size());
            for (PackageSetting ps : records.values()) ps.writeToParcel(p, 0);
        }
    }

    boolean canWrite() { return !readFailed; }

    PackageSetting retainedSetting(String packageName) { return retained.get(packageName); }

    void retain(PackageSetting setting) { retained.put(setting.packageName, setting); }

    void forget(String packageName) { retained.remove(packageName); }

    @Override
    public void readPersistenceData(Parcel p, int version) {
        if (version < 1 || version > CURRENT_VERSION) {
            throw new IllegalStateException("Unsupported package registry version: " + version);
        }
        if (p.dataAvail() < 4) throw new IllegalStateException("Truncated package registry");
        int count = p.readInt();
        if (count < 0 || count > 10000) throw new IllegalStateException("Invalid package record count");
        Map<String, PackageSetting> decoded = new LinkedHashMap<>();
        while (count-- > 0) {
            PackageSetting setting;
            if (version < PackageSetting.FIRST_V2_VERSION) {
                changed = true;
                PackageSettingV1 v1 = new PackageSettingV1();
                v1.readFromParcel(p, version);
                PackageSetting v2 = new PackageSetting();
                v2.packageName = v1.packageName;
                v2.appMode = v1.notCopyApk ? MODE_APP_USE_OUTSIDE_APK : MODE_APP_COPY_APK;
                v2.appId = v1.appId;
                v2.flag = v1.flag;
                v2.userState = v1.userState;
                v2.firstInstallTime = System.currentTimeMillis();
                v2.lastUpdateTime = v2.firstInstallTime;
                setting = v2;
            } else {
                setting = new PackageSetting(version, p);
            }
            if (setting.packageName == null || !setting.packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)*")
                    || setting.appId <= 0 || setting.userState == null
                    || (setting.appMode != MODE_APP_COPY_APK && setting.appMode != MODE_APP_USE_OUTSIDE_APK)
                    || setting.flag < 0 || setting.flag > PackageSetting.FLAG_RUN_64BIT
                    || decoded.put(setting.packageName, setting) != null) {
                throw new IllegalStateException("Invalid package record");
            }
        }
        if (p.dataAvail() != 0) throw new IllegalStateException("Unexpected package registry bytes");
        // A later broken record must not leave a partially applied registry.
        retained.clear();
        retained.putAll(decoded);
        readFailed = false;
        for (PackageSetting setting : decoded.values()) mService.loadPackage(setting);
    }

    @Override
    public void onPersistenceFileDamage() {
        readFailed = true;
        changed = false;
        com.lody.virtual.helper.utils.VLog.e("PackagePersistenceLayer", "Invalid package registry; keeping registry and guest data");
    }

    @Override
    protected void onPersistenceReadFailure(Exception error) {
        readFailed = true;
        changed = false;
        com.lody.virtual.helper.compat.ContainerRuntimeEvent.PACKAGE_REGISTRY_READ_FAILED.record(1);
        com.lody.virtual.helper.utils.VLog.e("PackagePersistenceLayer", "Package registry read failed; keeping registry and guest data: %s", error.getClass().getSimpleName());
    }
}
