package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.LimbusNativeProfile
import org.junit.Assert.*
import org.junit.Test
import java.util.Properties

class NativeProfileTest {
    private val digest = "a".repeat(64)
    private fun rules() = Properties().apply {
        setProperty("profiles.count", "1")
        setProperty("profile.0.id", "fixture-only")
        setProperty("profile.0.versionCode", "450")
        setProperty("profile.0.minSdk", "31")
        setProperty("profile.0.maxSdk", "31")
        setProperty("profile.0.abi", "arm64-v8a")
        setProperty("profile.0.pageSize", "4096")
        setProperty("profile.0.libcovault.sha256", digest)
        setProperty("profile.0.libil2cpp.sha256", digest)
    }
    @Test fun emptyCatalogCannotApproveTheCurrentBinary() {
        var calls = 0
        assertEquals("", LimbusNativeProfile.selectVerifiedProfile(Properties(), 450, 31, "arm64-v8a", 4096) { calls++; digest })
        assertEquals(0, calls)
    }
    @Test fun bothHashesVersionSdkAndAbiMustMatch() {
        val rules = rules()
        assertEquals("fixture-only", LimbusNativeProfile.selectVerifiedProfile(rules, 450, 31, "arm64-v8a", 4096) { digest })
        assertEquals("", LimbusNativeProfile.selectVerifiedProfile(rules, 451, 31, "arm64-v8a", 4096) { digest })
        assertEquals("", LimbusNativeProfile.selectVerifiedProfile(rules, 450, 36, "arm64-v8a", 4096) { digest })
        assertEquals("", LimbusNativeProfile.selectVerifiedProfile(rules, 450, 31, "x86_64", 4096) { digest })
        assertEquals("", LimbusNativeProfile.selectVerifiedProfile(rules, 450, 31, "arm64-v8a", 16384) { digest })
        assertEquals("", LimbusNativeProfile.selectVerifiedProfile(rules, 450, 31, "arm64-v8a", 4096) { name ->
            if (name == "libil2cpp.so") "b".repeat(64) else digest
        })
    }
}
