package com.example.limbuszhcn.translation

internal object AiKeyOrder {
    fun name(value: String, id: String, keys: List<AiKeyChoice>): String = value.trim().also { name ->
        require(name.length in 1..48 && name.none(Char::isISOControl)) { "名称需为 1–48 个字符" }
        require(keys.none { it.id != id && it.name.equals(name, true) }) { "已有同名密钥，请换一个名称" }
    }
    fun validate(ids: List<String>, current: List<String>) {
        require(ids.size == current.size && ids.toSet().size == ids.size && ids.toSet() == current.toSet()) {
            "密钥列表已变化，请重新排序"
        }
    }
}
