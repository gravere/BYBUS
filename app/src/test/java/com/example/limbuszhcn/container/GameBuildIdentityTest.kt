package com.example.limbuszhcn.container

import org.junit.Assert.assertThrows
import org.junit.Test

class GameBuildIdentityTest {
    private val baseline = GameBuildIdentity(475, 10,
        listOf(GameApkIdentity("/data/app/game/base.apk", 100, 10), GameApkIdentity("/data/app/game/arm64.apk", 200, 10)))

    @Test fun unchangedGameCanLaunchAfterPreparation() {
        requireSameGameBuild(baseline, baseline.copy())
    }

    @Test fun importMustUseTheCurrentBaseAndAllItsSplits() {
        val data = GameApkIdentity("/data/app/game/data.apk", 300, 10)
        val installed = baseline.copy(apks = baseline.apks + data)
        val (base, arm, assets) = installed.apks.map { it.path }
        val selected = GameImportRequest(listOf(base, assets, arm), installed.version, "installed")
        installed.requireImportSource(selected)
        for (stale in listOf(selected.copy(versionCode = 474),
            selected.copy(splitApkPaths = listOf(arm, base, assets)),
            selected.copy(splitApkPaths = listOf(base, arm)),
            selected.copy(splitApkPaths = listOf(base, arm, arm)),
            selected.copy(splitApkPaths = listOf(base, arm, "/data/app/old/data.apk")))) {
            assertThrows(IllegalArgumentException::class.java) { installed.requireImportSource(stale) }
        }
    }

    @Test fun updateOrSplitReplacementDuringPreparationCannotLaunchMixedGenerations() {
        for (changed in listOf(baseline.copy(version = 476), baseline.copy(updated = 11),
            baseline.copy(apks = baseline.apks.dropLast(1)),
            baseline.copy(apks = baseline.apks.map { it.copy(path = it.path.replace("game", "new-game")) }),
            baseline.copy(apks = baseline.apks.map { it.copy(size = it.size + 1) }),
            baseline.copy(apks = baseline.apks.map { it.copy(modified = 11) }))) {
            assertThrows(IllegalArgumentException::class.java) { requireSameGameBuild(baseline, changed) }
        }
    }
}
