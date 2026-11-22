package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.PackageManagerUserScope
import org.junit.Assert.*
import org.junit.Test

class PackageManagerUserScopeTest {
    interface Queries {
        fun getPackageInfo(pkg: String, flags: Int, user: Int): String
        fun getPackageInfo(pkg: String, flags: Long, user: Int): String
        fun getApplicationInfo(pkg: String, flags: Long, user: Int): String
        fun resolveContentProvider(name: String, flags: Int, user: Int): String
        fun getInstalledPackages(flags: Long, user: Int): String
        fun getPermissionFlags(permission: String, pkg: String, user: Int): String
        fun checkUidSignatures(first: Int, second: Int): Int
        fun queryContentProviders(process: String, uid: Int, flags: Long, metadata: String?): String
        fun getPackageInfo(pkg: String, flags: Long, user: Int, vendorOption: Int): String
    }

    @Test fun systemQueriesUsePhysicalUserAndLeaveVirtualArgumentsUntouched() {
        for (method in Queries::class.java.methods.filter { it.name != "checkUidSignatures" && it.name != "queryContentProviders" && it.parameterCount < 4 }) {
            val args = method.parameterTypes.map { when (it) {
                String::class.java -> "fixture"
                Long::class.javaPrimitiveType -> 0x100000000L
                else -> 0
            } }.toTypedArray()
            val routed = PackageManagerUserScope.arguments(method, args, 0, 11)
            assertEquals(method.name, 11, routed.last())
            assertEquals(method.name, 0, args.last())
            args.dropLast(1).forEachIndexed { index, value -> assertEquals(value, routed[index]) }
            assertSame(args, PackageManagerUserScope.arguments(method, args, 0, 0))
        }
    }

    @Test fun explicitOtherProfilesAndUserConstantsAreNotRewritten() {
        val method = Queries::class.java.getMethod("getPackageInfo", String::class.java, Long::class.javaPrimitiveType, Int::class.javaPrimitiveType)
        for (user in listOf(-1, -2, 10, 11, 12)) {
            val args = arrayOf<Any>("fixture", 0L, user)
            assertSame(args, PackageManagerUserScope.arguments(method, args, 0, 11))
        }
        assertEquals(11, PackageManagerUserScope.arguments(method, arrayOf("fixture", 0L, 2), 2, 11).last())
    }

    @Test fun uidFlagsAndUnknownVendorSignaturesStayIntact() {
        for (method in Queries::class.java.methods.filter { it.name == "checkUidSignatures" || it.parameterCount == 4 }) {
            val args = method.parameterTypes.map { if (it == String::class.java) "fixture" else if (it == Long::class.javaPrimitiveType) 0L else 0 }.toTypedArray()
            assertSame(method.name, args, PackageManagerUserScope.arguments(method, args, 0, 11))
        }
    }

    interface SystemQuery { fun getPackageInfo(pkg: String, flags: Long, user: Int): String }

    @Test fun realBoundaryRoutesBeforeInvocationAndPreservesSecurityException() {
        val denied = SecurityException("fixture-denial")
        val original = object : SystemQuery {
            override fun getPackageInfo(pkg: String, flags: Long, user: Int): String {
                assertEquals(11, user)
                assertEquals(0x100000000L, flags)
                if (pkg == "denied") throw denied
                return "host-profile-11"
            }
        }
        val scoped: SystemQuery = PackageManagerUserScope.wrap(original, arrayOf(SystemQuery::class.java), { 0 }, { 11 })
        assertEquals("host-profile-11", scoped.getPackageInfo("fixture", 0x100000000L, 0))
        try {
            scoped.getPackageInfo("denied", 0x100000000L, 0)
            fail("The real permission failure must be preserved")
        } catch (actual: SecurityException) { assertSame(denied, actual) }
    }
}
