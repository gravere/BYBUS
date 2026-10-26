package com.example.limbuszhcn.container

import android.content.IntentFilter
import com.lody.virtual.helper.compat.ReceiverRegistrationCompat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Test

class ReceiverRegistrationCompatTest {
    private val legacy = arrayOf(
        Any::class.java, String::class.java, Any::class.java, IntentFilter::class.java,
        String::class.java, Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
    )
    private val modern = arrayOf(
        Any::class.java, String::class.java, String::class.java, String::class.java,
        Any::class.java, IntentFilter::class.java, String::class.java,
        Int::class.javaPrimitiveType!!, Int::class.javaPrimitiveType!!,
    )

    @Test fun modernNullPermissionKeepsTheReceiverAndRegistrationFlags() {
        val receiver = Any()
        val args = arrayOf<Any?>(Any(), "host", null, null, receiver, null, null, 0, 4)
        val original = args.copyOf()
        ReceiverRegistrationCompat.clearRequiredPermission(modern, args)
        assertArrayEquals(original, args)
        assertSame(receiver, args[4])
    }

    @Test fun modernPermissionIsClearedWithoutTouchingTheReceiver() {
        val receiver = Any()
        val args = arrayOf<Any?>(Any(), "host", null, null, receiver, null, "billing.permission", 0, 2)
        val expected = args.copyOf().also { it[6] = null }
        ReceiverRegistrationCompat.clearRequiredPermission(modern, args)
        assertArrayEquals(expected, args)
        assertNull(args[6])
    }

    @Test fun legacySignatureRetainsNullReceiversUsedForStickyQueries() {
        val args = arrayOf<Any?>(Any(), "host", null, null, "some.permission", 0, 0)
        val expected = args.copyOf().also { it[4] = null }
        ReceiverRegistrationCompat.clearRequiredPermission(legacy, args)
        assertArrayEquals(expected, args)
    }

    @Test fun unknownOrMismatchedSignaturesAreLeftAlone() {
        val args = arrayOf<Any?>(Any(), "permission")
        val original = args.copyOf()
        ReceiverRegistrationCompat.clearRequiredPermission(arrayOf(Any::class.java, String::class.java), args)
        ReceiverRegistrationCompat.clearRequiredPermission(modern, args)
        assertArrayEquals(original, args)
    }

    private fun registration(flags: Any? = 0) = arrayOf<Any?>(
        Any(), "host", null, null, Any(), Any(), null, 0, flags,
    )

    @Test fun legacyMicrogGetsExportedWithoutChangingReceiverOrOtherFlagBits() {
        for (api in listOf(34, 35, 36)) for (flags in listOf(0, 1, 8)) {
            val args = registration(flags)
            val expected = args.copyOf().also { it[8] = flags or 2 }
            ReceiverRegistrationCompat.clearRequiredPermission(modern, args)
            ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(api, "com.google.android.gms", 29, modern, args)
            assertArrayEquals(expected, args)
        }
        val oldSignature = arrayOf<Any?>(Any(), "host", Any(), Any(), null, 0, 0)
        ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(34, "com.google.android.gms", 32, legacy, oldSignature)
        assertEquals(2, oldSignature[6])
    }

    @Test fun explicitExportModesAndStickyQueriesStayUnchanged() {
        for (flags in listOf(2, 3, 4, 5, 6, 12, 0)) {
            val args = registration(flags)
            if (flags == 0) args[4] = null // sticky query: no receiver
            val original = args.copyOf()
            ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(36, "com.google.android.gms", 29, modern, args)
            assertArrayEquals(original, args)
        }
        val noFilter = registration().also { it[5] = null }
        val original = noFilter.copyOf()
        ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(36, "com.google.android.gms", 29, modern, noFilter)
        assertArrayEquals(original, noFilter)
    }

    @Test fun compatibilityDoesNotLeakToOtherAppsOrNewTargetsOrOldPlatforms() {
        for (pkg in listOf(null, "com.android.vending", "com.ProjectMoon.LimbusCompany", "com.google.android.gms.fake")) {
            val args = registration()
            val original = args.copyOf()
            ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(36, pkg, 29, modern, args)
            assertArrayEquals(original, args)
        }
        for ((api, target) in listOf(33 to 29, 26 to 29, 36 to 33, 36 to 35, 36 to 0, 36 to -1)) {
            val args = registration()
            val original = args.copyOf()
            ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(api, "com.google.android.gms", target, modern, args)
            assertArrayEquals(original, args)
        }
    }

    @Test fun unknownRegistrationSignaturesAndMalformedFlagsAreNotRewritten() {
        val signatures = listOf(
            null, modern.copyOf().also { it[6] = Any::class.java },
            modern.copyOf().also { it[7] = String::class.java },
            modern.copyOf().also { it[8] = Any::class.java }, legacy,
        )
        for (signature in signatures) {
            val args = registration()
            val original = args.copyOf()
            ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(36, "com.google.android.gms", 29, signature, args)
            assertArrayEquals(original, args)
        }
        val extraArgument = registration() + null
        val originalExtra = extraArgument.copyOf()
        ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(36, "com.google.android.gms", 29,
            modern + Any::class.java, extraArgument)
        assertArrayEquals(originalExtra, extraArgument)
        for (flag in listOf(null, "0")) {
            val args = registration(flag)
            val original = args.copyOf()
            ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(36, "com.google.android.gms", 29, modern, args)
            assertArrayEquals(original, args)
        }
        ReceiverRegistrationCompat.applyLegacyMicrogExportFlag(36, "com.google.android.gms", 29, modern, null)
    }
}
