package com.example.limbuszhcn.tracking

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ZeroReleaseFeedTest {
    private val now = Instant.parse("2026-11-21T00:00:00Z").toEpochMilli()
    private fun feed() = JSONObject().put("schema", 1).put("repository", "LocalizeLimbusCompany/LocalizeLimbusCompany")
        .put("syncedAt", "2026-11-21T00:00:00Z").put("release", JSONObject().put("tag_name", "2026111901")
            .put("published_at", "2026-11-19T09:43:59Z").put("draft", false).put("prerelease", false)
            .put("html_url", "${ZeroGitHub.RELEASES}/tag/2026111901").put("assets", JSONArray().put(JSONObject()
                .put("name", "LimbusLocalize_2026111901.7z").put("size", 1000)
                .put("browser_download_url", "${ZeroGitHub.RELEASES}/download/2026111901/LimbusLocalize_2026111901.7z")
                .put("digest", "sha256:" + "a".repeat(64)))))

    @Test fun feedRetainsTheUpstreamVersionAndDigest() {
        val json = ZeroReleaseFeed.release(feed().toString(), now)
        val release = com.example.limbuszhcn.update.GitHubTranslationReleaseClient(ZeroGitHub.API).parseRelease(json)
        assertEquals(2026111901L, release.version)
        assertEquals("a".repeat(64), release.sha256)
    }
    @Test fun expiredFutureOrWrongRepositoryMetadataCannotClaimToBeCurrent() {
        for (value in listOf(feed().put("syncedAt", "2026-11-20T00:00:00Z"),
            feed().put("syncedAt", "2026-11-21T00:10:00Z"), feed().put("repository", "other/repo")))
            assertThrows(Exception::class.java) { ZeroReleaseFeed.release(value.toString(), now) }
    }
    @Test fun aFeedCannotSwitchAssetRepositoriesOrSilentlyDropItsChecksum() {
        for ((key, value) in listOf("digest" to "", "browser_download_url" to "https://github.com/other/repo/file")) {
            val root = feed()
            root.getJSONObject("release").getJSONArray("assets").getJSONObject(0).put(key, value)
            assertThrows(Exception::class.java) { ZeroReleaseFeed.release(root.toString(), now) }
        }
    }
}
