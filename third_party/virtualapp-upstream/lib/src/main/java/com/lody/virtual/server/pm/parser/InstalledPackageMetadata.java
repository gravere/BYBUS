package com.lody.virtual.server.pm.parser;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;
import com.lody.virtual.remote.InstalledAppInfo;
import com.lody.virtual.server.pm.PackageSetting;

import java.util.Arrays;
import java.util.HashSet;

/** Metadata from the exact installed APK referenced by a virtual installation. */
final class InstalledPackageMetadata {
    private InstalledPackageMetadata() { }

    static void apply(VPackage pkg, PackageInfo result, int flags) {
        if (!(pkg.mExtras instanceof PackageSetting)
                || ((PackageSetting) pkg.mExtras).appMode != InstalledAppInfo.MODE_APP_USE_OUTSIDE_APK) {
            return;
        }
        int sourceFlags = PackageManager.GET_SIGNATURES;
        if (Build.VERSION.SDK_INT >= 28) sourceFlags |= PackageManager.GET_SIGNING_CERTIFICATES;
        try {
            PackageInfo installed = VirtualCore.get().getUnHookPackageManager()
                    .getPackageInfo(pkg.packageName, sourceFlags);
            copyVerified(result, installed, pkg.mSignatures, flags);
        } catch (PackageManager.NameNotFoundException | RuntimeException error) {
            // A concurrent uninstall/update must not invent metadata from another installation.
            VLog.w("PackageMetadata", "Installed metadata unavailable: %s", error.getClass().getSimpleName());
        }
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
