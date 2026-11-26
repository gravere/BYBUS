package com.example.limbuszhcn.migration

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

internal object RootResourcePaths {
    const val GAME = "com.ProjectMoon.LimbusCompany"
    const val MAIN = "com.example.limbuszhcn.migration.RootResourceMain"

    fun validateDataDirectory(user: Int, directory: String) {
        require(user in 0..99999) { "Android 用户编号无效" }
        val suffix = "/user/$user/$GAME"
        require(directory == "/data$suffix" || user == 0 && directory == "/data/data/$GAME" ||
            Regex("/mnt/expand/[a-fA-F0-9-]+${Regex.escape(suffix)}").matches(directory)) { "原版游戏数据目录无效" }
    }

    fun roots(user: Int, directory: String): Map<String, Path> {
        validateDataDirectory(user, directory)
        val rawExternal = File("/data/media/$user/Android/data/$GAME")
        val external = (if (rawExternal.isDirectory) rawExternal else
            File("/storage/emulated/$user/Android/data/$GAME")).canonicalFile.toPath()
        val internal = File(directory).canonicalFile.toPath()
        validateDataDirectory(user, internal.toString())
        require(external.toString() in setOf("/data/media/$user/Android/data/$GAME",
            "/storage/emulated/$user/Android/data/$GAME")) { "原版外部资源目录无效" }
        // Resolve the known Android aliases only; links within resource trees are rejected by the exporter.
        val roots = mapOf(
            "assets" to external.resolve("files/Assets"),
            "external-unity-cache" to external.resolve("cache/UnityCache/Shared"),
            "external-files-unity-cache" to external.resolve("files/UnityCache/Shared"),
            "internal-unity-cache" to internal.resolve("cache/UnityCache/Shared"),
            "internal-files-unity-cache" to internal.resolve("files/UnityCache/Shared")
        )
        roots.forEach { (alias, root) ->
            val base = if (alias.startsWith("internal-")) internal else external
            var part: Path? = root
            while (part != null && part.startsWith(base)) {
                require(!Files.isSymbolicLink(part)) { "原版资源目录包含符号链接" }
                part = part.parent
            }
        }
        return roots
    }

    fun quote(value: String): String {
        require(value.none { it == '\u0000' || it == '\n' || it == '\r' })
        return "'" + value.replace("'", "'\\''") + "'"
    }

    fun command(apk: String, user: Int, version: Long, directory: String): String {
        validateDataDirectory(user, directory)
        require(version > 0 && apk.startsWith('/') && apk.endsWith(".apk"))
        return "CLASSPATH=${quote(apk)} /system/bin/app_process /system/bin $MAIN $user $version ${quote(directory)}"
    }

    /** Presence probe only: never invokes su or triggers an authorization prompt. */
    fun findSu(path: String? = System.getenv("PATH")): String? =
        (listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/debug_ramdisk/su") +
            path.orEmpty().split(':').filter { it.startsWith('/') }.map { "$it/su" })
            .distinct().firstOrNull { runCatching { File(it).isFile && File(it).canExecute() }.getOrDefault(false) }
}

internal object RootImportReminder {
    const val PREFS = "root-resource-import"
    const val HANDLED = "launch-reminder-handled"
    fun shouldOffer(handled: Boolean, rootDetected: Boolean, returningToGame: Boolean) =
        !handled && rootDetected && !returningToGame
}
