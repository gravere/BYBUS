package com.lody.virtual.helper.compat;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

/** Read current APK signers on both legacy archive parsers and modern package managers. */
@SuppressWarnings("deprecation")
public final class ApkSignatureCompat {
    private ApkSignatureCompat() {}

    public static int queryFlags(int sdk) {
        // Android 9/10 PackageManager.getPackageArchiveInfo only collects certificates
        // when GET_SIGNATURES is present, even though it exposes GET_SIGNING_CERTIFICATES.
        return PackageManager.GET_SIGNATURES
                | (sdk >= 28 ? PackageManager.GET_SIGNING_CERTIFICATES : 0);
    }

    public static Signature[] currentSigners(PackageInfo info) {
        if (info == null) return new Signature[0];
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            Signature[] current = info.signingInfo.getApkContentsSigners();
            if (current != null && current.length > 0) return current;
        }
        // Some legacy/OEM archive parsers only populate this field. Never use signing
        // history or substitute a known certificate when neither field is available.
        return info.signatures != null ? info.signatures : new Signature[0];
    }
}
