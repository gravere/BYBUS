package com.example.limbuszhcn.translation

import org.json.JSONObject
import java.net.URI

internal data class SourceUpdateLink(val url: String, val automatic: Boolean = true,
    val etag: String? = null, val modified: String? = null, val validatorTarget: String? = null,
    val validatorRevision: String? = null, val checkedAt: Long = 0) {
    fun json() = JSONObject().put("url", url).put("automatic", automatic).put("etag", etag)
        .put("modified", modified).put("validatorTarget", validatorTarget)
        .put("validatorRevision", validatorRevision).put("checkedAt", checkedAt)
    fun due(now: Long) = automatic && (checkedAt <= 0 || now < checkedAt || now - checkedAt >= 60 * 60 * 1000L)
    companion object {
        fun read(j: JSONObject) = SourceUpdateLink(downloadUrl(j.getString("url")), j.optBoolean("automatic", true),
            header(j.optString("etag")), header(j.optString("modified")), hash(j.optString("validatorTarget")),
            hash(j.optString("validatorRevision")), j.optLong("checkedAt").coerceAtLeast(0))
        fun header(value: String?): String? = value?.takeIf { it.length in 1..2048 && it.all { c -> c.code in 32..126 } }
        private fun hash(value: String) = value.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
        fun downloadUrl(value: String): String {
            require(value.trim().length in 1..4096) { "请填写 HTTPS 汉化包下载链接" }
            val uri = runCatching { URI(value.trim()) }.getOrNull()
            require(uri != null && uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
                uri.rawUserInfo == null && uri.rawFragment == null && (uri.port == -1 || uri.port in 1..65535)) {
                "请填写 HTTPS ZIP/7z 下载链接，不支持带账号密码的地址"
            }
            require(!uri.host.equals("localhost", true) && !uri.host.endsWith(".localhost", true)) { "请填写公开汉化包的下载链接" }
            return uri.toASCIIString()
        }
    }
}

/** Keeps the unmodified imported archive separate from the generated, effective package. */
internal data class OfficialSupplement(val originalRevision: String, val originalArchive: String,
    val version: String, val added: Int) {
    fun json() = JSONObject().put("originalRevision", originalRevision).put("originalArchive", originalArchive)
        .put("version", version).put("added", added)
    companion object {
        fun read(j: JSONObject): OfficialSupplement {
            val revision = j.getString("originalRevision")
            require(revision.matches(Regex("[a-f0-9]{64}")) && j.getString("originalArchive") == "$revision.pack")
            return OfficialSupplement(revision, "$revision.pack", j.getString("version"), j.getInt("added").also { require(it >= 0) })
        }
    }
}

internal enum class ImportFillChoice { Keep, Official, Ai }
internal data class ImportCoverageReview(val sourceId: String, val revision: String, val name: String,
    val report: CoverageReport? = null, val issue: String? = null)
