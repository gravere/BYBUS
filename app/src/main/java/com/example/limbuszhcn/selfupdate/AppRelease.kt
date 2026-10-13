package com.example.limbuszhcn.selfupdate

import org.json.JSONObject
import java.net.URI

internal object AppReleases {
    const val REPOSITORY = "gravere/BYBUS"
    const val REPOSITORY_ID = "1415176635"
    const val PAGE = "https://github.com/$REPOSITORY/releases/latest"
    private const val API_PATH = "/repositories/$REPOSITORY_ID/releases"
    const val API = "https://api.github.com$API_PATH"
    const val PACKAGE = "com.example.limbuszhcn"
    const val APK = "BYBUS.apk"
    const val MANIFEST = "bybus-update.json"
    const val MAX_APK_BYTES = 256L * 1024 * 1024
    const val DELTA_FORMAT = "bybus-copy-v1"
    const val FIRST_DELTA_BUILD = 48L
    const val MAX_DELTAS = 3

    /** Redirects are restricted to HTTPS GitHub release storage, without credentials. */
    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) || uri.fragment != null) return false
        when (uri.host) {
            "api.github.com" -> uri.path == "$API_PATH/latest" ||
                uri.path.matches(Regex("$API_PATH/assets/[1-9][0-9]*"))
            "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com" -> true
            else -> false
        }
    }.getOrDefault(false)
}

internal data class ReleaseAsset(val id: Long, val name: String, val size: Long) {
    val url get() = "${AppReleases.API}/assets/$id"
}

internal data class AppDelta(val fromVersionCode: Long, val fromSize: Long, val fromSha256: String,
    val sha256: String, val asset: ReleaseAsset)

internal data class AppRelease(val tag: String, val versionName: String, val versionCode: Long,
    val minSdk: Int, val sha256: String, val apk: ReleaseAsset, val notes: String,
    val deltas: List<AppDelta> = emptyList()) {
    fun newerThan(installed: Long) = versionCode > installed

    companion object {
        fun asset(release: JSONObject, name: String): ReleaseAsset {
            val assets = release.getJSONArray("assets")
            require(assets.length() <= 30) { "发行文件数量异常" }
            val matches = (0 until assets.length()).map(assets::getJSONObject).filter { it.getString("name") == name }
            val raw = requireNotNull(matches.singleOrNull()) { "发行文件不完整，请稍后重试" }
            require(raw.getString("state") == "uploaded") { "发行文件尚未上传完成" }
            return ReleaseAsset(raw.getLong("id"), name, raw.getLong("size")).also {
                require(it.id > 0 && it.size > 0) { "发行文件信息异常" }
            }
        }

        fun parse(release: JSONObject, manifest: JSONObject): AppRelease {
            require(!release.getBoolean("draft") && !release.getBoolean("prerelease")) { "此发行版尚未正式发布" }
            require(manifest.getInt("schemaVersion") == 1 && manifest.getString("packageName") == AppReleases.PACKAGE) {
                "更新描述不属于 BYBUS"
            }
            val code = manifest.getLong("versionCode")
            val version = manifest.getString("versionName")
            val tag = release.getString("tag_name")
            require(code > 0 && version.matches(Regex("v[0-9]+(?:\\.[0-9]+){1,2}")) && tag == "$version-build.$code") {
                "更新版本信息不一致"
            }
            require(manifest.getString("apkName") == AppReleases.APK) { "更新安装包名称异常" }
            val apk = asset(release, AppReleases.APK)
            require(apk.size in 1..AppReleases.MAX_APK_BYTES && apk.size == manifest.getLong("size")) { "安装包大小不一致" }
            val sha = manifest.getString("sha256").lowercase(java.util.Locale.ROOT)
            require(sha.matches(Regex("[a-f0-9]{64}"))) { "缺少有效的安装包校验值" }
            val minSdk = manifest.getInt("minSdk")
            require(minSdk in 26..100) { "系统版本要求异常" }
            return AppRelease(tag, version, code, minSdk, sha, apk,
                manifest.optString("notes").take(3000), parseDeltas(release, manifest, code, apk.size))
        }

        /** Optional or future patch metadata must never prevent a full APK update. */
        private fun parseDeltas(release: JSONObject, manifest: JSONObject, code: Long, apkSize: Long): List<AppDelta> {
            val list = manifest.optJSONArray("deltas") ?: return emptyList()
            if (list.length() > AppReleases.MAX_DELTAS) return emptyList()
            val parsed = (0 until list.length()).mapNotNull { index ->
                try {
                    val raw = list.getJSONObject(index)
                    if (raw.getString("format") != AppReleases.DELTA_FORMAT) return@mapNotNull null
                    val from = raw.getLong("fromVersionCode")
                    val fromSize = raw.getLong("fromSize")
                    require(from >= AppReleases.FIRST_DELTA_BUILD && from < code)
                    require(fromSize in 1..AppReleases.MAX_APK_BYTES)
                    val name = "BYBUS-update-$from-to-$code.delta"
                    require(raw.getString("name") == name)
                    val patch = asset(release, name)
                    require(patch.size == raw.getLong("size") && patch.size < apkSize * 4 / 5)
                    fun sha(key: String) = raw.getString(key).lowercase(java.util.Locale.ROOT).also {
                        require(it.matches(Regex("[a-f0-9]{64}")))
                    }
                    AppDelta(from, fromSize, sha("fromSha256"), sha("sha256"), patch)
                } catch (_: org.json.JSONException) { null }
                  catch (_: IllegalArgumentException) { null }
            }
            return if (parsed.map { it.fromVersionCode }.distinct().size == parsed.size) parsed else emptyList()
        }
    }
}
