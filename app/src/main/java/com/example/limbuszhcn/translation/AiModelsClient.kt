package com.example.limbuszhcn.translation

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.SocketTimeoutException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CancellationException

internal fun normalizedAiEndpoint(value: String): String {
    val uri = try { URI(value.trim().trimEnd('/')) } catch (_: Exception) {
        throw IllegalArgumentException("请填写有效的 HTTPS 服务地址")
    }
    require(uri.scheme == "https" && !uri.host.isNullOrBlank() && uri.rawUserInfo == null &&
        uri.rawQuery == null && uri.rawFragment == null && uri.port in setOf(-1, 443)) {
        "API 地址需为不含凭据的 HTTPS 地址"
    }
    return uri.toString()
}

internal fun validAiModelId(value: String) = value.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))

/** A saved credential belongs to the exact service base, including its path. */
internal fun aiModelsKey(endpoint: String, savedEndpoint: String, replacement: String, savedKey: () -> String): String {
    val address = normalizedAiEndpoint(endpoint)
    val key = if (replacement.isNotBlank()) replacement else if (address == normalizedAiEndpoint(savedEndpoint)) savedKey() else ""
    require(key.length <= 1024 && key.none(Char::isWhitespace)) { "API Key 格式无效" }
    return key
}

internal fun interface AiModelsTransport { fun get(url: String, key: String): String }

/** Read-only model discovery: no credential requirement, config write or inference request. */
internal class AiModelsClient(private val transport: AiModelsTransport = AiModelsTransport { url, key -> get(url, key) }) {
    fun list(endpoint: String, key: String = ""): List<String> {
        val address = normalizedAiEndpoint(endpoint)
        require(key.length <= 1024 && key.none(Char::isWhitespace)) { "API Key 格式无效" }
        if (Thread.currentThread().isInterrupted) throw CancellationException()
        val raw = transport.get("$address/models", key)
        val rows = try { JSONObject(raw).getJSONArray("data") } catch (_: Exception) {
            throw IllegalArgumentException("此服务未返回兼容的模型列表，可手动填写模型")
        }
        require(rows.length() <= 4096) { "模型列表过大，请手动填写模型" }
        val models = (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.opt("id") as? String }
            .filter(::validAiModelId).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        require(models.isNotEmpty()) { "此服务没有返回可选择的模型，可手动填写" }
        return models
    }

    companion object {
        internal fun get(url: String, key: String,
            connect: (String) -> HttpURLConnection = { URI(it).toURL().openConnection() as HttpURLConnection }): String {
            val connection = connect(url)
            try {
                connection.instanceFollowRedirects = false
                connection.useCaches = false
                connection.connectTimeout = 12_000; connection.readTimeout = 15_000
                connection.requestMethod = "GET"
                connection.setRequestProperty("Accept", "application/json")
                connection.setRequestProperty("User-Agent", "BYBUS-Models")
                if (key.isNotEmpty()) connection.setRequestProperty("Authorization", "Bearer $key")
                val status = connection.responseCode
                require(status == 200) { when (status) {
                    401, 403 -> if (key.isEmpty()) "此服务需要 API Key，请填写后重新获取"
                        else "未获准读取模型列表，请检查 Key 或手动填写模型"
                    404, 405 -> "此服务未提供模型列表，请手动填写模型"
                    429 -> "请求过于频繁，请稍后再获取模型"
                    in 300..399 -> "服务地址发生跳转，请填写最终 HTTPS 地址"
                    else -> "暂时无法获取模型（HTTP $status），可稍后重试或手动填写"
                } }
                val output = ByteArrayOutputStream()
                connection.inputStream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        if (Thread.currentThread().isInterrupted) throw CancellationException()
                        val count = input.read(buffer); if (count < 0) break
                        require(output.size() + count <= 4 * 1024 * 1024) { "模型列表过大，请手动填写模型" }
                        output.write(buffer, 0, count)
                    }
                }
                return output.toString("UTF-8")
            } catch (_: SocketTimeoutException) { throw IllegalStateException("获取模型超时，请稍后重试")
            } catch (_: IOException) { throw IllegalStateException("无法连接模型服务，请检查网络和地址")
            } finally { connection.disconnect() }
        }
    }
}
