package com.example.limbuszhcn.update

/** Hashing thousands of resources must not allocate a Formatter for every digest byte. */
internal fun ByteArray.toLowerHex(): String {
    val alphabet = "0123456789abcdef"
    val output = CharArray(size * 2)
    for (index in indices) {
        val value = this[index].toInt() and 255
        output[index * 2] = alphabet[value ushr 4]
        output[index * 2 + 1] = alphabet[value and 15]
    }
    return String(output)
}
