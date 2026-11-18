package com.lody.virtual.helper.compat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssetPackNamesTest {
    @Test fun systemNamesWorkWithAnyInstallerFilename() {
        val paths = arrayOf("/pkg/1.apk", "/pkg/2.apk")
        val names = arrayOf("config.arm64_v8a", "UnityDataAssetPack")
        assertEquals("UnityDataAssetPack", AssetPackNames.nameAt(names, paths, 1))
        assertNull(AssetPackNames.nameAt(names, paths, 0))
        assertNull(AssetPackNames.nameAt(arrayOf("other"), paths, 1))
        assertNull(AssetPackNames.nameAt(arrayOf("../bad", "other"), paths, 0))
        assertEquals("UnityDataAssetPack", AssetPackNames.nameAt(null, arrayOf("/pkg/split_UnityDataAssetPack.apk"), 0))
    }
}
