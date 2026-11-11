package com.example.limbuszhcn.container

import com.example.limbuszhcn.update.PatchManifest
import com.example.limbuszhcn.update.sha256
import java.nio.file.Files
import java.nio.file.Path

internal fun verifiedFixtureManifest(directory: Path, manifest: PatchManifest): PatchManifest =
    manifest.copy(archiveSha256 = "a".repeat(64), entries = manifest.entries.map {
        val file = directory.resolve(it.cachePath)
        it.copy(size = Files.size(file), sha256 = file.sha256())
    })
