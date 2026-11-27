package com.example.limbuszhcn.container

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.Signature
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.helper.compat.ApkSignatureCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException

@RunWith(AndroidJUnit4::class)
@Suppress("DEPRECATION")
class ApkSignatureDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun currentSignersArePreferredOverLegacyArray() {
        val info = context.packageManager.getPackageInfo(context.packageName,
            ApkSignatureCompat.queryFlags(Build.VERSION.SDK_INT))
        val expected = info.signingInfo!!.apkContentsSigners
        info.signatures = arrayOf(Signature(byteArrayOf(1, 2, 3)))
        assertArrayEquals(expected, ApkSignatureCompat.currentSigners(info))
    }

    @Test fun legacyOnlyResultIsReadableButMissingCertificatesStayMissing() {
        val info = PackageInfo()
        info.signatures = arrayOf(Signature(byteArrayOf(7, 8, 9)))
        assertArrayEquals(info.signatures, ApkSignatureCompat.currentSigners(info))
        info.signatures = emptyArray()
        assertTrue(ApkSignatureCompat.currentSigners(info).isEmpty())
        info.signatures = null
        assertTrue(ApkSignatureCompat.currentSigners(info).isEmpty())
        assertTrue(ApkSignatureCompat.currentSigners(null).isEmpty())
    }

    @Test fun realBundledComponentsStageVerifyAndRemainRecognizedForUpgrade() {
        // Isolated storage: never replace a configured component or touch account data.
        val suffix = "signature-test-${System.nanoTime()}"
        val directory = File(context.cacheDir, suffix).apply { mkdirs() }
        val isolated = object : ContextWrapper(context) {
            override fun getFilesDir(): File = directory
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
                context.getSharedPreferences("$suffix-$name", mode)
        }
        try {
            val config = MicrogContainerConfig(isolated)
            val apks = config.microgApks()
            assertEquals(2, apks.size)
            assertEquals(apks, config.microgApks())
            val bridgeType = Class.forName("com.example.limbuszhcn.container.VirtualAppBridge")
            val bridge = bridgeType.getDeclaredConstructor(Context::class.java)
                .apply { isAccessible = true }.newInstance(isolated)
            val read = bridgeType.getDeclaredMethod("readMicrogIdentity", File::class.java)
                .apply { isAccessible = true }
            for (apk in apks) {
                val identity = read.invoke(bridge, apk) as MicrogPackageIdentity
                assertEquals(setOf(MicrogUpgradePolicy.CERTIFICATE), identity.signers)
                assertTrue(MicrogUpgradePolicy.needsInstall(identity, null))
                assertFalse(MicrogUpgradePolicy.needsInstall(identity, identity))
            }
            // Calling the production verifier with the wrong pin must still reject.
            val manifest = context.assets.open("microg/manifest.properties")
                .use(::parseBundledMicrogManifest)
            val entry = manifest.entries.first()
            val verify = MicrogContainerConfig::class.java.getDeclaredMethod(
                "verifyBundledApk", File::class.java, BundledMicrogEntry::class.java)
                .apply { isAccessible = true }
            try {
                verify.invoke(config, apks.first { it.name == entry.assetName },
                    entry.copy(certificateSha256 = "00".repeat(32)))
                fail("A mismatching certificate was accepted")
            } catch (error: InvocationTargetException) {
                assertTrue(error.cause is IllegalArgumentException)
                assertTrue(error.cause!!.message!!.contains("certificate mismatch"))
            }
        } finally {
            directory.deleteRecursively()
            context.deleteSharedPreferences("$suffix-${MicrogContainerConfig.PREFERENCES_NAME}")
        }
    }
}
