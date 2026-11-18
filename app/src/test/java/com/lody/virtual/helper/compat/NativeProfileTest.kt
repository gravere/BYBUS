package com.lody.virtual.helper.compat

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NativeProfileTest {
    @get:Rule val temp = TemporaryFolder()

    private fun library(page: Long, machine: Short = 183): ByteArray {
        val elf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN)
        elf.put(byteArrayOf(127, 69, 76, 70, 2, 1, 1))
        elf.putShort(16, 3)
        elf.putShort(18, machine)
        elf.putInt(20, 1)
        elf.putLong(32, 64)
        elf.putShort(52, 64)
        elf.putShort(54, 56)
        elf.putShort(56, 1)
        elf.putInt(64, 1)
        elf.putLong(96, 4096)
        elf.putLong(104, 4096)
        elf.putLong(112, page)
        return elf.array()
    }

    private fun split(vararg libraries: Pair<String, ByteArray>): String {
        val file = File.createTempFile("split", ".apk", temp.root)
        ZipOutputStream(file.outputStream()).use { zip ->
            for ((name, bytes) in libraries) {
                zip.putNextEntry(ZipEntry("lib/arm64-v8a/$name.so"))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        return file.path
    }

    @Test fun readsNativeLibrariesAcrossSplitsOnBothPageSizes() {
        val binary = library(16384)
        val paths = listOf(split("libmain" to binary, "libunity" to binary),
            split("libil2cpp" to binary, "libcovault-appsec" to binary))
        LimbusNativeProfile.requireLibraryAlignment(paths, 4096, true)
        LimbusNativeProfile.requireLibraryAlignment(paths.reversed(), 16384, true)
        assertThrows(IOException::class.java) {
            LimbusNativeProfile.requireLibraryAlignment(paths + split("libmain" to binary), 4096, true)
        }
    }

    @Test fun onlyActuallyPresentAndLoadableComponentsPass() {
        val binary = library(4096)
        val base = listOf(split("libmain" to binary, "libunity" to binary, "libil2cpp" to binary))
        LimbusNativeProfile.requireLibraryAlignment(base, 4096, false)
        assertThrows(IOException::class.java) { LimbusNativeProfile.requireLibraryAlignment(base, 4096, true) }
        assertThrows(IOException::class.java) { LimbusNativeProfile.requireLibraryAlignment(base, 16384, false) }
        assertThrows(IOException::class.java) {
            LimbusNativeProfile.requireLibraryAlignment(base + split("libextra" to library(4096, 62)), 4096, false)
        }
    }
}
