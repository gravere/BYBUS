package com.example.limbuszhcn.translation

import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.util.Locale
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Only locally defined categories escape this parser. Never retain a provider message or cause. */
internal enum class AiServiceReason(val description: String) {
    Authorization("API 授权失败，请检查密钥及模型权限"),
    Quota("API 额度不足，请充值或更换 API 后继续"),
    RateLimit("API 请求受限，请查看服务商额度，或稍后继续"),
    Busy("模型服务繁忙，请稍后继续或更换 API"),
    Timeout("翻译服务响应超时，可稍后继续或更换 API"),
    Model("当前 API 无法使用所选模型，请重新获取模型列表并选择"),
    Endpoint("API 地址或模型未找到，请核对连接设置"),
    Context("本批内容超出模型长度限制，请换用支持更长文本的模型"),
    Parameters("API 不支持本次请求的参数，请使用支持 Chat Completions 的接口或模型"),
    Content("服务商拦截了本批内容，请检查所选文本或更换 API"),
    Redirect("API 地址发生重定向，请填写服务商的最终 HTTPS 地址"),
    Request("翻译请求未被接受，请核对模型及连接设置"),
    Unknown("翻译服务返回异常，可更换 API 后继续")
}

internal open class AiServiceException(val reason: AiServiceReason, val httpStatus: Int,
    val providerStatus: Int = 0) : AiTranslationException(buildString {
    append(reason.description)
    if (httpStatus >= 300) append("（HTTP $httpStatus）")
    else if (providerStatus >= 400) append("（服务错误 $providerStatus）")
    append("。已完成的译文保留。")
})

internal class AiHttpException(val status: Int, reason: AiServiceReason = aiReasonForStatus(status),
    providerStatus: Int = 0) : AiServiceException(reason, status, providerStatus)

internal fun aiReasonForStatus(status: Int): AiServiceReason = when (status) {
    401, 403 -> AiServiceReason.Authorization
    404 -> AiServiceReason.Endpoint
    429 -> AiServiceReason.RateLimit
    408, 504, 524 -> AiServiceReason.Timeout
    in 500..599 -> AiServiceReason.Busy
    in 300..399 -> AiServiceReason.Redirect
    in 400..499 -> AiServiceReason.Request
    else -> AiServiceReason.Unknown
}

private const val MAX_ERROR_BYTES = 16 * 1024
private val errorReadDeadline by lazy {
    ScheduledThreadPoolExecutor(1) { task -> Thread(task, "bybus-ai-error-read").apply { isDaemon = true } }
        .apply { removeOnCancelPolicy = true }
}

/** Changing readTimeout after response headers does not bound every Android HTTP implementation. */
internal fun readAiHttpFailure(connection: HttpURLConnection, status: Int): AiHttpException {
    val deadline = errorReadDeadline.schedule({ runCatching { connection.disconnect() } }, 5, TimeUnit.SECONDS)
    return try {
        connection.errorStream?.use { readAiHttpFailure(status, it) } ?: AiHttpException(status)
    } catch (_: Exception) { AiHttpException(status) }
    finally { deadline.cancel(false) }
}

/** HTTP failures stay failures even when their body is absent, truncated, HTML or invalid JSON. */
internal fun readAiHttpFailure(status: Int, input: InputStream?): AiHttpException {
    val classified = runCatching {
        if (input == null) return@runCatching null
        val bytes = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(2048)
        while (bytes.size() <= MAX_ERROR_BYTES) {
            val count = input.read(buffer, 0, minOf(buffer.size, MAX_ERROR_BYTES + 1 - bytes.size()))
            if (count < 0) break
            bytes.write(buffer, 0, count)
        }
        val raw = bytes.toByteArray()
        if (raw.size > MAX_ERROR_BYTES) return@runCatching null
        val body = parseAiResponseObject(String(raw, Charsets.UTF_8), maxCharacters = MAX_ERROR_BYTES)
        aiServiceFailure(body, status, errorEvent = true)
    }.getOrNull()
    return AiHttpException(status, classified?.reason ?: aiReasonForStatus(status), classified?.providerStatus ?: 0)
}

/** error:null / false / empty string are optional success-envelope fields, not error events. */
internal fun aiServiceFailure(body: JSONObject, httpStatus: Int = 200, errorEvent: Boolean = false): AiServiceException? {
    val value = body.opt("error")
    val present = value != null && value !== JSONObject.NULL && value != false && value != ""
    if (!present && !errorEvent) return null
    val fields = mutableListOf<String>()
    var providerStatus = 0
    fun inspect(value: Any?, depth: Int) {
        if (depth > 2) return
        when (value) {
            is JSONObject -> {
                for (key in listOf("code", "status", "status_code", "type")) {
                    val field = value.opt(key)
                    val token = when (field) { is String -> field.take(96); is Number -> field.toString(); else -> "" }
                    val numeric = token.toIntOrNull()
                    if (providerStatus == 0 && numeric != null && numeric in 400..599) providerStatus = numeric
                    fields += token.lowercase(Locale.ROOT)
                }
                inspect(value.opt("message"), depth + 1)
                inspect(value.opt("error"), depth + 1)
            }
            is String -> {
                val text = value.take(MAX_ERROR_BYTES).trim()
                if (text.startsWith('{')) {
                    val nested = runCatching { parseAiResponseObject(text, maxCharacters = MAX_ERROR_BYTES) }.getOrNull()
                    if (nested != null) { inspect(nested, depth); return }
                }
                fields += text.lowercase(Locale.ROOT)
            }
        }
    }
    inspect(if (present) value else body, 0)
    fun has(vararg values: String) = fields.any { field -> values.any { field.contains(it) } }
    // A known structured category is more informative than a gateway's generic 400/500 wrapper.
    val reason = when {
        has("insufficient_quota", "quota_exceeded", "billing_hard_limit", "insufficient balance", "credit balance", "quota exhausted", "quota has been exhausted", "余额不足", "额度不足") -> AiServiceReason.Quota
        has("invalid_api_key", "authentication_error", "unauthenticated", "permission_denied", "invalid api key", "authentication failed") -> AiServiceReason.Authorization
        has("context_length_exceeded", "context window", "maximum context length", "input too long", "token limit exceeded") -> AiServiceReason.Context
        has("model_not_found", "model_not_supported", "unknown model", "model does not exist", "model is not available", "no available channel", "模型不存在") -> AiServiceReason.Model
        has("unsupported_parameter", "unknown_parameter", "unsupported_value", "unrecognized request argument", "unknown parameter", "unsupported parameter") -> AiServiceReason.Parameters
        has("content_filter", "content_policy_violation", "safety_block", "safety filter") -> AiServiceReason.Content
        has("rate_limit_exceeded", "rate_limit_error", "resource_exhausted", "too many requests", "rate limit") -> AiServiceReason.RateLimit
        has("deadline_exceeded", "request_timeout", "gateway_timeout", "timed out", "timeout") -> AiServiceReason.Timeout
        has("overloaded_error", "overloaded", "capacity", "unavailable", "server_error", "internal_error") -> AiServiceReason.Busy
        providerStatus != 0 -> aiReasonForStatus(providerStatus)
        else -> aiReasonForStatus(httpStatus)
    }
    return AiServiceException(reason, httpStatus, providerStatus)
}
