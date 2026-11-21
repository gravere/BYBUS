package com.example.limbuszhcn.tracking

import org.json.JSONObject
import java.time.Instant

/** The public feed contains release facts only; package bytes still come from the upstream. */
internal object ZeroReleaseFeed {
    const val MAX_AGE = 6 * 60 * 60_000L
    fun release(json: String, now: Long): String {
        require(json.length <= 32 * 1024)
        val root = JSONObject(json)
        require(root.getInt("schema") == 1 && root.getString("repository") ==
            "LocalizeLimbusCompany/LocalizeLimbusCompany")
        val synced = Instant.parse(root.getString("syncedAt")).toEpochMilli()
        if (now - synced !in -300_000L..MAX_AGE) throw ProjectFetchException(ProjectIssue.Unavailable)
        val release = root.getJSONObject("release")
        val snapshot = GitHubSnapshotCodec.parseResponse(release.toString(), now)
        val assets = release.getJSONArray("assets")
        require(assets.length() == 1)
        val asset = assets.getJSONObject(0)
        val name = "LimbusLocalize_${snapshot.tag}.7z"
        require(asset.getString("name") == name && asset.getString("browser_download_url") ==
            "${ZeroGitHub.RELEASES}/download/${snapshot.tag}/$name")
        require(asset.getString("digest").matches(Regex("sha256:[a-fA-F0-9]{64}")))
        require(asset.getLong("size") in 1..1024L * 1024 * 1024)
        return release.toString()
    }
}
