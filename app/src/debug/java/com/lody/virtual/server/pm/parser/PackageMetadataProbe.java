package com.lody.virtual.server.pm.parser;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Process;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.VPackageManagerService;

import org.json.JSONObject;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;

/** Disposable emulator only: actual platform metadata and production virtual-package queries. */
public final class PackageMetadataProbe {
    public static JSONObject inspect(Context context) throws Exception {
        if (Build.VERSION.SDK_INT < 28) throw new AssertionError("modern signing API required");
        checkServer();
        int all = PackageManager.GET_SIGNATURES | PackageManager.GET_SIGNING_CERTIFICATES;
        PackageInfo source = VirtualCore.get().getUnHookPackageManager().getPackageInfo(context.getPackageName(), all);
        Signature[] current = source.signingInfo.getApkContentsSigners();
        JSONObject checks = new JSONObject();
        Field field = VPackageManagerService.class.getDeclaredField("mPackages");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,VPackage> registry = (Map<String,VPackage>) field.get(VPackageManagerService.get());
        synchronized (registry) {
            require(!registry.containsKey(source.packageName), "fixture namespace must be unused");
            VPackage pkg = fixture(source, current);
            registry.put(pkg.packageName, pkg);
            try {
                for (int flags : new int[]{0, PackageManager.GET_SIGNATURES, PackageManager.GET_SIGNING_CERTIFICATES, all}) {
                    PackageInfo actual = VPackageManagerService.get().getPackageInfo(pkg.packageName, flags, 0);
                    check(checks, "modern_flag_"+flags, ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0)
                            ? actual.signingInfo != null && Arrays.equals(current, actual.signingInfo.getApkContentsSigners())
                            : actual.signingInfo == null);
                    check(checks, "legacy_flag_"+flags, ((flags & PackageManager.GET_SIGNATURES) != 0)
                            ? Arrays.equals(source.signatures, actual.signatures) : actual.signatures == null);
                    check(checks, "virtual_identity_"+flags, actual.applicationInfo.uid == 19019
                            && !source.applicationInfo.dataDir.equals(actual.applicationInfo.dataDir));
                    PackageInfo remote = parcel(actual);
                    check(checks, "parcel_"+flags, ((flags & PackageManager.GET_SIGNING_CERTIFICATES) == 0)
                            || Arrays.equals(current, remote.signingInfo.getApkContentsSigners()));
                }
                PackageInfo actual = VPackageManagerService.get().getPackageInfo(pkg.packageName, all, 0);
                check(checks, "history", Arrays.equals(source.signingInfo.getSigningCertificateHistory(),
                        actual.signingInfo.getSigningCertificateHistory()));
                check(checks, "ordinary_source", actual.versionCode == source.versionCode
                        && actual.applicationInfo.targetSdkVersion == source.applicationInfo.targetSdkVersion);
                ((PackageSetting)pkg.mExtras).setInstalled(0, false);
                check(checks, "uninstalled_stays_absent", VPackageManagerService.get().getPackageInfo(pkg.packageName, all, 0) == null);
                ((PackageSetting)pkg.mExtras).setInstalled(0, true);
            } finally { registry.remove(pkg.packageName); }
        }
        PackageInfo splitSource = parcel(source);
        splitSource.splitNames = new String[]{"feature", "config.arm64_v8a"};
        splitSource.splitRevisionCodes = new int[]{2, 4};
        splitSource.applicationInfo.splitSourceDirs = new String[]{"/fixture/feature.apk", "/fixture/arm64.apk"};
        PackageInfo target = bare(splitSource);
        check(checks, "split_copy", InstalledPackageMetadata.copyVerified(target, splitSource, current, all)
                && Arrays.equals(target.splitNames, splitSource.splitNames)
                && Arrays.equals(target.splitRevisionCodes, splitSource.splitRevisionCodes));
        target.splitNames[0] = "changed";
        target.splitRevisionCodes[0] = 99;
        check(checks, "no_shared_arrays", splitSource.splitNames[0].equals("feature") && splitSource.splitRevisionCodes[0] == 2);
        for (String mismatch : new String[]{"package", "version", "version_major", "base", "splits", "signer", "missing_signer", "split_count"}) {
            target = bare(splitSource);
            PackageInfo altered = parcel(splitSource);
            Signature[] expected = current;
            if (mismatch.equals("package")) altered.packageName += ".different";
            if (mismatch.equals("version")) altered.versionCode++;
            if (mismatch.equals("version_major")) altered.setLongVersionCode(altered.getLongVersionCode() + (1L << 32));
            if (mismatch.equals("base")) altered.applicationInfo.sourceDir += ".different";
            if (mismatch.equals("splits")) altered.applicationInfo.splitSourceDirs[0] += ".different";
            if (mismatch.equals("signer")) expected = new Signature[]{new Signature(new byte[]{1,2})};
            if (mismatch.equals("missing_signer")) expected = null;
            if (mismatch.equals("split_count")) altered.splitRevisionCodes = new int[]{1};
            check(checks, "reject_"+mismatch, !InstalledPackageMetadata.copyVerified(target, altered, expected, all)
                    && target.signingInfo == null && target.splitNames == null);
        }
        return new JSONObject().put("passed", true).put("checks", checks).put("check_count", checks.length())
                .put("sdk", Build.VERSION.SDK_INT).put("ordinary_app_uid", Process.myUid()>=10000)
                .put("real_platform_signature", true).put("split_fixture", "synthetic_path_guard")
                .put("registry_restored", true);
    }

    private static VPackage fixture(PackageInfo source, Signature[] current) {
        VPackage pkg = new VPackage(); pkg.packageName = source.packageName; pkg.mVersionCode = source.versionCode;
        pkg.mVersionName = source.versionName; pkg.applicationInfo = new ApplicationInfo(source.applicationInfo);
        pkg.applicationInfo.uid = 19019; pkg.mSignatures = current.clone();
        pkg.activities = new ArrayList<>(); pkg.receivers = new ArrayList<>(); pkg.providers = new ArrayList<>();
        pkg.services = new ArrayList<>(); pkg.instrumentation = new ArrayList<>(); pkg.permissions = new ArrayList<>();
        pkg.permissionGroups = new ArrayList<>(); pkg.requestedPermissions = new ArrayList<>();
        PackageSetting settings = new PackageSetting(); settings.packageName = pkg.packageName; settings.appId = 19019;
        settings.appMode = InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK; settings.setInstalled(0, true); pkg.mExtras = settings;
        return pkg;
    }

    private static PackageInfo bare(PackageInfo source) {
        PackageInfo target = parcel(source);
        target.signingInfo = null; target.signatures = null; target.splitNames = null; target.splitRevisionCodes = null;
        return target;
    }
    private static PackageInfo parcel(PackageInfo source) {
        Parcel p=Parcel.obtain();
        try { source.writeToParcel(p,0); p.setDataPosition(0); return PackageInfo.CREATOR.createFromParcel(p); }
        finally { p.recycle(); }
    }
    private static void checkServer() { require(VActivityManager.get().getSystemPid() == Process.myPid(), "server process"); }
    private static void require(boolean value,String name) { if(!value) throw new AssertionError(name); }
    private static void check(JSONObject checks,String name,boolean value) throws Exception { require(value,name); checks.put(name,true); }
}
