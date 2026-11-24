package com.lody.virtual.server.pm.parser;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Parcel;
import android.os.Process;
import android.os.IInterface;

import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.proxies.pm.PackageManagerStub;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.VPackageManagerService;

import org.json.JSONObject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import mirror.android.app.ActivityThread;

/** Disposable emulator only: actual platform metadata and production virtual-package queries. */
public final class PackageMetadataProbe {
    public static JSONObject inspect(Context context) throws Exception {
        checkServer();
        boolean modern = Build.VERSION.SDK_INT >= 28;
        int all = PackageManager.GET_SIGNATURES | (modern ? PackageManager.GET_SIGNING_CERTIFICATES : 0);
        // Intentionally warm the facade first, then prove that cold reads reach the
        // injected global service. A warm-only fixture missed the startup regression.
        PackageInfo source = VirtualCore.get().getUnHookPackageManager().getPackageInfo(context.getPackageName(), all);
        Signature[] current = modern ? source.signingInfo.getApkContentsSigners() : source.signatures;
        JSONObject checks = new JSONObject();
        Field field = VPackageManagerService.class.getDeclaredField("mPackages");
        field.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String,VPackage> registry = (Map<String,VPackage>) field.get(VPackageManagerService.get());
        synchronized (registry) {
            require(!registry.containsKey(source.packageName), "fixture namespace must be unused");
            VPackage pkg = fixture(source, current);
            registry.put(pkg.packageName, pkg);
            try (FrameworkQueryTrap trap = new FrameworkQueryTrap(source.packageName)) {
                trap.requireColdFacade(checks, all);
                int before = trap.calls.get();
                int[] requested = modern ? new int[]{0, PackageManager.GET_SIGNATURES, PackageManager.GET_SIGNING_CERTIFICATES, all}
                        : new int[]{0, PackageManager.GET_SIGNATURES};
                for (int flags : requested) {
                    PackageInfo actual = VPackageManagerService.get().getPackageInfo(pkg.packageName, flags, 0);
                    if (modern) check(checks, "modern_flag_"+flags, ((flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0)
                            ? actual.signingInfo != null && Arrays.equals(current, actual.signingInfo.getApkContentsSigners())
                            : actual.signingInfo == null);
                    check(checks, "legacy_flag_"+flags, ((flags & PackageManager.GET_SIGNATURES) != 0)
                            ? Arrays.equals(source.signatures, actual.signatures) : actual.signatures == null);
                    check(checks, "virtual_identity_"+flags, actual.applicationInfo.uid == 19019
                            && !source.applicationInfo.dataDir.equals(actual.applicationInfo.dataDir));
                    PackageInfo remote = parcel(actual);
                    check(checks, "parcel_"+flags, !modern || ((flags & PackageManager.GET_SIGNING_CERTIFICATES) == 0)
                            || Arrays.equals(current, remote.signingInfo.getApkContentsSigners()));
                }
                PackageInfo actual = VPackageManagerService.get().getPackageInfo(pkg.packageName, all, 0);
                if (modern) check(checks, "history", Arrays.equals(source.signingInfo.getSigningCertificateHistory(),
                        actual.signingInfo.getSigningCertificateHistory()));
                check(checks, "ordinary_source", actual.versionCode == source.versionCode
                        && actual.applicationInfo.targetSdkVersion == source.applicationInfo.targetSdkVersion);
                ((PackageSetting)pkg.mExtras).setInstalled(0, false);
                check(checks, "uninstalled_stays_absent", VPackageManagerService.get().getPackageInfo(pkg.packageName, all, 0) == null);
                ((PackageSetting)pkg.mExtras).setInstalled(0, true);
                check(checks, "metadata_never_reenters_framework", trap.calls.get() == before);
                inspectReentry(checks, pkg, all);
                pkg.mSignatures = null;
                PackageInfo unsigned = VPackageManagerService.get().getPackageInfo(pkg.packageName, all, 0);
                check(checks, "missing_cached_signer_stays_unknown", unsigned.signatures == null
                        && (!modern || unsigned.signingInfo == null) && trap.calls.get() == before);
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
            if (mismatch.equals("version_major") && !modern) continue;
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
                    && (!modern || target.signingInfo == null) && target.splitNames == null);
        }
        return new JSONObject().put("passed", true).put("checks", checks).put("check_count", checks.length())
                .put("sdk", Build.VERSION.SDK_INT).put("ordinary_app_uid", Process.myUid()>=10000)
                .put("real_platform_signature", true).put("split_fixture", "synthetic_path_guard")
                .put("registry_restored", true);
    }

    private static void inspectReentry(JSONObject checks, VPackage pkg, int flags) throws Exception {
        MethodInvocationStub<?> stub = InvocationStubManager.getInstance().getInvocationStub(PackageManagerStub.class);
        Field field = MethodInvocationStub.class.getDeclaredField("mBaseInterface");
        field.setAccessible(true);
        Object original = field.get(stub);
        AtomicInteger calls = new AtomicInteger();
        Class<?> api = Class.forName("android.content.pm.IPackageManager");
        Object nested = Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (p,m,a) -> {
            if (m.getName().equals("getPackageInfo") && pkg.packageName.equals(a[0])) {
                require(calls.incrementAndGet() == 1, "recursive boundary must be stopped");
                return VPackageManagerService.get().getPackageInfo(pkg.packageName, flags, 0);
            }
            try { return m.invoke(original, a); }
            catch (InvocationTargetException error) { throw error.getCause(); }
        });
        try {
            field.set(stub, nested);
            PackageInfo result = VPackageManagerService.get().getPackageInfo(pkg.packageName, flags, 0);
            check(checks, "reentry_stopped", calls.get() == 1 && result != null);
            check(checks, "reentry_not_used_as_system_metadata", result.splitRevisionCodes == null
                    && (Build.VERSION.SDK_INT < 28 || result.signingInfo == null));
        } finally { field.set(stub, original); }
        PackageInfo recovered = VPackageManagerService.get().getPackageInfo(pkg.packageName, flags, 0);
        check(checks, "query_recovers_after_reentry", recovered != null
                && (Build.VERSION.SDK_INT < 28 || recovered.signingInfo != null));
    }

