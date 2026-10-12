package com.example.limbuszhcn.container

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import java.util.zip.ZipFile

/** Read-only bootstrap hints. Unsupported Unity layouts are deliberately ignored. */
internal object BundledBattleHints {
    private val names = setOf("JP_BattleHint", "JP_BattleHint_NormalBattle", "JP_BattleHint_AbnorBattle")
    private val assetPath = Regex("assets/bin/Data/[0-9a-f]{32}")
    private const val MAX_FILE = 1024 * 1024
    private const val PREFIX_SIZE = 512
    private data class Header(val name: String, val textOffset: Int, val textSize: Int)

    fun read(apkPaths: List<String>): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val ambiguous = mutableSetOf<String>()
        for (path in apkPaths.distinct().sorted()) ZipFile(path).use { zip ->
            for (entry in zip.entries()) {
                if (!assetPath.matches(entry.name) || entry.size !in 144..MAX_FILE.toLong()) continue
                zip.getInputStream(entry).use asset@ { input ->
                    val prefix = ByteArray(minOf(PREFIX_SIZE.toLong(), entry.size).toInt())
                    java.io.DataInputStream(input).readFully(prefix)
                    val header = header(prefix, entry.size.toInt()) ?: return@asset
                    val bytes = ByteArray(entry.size.toInt())
                    prefix.copyInto(bytes)
                    java.io.DataInputStream(input).readFully(bytes, prefix.size, bytes.size - prefix.size)
                    val text = decodeText(bytes, header) ?: return@asset
                    val name = header.name + ".json"
                    if (name in ambiguous) return@asset
                    val previous = result.put(name, text)
                    if (previous != null && previous != text) {
                        result.remove(name)
                        ambiguous.add(name)
                    }
                }
            }
        }
        return result
    }

    internal fun parse(bytes: ByteArray): Pair<String, String>? {
        val header = header(bytes, bytes.size) ?: return null
        return decodeText(bytes, header)?.let { header.name + ".json" to it }
    }

    private fun decodeText(bytes: ByteArray, header: Header): String? = runCatching {
        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, header.textOffset, header.textSize)).toString()
    }.getOrNull()

    private fun header(prefix: ByteArray, fileSize: Int): Header? = runCatching {
        require(fileSize in 144..MAX_FILE)
        val b = ByteBuffer.wrap(prefix).order(ByteOrder.BIG_ENDIAN)
        // v22 extended header. This reader supports one non-script TextAsset,
        // little endian Android, no type tree/references, and bounded metadata.
        require(b.int == 0 && b.int == 0 && b.int == 22 && b.int == 0)
        require(b.int == 0) // endian byte + three reserved bytes
        val metadataSize = b.int
        require(metadataSize in 64..400 && b.long == fileSize.toLong())
        val dataOffset = b.long
        require(dataOffset in (48L + metadataSize)..480L && b.long == 0L)
        b.order(ByteOrder.LITTLE_ENDIAN)
        val version = StringBuilder()
        while (true) {
            val char = b.get().toInt()
            if (char == 0) break
            require(char in 32..126 && version.length < 32)
            version.append(char.toChar())
        }
        require(version.startsWith("6000.") || version.startsWith("2022."))
        require(b.int == 13 && b.get().toInt() == 0 && b.int == 1)
        require(b.int == 49 && b.get().toInt() == 0 && b.short.toInt() == -1)
        b.position(b.position() + 16) // type hash
        require(b.int == 1)
        b.position((b.position() + 3) and -4)
        b.long // path ID is identity metadata, not display text
        require(b.long == 0L)
        val objectSize = b.int
        require(objectSize.toLong() == fileSize - dataOffset && b.int == 0)
        require(b.int == 0 && b.int == 0 && b.int == 0 && b.get().toInt() == 0)
        require(b.position() == 48 + metadataSize)
        b.position(dataOffset.toInt())
        val nameSize = b.int
        require(nameSize in 1..64)
        val nameBytes = ByteArray(nameSize).also(b::get)
        val name = String(nameBytes, Charsets.US_ASCII)
        require(name in names)
        b.position((b.position() + 3) and -4)
        val textSize = b.int
        require(textSize > 0 && textSize.toLong() + b.position() == fileSize.toLong())
        Header(name, b.position(), textSize)
    }.getOrNull()
}
