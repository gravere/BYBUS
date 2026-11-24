package com.lody.virtual.server.pm.parser;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.proxies.pm.PackageManagerStub;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.os.VUserHandle;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.pm.PackageSetting;

import java.util.Arrays;
import java.util.HashSet;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

/** Metadata from the exact installed APK referenced by a virtual installation. */
final class InstalledPackageMetadata {
    private static final ThreadLocal<ReadGuard> READING = new ThreadLocal<>();
    private static final AtomicInteger FAILURE_LOGS = new AtomicInteger();
    private static final class ReadGuard { boolean reentered; }
    private InstalledPackageMetadata() { }

    static void apply(VPackage pkg, PackageInfo result, int flags) {
        if (!(pkg.mExtras instanceof PackageSetting)
                || ((PackageSetting) pkg.mExtras).appMode != InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK) {
            return;
        }
        int sourceFlags = PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) sourceFlags |= PackageManager.GET_SIGNING_CERTIFICATES;
        ReadGuard previous = READING.get();
        if (previous != null) {
            previous.reentered = true;
            return;
        }
        ReadGuard guard = new ReadGuard();
        READING.set(guard);
        try {
            PackageInfo installed = readInstalled(pkg.packageName, sourceFlags);
            if (!guard.reentered) copyVerified(result, installed, pkg.mSignatures, flags);
        } catch (ReflectiveOperationException | RuntimeException error) {
            // A concurrent uninstall/update must not invent metadata from another installation.
            if (FAILURE_LOGS.getAndIncrement() < 4) {
                VLog.w("PackageMetadata", "Installed metadata unavailable: %s", error.getClass().getSimpleName());
            }
        } finally {
            READING.remove();
        }
    }

    static PackageInfo readInstalled(String packageName, int flags) throws ReflectiveOperationException {
        MethodInvocationStub<?> stub = InvocationStubManager.getInstance().getInvocationStub(PackageManagerStub.class);
        if (stub == null || stub.getBaseInterface() == null) return null;
        // ApplicationPackageManager uses a global cache backed by the injected service.
        // Invoke the captured system boundary directly, never that cached facade.
        Class<?> service = Class.forName("android.content.pm.IPackageManager");
        if (Build.VERSION.SDK_INT >= 33) {
            Method method = service.getMethod("getPackageInfo", String.class, long.class, int.class);
            return (PackageInfo) method.invoke(stub.getBaseInterface(), packageName, (long) flags, VUserHandle.realUserId());
        }
        Method method = service.getMethod("getPackageInfo", String.class, int.class, int.class);
        return (PackageInfo) method.invoke(stub.getBaseInterface(), packageName, flags, VUserHandle.realUserId());
    }

    static boolean copyVerified(PackageInfo target, PackageInfo installed,
                                Signature[] cachedSigners, int flags) {
        if (target == null || installed == null || target.packageName == null
                || !target.packageName.equals(installed.packageName)
                || (Build.VERSION.SDK_INT >= 28
                    ? target.getLongVersionCode() != installed.getLongVersionCode()
                    : target.versionCode != installed.versionCode)) return false;
        ApplicationInfo targetApp = target.applicationInfo, sourceApp = installed.applicationInfo;
        if (targetApp == null || sourceApp == null || targetApp.sourceDir == null
                || !targetApp.sourceDir.equals(sourceApp.sourceDir)
                || !Arrays.equals(targetApp.splitSourceDirs, sourceApp.splitSourceDirs)) return false;
        Signature[] current = Build.VERSION.SDK_INT >= 28 && installed.signingInfo != null
                ? installed.signingInfo.getApkContentsSigners() : installed.signatures;
        if (!sameSigners(cachedSigners, current)) return false;
        int splitCount = sourceApp.splitSourceDirs == null ? 0 : sourceApp.splitSourceDirs.length;
        if ((installed.splitNames == null ? 0 : installed.splitNames.length) != splitCount
                || (installed.splitRevisionCodes == null ? 0 : installed.splitRevisionCodes.length) != splitCount) {
            return false;
        }
        target.splitNames = installed.splitNames == null ? null : installed.splitNames.clone();
        target.splitRevisionCodes = installed.splitRevisionCodes == null ? null : installed.splitRevisionCodes.clone();
        target.baseRevisionCode = installed.baseRevisionCode;
        if ((flags & PackageManager.GET_SIGNATURES) != 0) {
            // Android returns the oldest proven certificate here after key rotation.
            target.signatures = installed.signatures == null ? null : installed.signatures.clone();
        }
        if (Build.VERSION.SDK_INT >= 28 && (flags & PackageManager.GET_SIGNING_CERTIFICATES) != 0
                && installed.signingInfo != null) {
            target.signingInfo = new SigningInfo(installed.signingInfo);
        }
        return true;
    }

    private static boolean sameSigners(Signature[] expected, Signature[] actual) {
        return expected != null && actual != null && expected.length > 0
                && expected.length == actual.length
                && new HashSet<>(Arrays.asList(expected)).equals(new HashSet<>(Arrays.asList(actual)));
    }
}
