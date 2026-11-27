package com.example.limbuszhcn.container

import android.content.pm.PackageManager
import com.lody.virtual.helper.compat.ApkSignatureCompat
import org.junit.Assert.*
import org.junit.Test

@Suppress("DEPRECATION")
class ApkSignatureCompatTest {
    @Test fun legacyArchiveCertificateCollectionRemainsEnabledFromAndroid8To17() {
        for (sdk in 26..37) {
            val flags = ApkSignatureCompat.queryFlags(sdk)
            // AOSP Android 9/10 only calls collectCertificates under this condition.
            assertNotEquals("SDK $sdk must collect archive certificates", 0,
                flags and PackageManager.GET_SIGNATURES)
            assertEquals(sdk >= 28, flags and PackageManager.GET_SIGNING_CERTIFICATES != 0)
        }
    }

    @Test fun reproducesOldQuerySkippingCertificateCollectionOnAndroid10() {
        val previousFlags = PackageManager.GET_SIGNING_CERTIFICATES
        assertEquals(0, previousFlags and PackageManager.GET_SIGNATURES)
        assertNotEquals(0, ApkSignatureCompat.queryFlags(29) and PackageManager.GET_SIGNATURES)
    }
}