    /** Test-only global interception, restored before returning; never present in Release. */
    private static final class FrameworkQueryTrap implements AutoCloseable {
        final AtomicInteger calls = new AtomicInteger();
        final IInterface original = ActivityThread.sPackageManager.get();
        final PackageManager facade = VirtualCore.get().getUnHookPackageManager();
        final Field facadeField;
        final Object originalFacade;
        final String packageName;

        FrameworkQueryTrap(String packageName) throws Exception {
            this.packageName = packageName;
            facadeField = facade.getClass().getDeclaredField("mPM");
            facadeField.setAccessible(true);
            originalFacade = facadeField.get(facade);
            Class<?> api = Class.forName("android.content.pm.IPackageManager");
            IInterface trap = (IInterface) Proxy.newProxyInstance(api.getClassLoader(), new Class<?>[]{api}, (p,m,a) -> {
                if (m.getName().equals("getPackageInfo") && packageName.equals(a[0])) {
                    calls.incrementAndGet();
                    throw new IllegalStateException("fixture intercepted framework package lookup");
                }
                try { return m.invoke(original, a); }
                catch (InvocationTargetException error) { throw error.getCause(); }
            });
            facadeField.set(facade, trap);
            ActivityThread.sPackageManager.set(trap);
        }

        // Disposable debug process only: deliberately turn off the framework cache
        // to test a cache miss. Container startup has already enabled its hidden API bridge.
        @android.annotation.SuppressLint("BlockedPrivateApi")
        void requireColdFacade(JSONObject checks, int flags) throws Exception {
            try { PackageManager.class.getDeclaredMethod("disablePackageInfoCache").invoke(null); }
            catch (NoSuchMethodException olderPlatformWithoutCache) { /* mPM is trapped directly. */ }
            boolean blocked = false;
            try { facade.getPackageInfo(packageName, flags); }
            catch (IllegalStateException expected) { blocked = true; }
            check(checks, "cold_facade_reaches_injected_service", blocked && calls.get() == 1);
        }

        @Override public void close() throws Exception {
            ActivityThread.sPackageManager.set(original);
            facadeField.set(facade, originalFacade);
        }
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
        if (Build.VERSION.SDK_INT >= 28) target.signingInfo = null;
        target.signatures = null; target.splitNames = null; target.splitRevisionCodes = null;
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
