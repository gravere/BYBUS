package com.example.limbuszhcn.container

import android.content.IntentFilter
import com.lody.virtual.helper.compat.ReceiverRegistrationCompat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
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
}
