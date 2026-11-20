package com.example.limbuszhcn.translation

/** All messages are authored locally. Never attach a provider response or parser cause. */
internal open class AiTranslationException(message: String) : IllegalArgumentException(message)

internal enum class AiValidationReason(val label: String) {
    Missing("未返回译文"), InvalidText("译文为空、过长或含无效字符"),
    Tokens("遗漏或重复了数字、标签等保护标记"), Order("改动了标签或段落顺序"),
    AddedNumber("译文数值无法与原文对应"), AddedFormat("添加了标签、格式或未知标记"),
    Terminology("译名与参考术语不一致"), Cache("格式无法原样保存")
}

internal class AiTextValidationException(val reason: AiValidationReason) : AiTranslationException(reason.label)
internal data class AiRejectedText(val id: String, val position: Int, val reason: AiValidationReason, val context: String = "")

internal class AiBatchValidationException(val rejected: List<AiRejectedText>, savedCount: Int? = null) :
    AiTranslationException(buildString {
        if (savedCount != null && savedCount > 0) append("本批已保存 $savedCount 条；")
        append("${rejected.size} 条未通过检查")
        append(rejected.take(3).joinToString("；", "（", "）") { "本批第 ${it.position} 条：${it.reason.label}" })
        if (savedCount != null) append("。继续时只处理未完成内容。")
        rejected.firstOrNull()?.context?.takeIf { it.isNotBlank() }?.let { append("\n位置：$it") }
    })
