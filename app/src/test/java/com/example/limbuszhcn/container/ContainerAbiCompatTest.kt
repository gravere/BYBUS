package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.ContainerAbiCompat
import org.junit.Assert.assertEquals
import org.junit.Test

class ContainerAbiCompatTest {
    @Test fun translatedArmUsesHostFilePathsWithoutChangingNativeArmDevices() {
        assertEquals(true, ContainerAbiCompat.requiresHostContextPaths(arrayOf("x86_64", "arm64-v8a")))
        assertEquals(true, ContainerAbiCompat.requiresHostContextPaths(arrayOf("x86", "armeabi-v7a")))
        assertEquals(false, ContainerAbiCompat.requiresHostContextPaths(arrayOf("arm64-v8a", "armeabi-v7a")))
        assertEquals(false, ContainerAbiCompat.requiresHostContextPaths(arrayOf("armeabi-v7a")))
        assertEquals(false, ContainerAbiCompat.requiresHostContextPaths(emptyArray()))
        assertEquals(false, ContainerAbiCompat.requiresHostContextPaths(null))
    }
    @Test fun installedArmEngineKeepsItsAbiOnAnX86Device() {
        assertEquals("arm64-v8a", ContainerAbiCompat.primaryAbi(
            "arm64-v8a", arrayOf("x86_64", "arm64-v8a"), "x86_64"))
        assertEquals("armeabi-v7a", ContainerAbiCompat.primaryAbi(
            "armeabi-v7a", arrayOf("x86", "armeabi-v7a"), "x86"))
    }

    @Test fun nativeArmDeviceRetainsTheInstalledSelection() {
        assertEquals("arm64-v8a", ContainerAbiCompat.primaryAbi(
            "arm64-v8a", arrayOf("arm64-v8a", "armeabi-v7a"), "arm64-v8a"))
    }

    @Test fun missingOrUnsupportedEngineDoesNotInventAnAbi() {
        assertEquals("arm64-v8a", ContainerAbiCompat.primaryAbi(null, arrayOf("arm64-v8a"), "arm64-v8a"))
        assertEquals("arm64-v8a", ContainerAbiCompat.primaryAbi("x86_64", arrayOf("arm64-v8a"), "arm64-v8a"))
    }
}
