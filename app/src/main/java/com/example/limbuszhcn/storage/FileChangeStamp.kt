package com.example.limbuszhcn.storage

import android.os.Build
import android.system.Os
import android.system.OsConstants
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

/** Android's NIO unix timestamps lose sub-second precision; use the platform timespec instead. */
internal fun reliableFileChangeStamp(file: Path): String? = runCatching {
    if (Build.VERSION.SDK_INT > 0) {
        if (Build.VERSION.SDK_INT < 27) return@runCatching null
        val stat = Os.lstat(file.toString())
        if (!OsConstants.S_ISREG(stat.st_mode)) return@runCatching null
        val changed = stat.st_ctim; val modified = stat.st_mtim
        if (changed.tv_nsec == 0L && modified.tv_nsec == 0L) return@runCatching null
        "${stat.st_dev}|${stat.st_ino}|${stat.st_size}|${changed.tv_sec}:${changed.tv_nsec}|${modified.tv_sec}:${modified.tv_nsec}"
    } else {
        val attrs = Files.readAttributes(file, "unix:dev,ino,size,ctime,lastModifiedTime", LinkOption.NOFOLLOW_LINKS)
        listOf("dev", "ino", "size", "ctime", "lastModifiedTime").joinToString("|") { requireNotNull(attrs[it]).toString() }
    }
}.getOrNull()
