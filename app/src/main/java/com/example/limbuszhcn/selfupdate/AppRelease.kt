package com.example.limbuszhcn.selfupdate

import org.json.JSONObject
import java.net.URI

internal object AppReleases {
    const val REPOSITORY = "gravere/LimbusZhLauncher"
    const val PAGE = "https://github.com/$REPOSITORY/releases/latest"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases"
    const val PACKAGE = "com.example.limbuszhcn"
    const val APK = "BYBUS.apk"
    const val MANIFEST = "bybus-update.json"
    const val MAX_APK_BYTES = 256L * 1024 * 1024

    /** Redirects are restricted to HTTPS GitHub release storage, without credentials. */
    fun allowed(url: String): Boolean = runCatching {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443) || uri.fragment != null) return false
        when (uri.host) {
            "api.github.com" -> uri.path == "/repos/$REPOSITORY/releases/latest" ||
                uri.path.matches(Regex("/repos/$REPOSITORY/releases/assets/[1-9][0-9]*"))
            "release-assets.githubusercontent.com", "objects.githubusercontent.com", "github-releases.githubusercontent.com" -> true
            else -> false
        }
    }.getOrDefault(false)
}

internal data class ReleaseAsset(val id: Long, val name: String, val size: Long) {
    val url get() = "${AppReleases.API}/assets/$id"
}

internal data class AppRelease(val tag: String, val versionName: String, val versionCode: Long,
    val minSdk: Int, val sha256: String, val apk: ReleaseAsset, val notes: String) {
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
                manifest.optString("notes").take(3000))
        }
    }
}
