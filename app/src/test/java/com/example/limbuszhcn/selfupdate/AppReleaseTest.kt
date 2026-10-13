package com.example.limbuszhcn.selfupdate

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.file.Files

class AppReleaseTest {
    private fun release() = JSONObject().put("tag_name", "v1.0-build.41").put("draft", false).put("prerelease", false)
        .put("assets", JSONArray().put(JSONObject().put("id", 123L).put("name", "BYBUS.apk").put("state", "uploaded").put("size", 1000L)))
    private fun manifest() = JSONObject().put("schemaVersion", 1).put("packageName", AppReleases.PACKAGE)
        .put("versionName", "v1.0").put("versionCode", 41).put("minSdk", 26).put("apkName", "BYBUS.apk")
        .put("sha256", "a".repeat(64)).put("size", 1000L).put("notes", "保留数据升级")
    private fun rejects(action: () -> Unit) { assertThrows(Exception::class.java, action) }

    @Test fun usesMonotonicBuildCodeEvenWhenDisplayVersionIsUnchanged() {
        val parsed = AppRelease.parse(release(), manifest())
        assertTrue(parsed.newerThan(40)); assertFalse(parsed.newerThan(41)); assertFalse(parsed.newerThan(42))
    }
    @Test fun rejectsDraftAndPrerelease() {
        rejects { AppRelease.parse(release().put("draft", true), manifest()) }
        rejects { AppRelease.parse(release().put("prerelease", true), manifest()) }
    }
    @Test fun rejectsWrongAppAndMismatchedReleaseCode() {
        rejects { AppRelease.parse(release(), manifest().put("packageName", "com.ProjectMoon.LimbusCompany")) }
        rejects { AppRelease.parse(release(), manifest().put("versionCode", 42)) }
        rejects { AppRelease.parse(release(), manifest().put("schemaVersion", 2)) }
    }
    @Test fun rejectsAmbiguousIncompleteOrOversizedAssets() {
        val duplicate = release().apply { getJSONArray("assets").put(getJSONArray("assets").getJSONObject(0)) }
        rejects { AppRelease.parse(duplicate, manifest()) }
        rejects { AppRelease.parse(release().apply { getJSONArray("assets").getJSONObject(0).put("state", "new") }, manifest()) }
        rejects { AppRelease.parse(release().apply { getJSONArray("assets").getJSONObject(0).put("size", AppReleases.MAX_APK_BYTES + 1) }, manifest()) }
        rejects { AppRelease.parse(release(), manifest().put("size", 999)) }
        rejects { AppRelease.parse(release(), manifest().put("sha256", "bad")) }
    }
    @Test fun onlyTrustsExactGitHubHostsAndRepositoryEndpoints() {
        assertTrue(AppReleases.allowed("${AppReleases.API}/latest"))
        assertTrue(AppReleases.allowed("${AppReleases.API}/assets/123"))
        assertTrue(AppReleases.allowed("https://release-assets.githubusercontent.com/github-production-release-asset/a?token=signed"))
        listOf(AppReleases.API.replace("https:", "http:") + "/latest",
            AppReleases.API.replace("api.github.com", "api.github.com.evil.test") + "/latest",
            "https://api.github.com/repositories/1415176636/releases/latest",
            "https://api.github.com/repositories/${AppReleases.REPOSITORY_ID}/issues",
            "https://api.github.com/repositories/${AppReleases.REPOSITORY_ID}/releases/assets/0",
            "https://api.github.com/repos/${AppReleases.REPOSITORY}/releases/latest",
            "https://api.github.com/repos/other/app/releases/latest", "https://api.github.com/user",
            "https://release-assets.githubusercontent.com@evil.test/a", "https://name:secret@objects.githubusercontent.com/a",
            "https://objects.githubusercontent.com:4433/a", "file:///data/local/tmp/app.apk").forEach { assertFalse(it, AppReleases.allowed(it)) }
        assertEquals("https://api.github.com/repositories/${AppReleases.REPOSITORY_ID}/releases/assets/123",
            ReleaseAsset(123, "BYBUS.apk", 1000).url)
    }
    @Test fun boundedCopyRejectsOversizeAndCancellation() {
        rejects { AppUpdateRepository.copy(ByteArrayInputStream(ByteArray(5)), 4) { _, _ -> } }
        try {
            Thread.currentThread().interrupt()
            assertThrows(java.util.concurrent.CancellationException::class.java) {
                AppUpdateRepository.copy(ByteArrayInputStream(ByteArray(1)), 1) { _, _ -> }
            }
        } finally { Thread.interrupted() }
    }
    @Test fun hashChangesWhenCachedFileChanges() {
        val file = Files.createTempFile("bybus-update", ".apk").toFile()
        try {
            file.writeText("original"); val original = AppUpdateRepository.hash(file)
            file.writeText("modified"); assertNotEquals(original, AppUpdateRepository.hash(file))
        } finally { file.delete() }
    }

    @Test fun optionalDeltaMetadataKeepsOldFullApkProtocolUsable() {
        val remote = release().put("tag_name", "v1.0.6-build.49")
        val data = manifest().put("versionName", "v1.0.6").put("versionCode", 49)
        val patch = JSONObject().put("format", AppReleases.DELTA_FORMAT).put("fromVersionCode", 48)
            .put("fromSize", 1000L).put("fromSha256", "b".repeat(64)).put("sha256", "c".repeat(64))
            .put("name", "BYBUS-update-48-to-49.delta").put("size", 100L)
        remote.getJSONArray("assets").put(JSONObject().put("id", 124L).put("name", patch.getString("name"))
            .put("state", "uploaded").put("size", 100L))
        assertTrue(AppRelease.parse(remote, data).deltas.isEmpty())
        data.put("deltas", JSONArray().put(patch))
        assertEquals(48L, AppRelease.parse(remote, data).deltas.single().fromVersionCode)
        listOf("format" to "future-format", "fromSha256" to "invalid", "fromVersionCode" to 47,
            "name" to "other.delta", "size" to 99L).forEach { (key, value) ->
            val invalid = JSONObject(patch.toString()).put(key, value)
            val parsed = AppRelease.parse(remote, JSONObject(data.toString()).put("deltas", JSONArray().put(invalid)))
            assertTrue(parsed.deltas.isEmpty()); assertEquals(1000L, parsed.apk.size)
        }
        assertTrue(AppRelease.parse(remote, data.put("deltas", JSONArray().put(patch).put(patch))).deltas.isEmpty())
    }
}
