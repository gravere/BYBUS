package com.example.limbuszhcn.selfupdate

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

internal class AppUpdateStorageException(cause: Exception) : IOException(
    "更新缓存无法写入，请检查可用空间后重试", cause)

/** Classify only local operations, never an enclosing network read or decoder. */
internal object AppUpdateStorage {
    fun <T> run(operation: () -> T): T = try { operation() }
    catch (error: IOException) { throw AppUpdateStorageException(error) }
    catch (error: SecurityException) { throw AppUpdateStorageException(error) }

    fun delete(file: File) = run {
        if (file.exists() && !file.delete()) throw IOException("Cannot remove update cache")
    }

    fun output(file: File): Output = Output(run { FileOutputStream(file) })

    class Output internal constructor(private val stream: FileOutputStream) : OutputStream() {
        override fun write(value: Int) = AppUpdateStorage.run { stream.write(value) }
        override fun write(bytes: ByteArray, offset: Int, length: Int) = AppUpdateStorage.run { stream.write(bytes, offset, length) }
        override fun flush() = AppUpdateStorage.run { stream.flush() }
        override fun close() = AppUpdateStorage.run { stream.close() }
        fun sync() = AppUpdateStorage.run { stream.fd.sync() }
    }
}
