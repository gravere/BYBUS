package com.example.limbuszhcn

import org.junit.Assert.assertTrue
import org.junit.Test

class DirectSyscallDeviceTest {
    @Test fun preservesTheKernelCallingConvention() {
        val result = NativeSyscallProbe.check()
        assertTrue(result, result.startsWith("PASS:"))
    }
}
