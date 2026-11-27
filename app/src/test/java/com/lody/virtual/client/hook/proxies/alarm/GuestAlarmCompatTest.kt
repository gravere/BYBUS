package com.lody.virtual.client.hook.proxies.alarm

import org.junit.Assert.*
import org.junit.Test

class GuestAlarmCompatTest {
    class Service(var denyAgain: Boolean = false) {
        val calls = mutableListOf<List<Any?>>()
        fun set(pkg: String, type: Int, trigger: Long, window: Long, interval: Long, flags: Int,
                operation: Any?, listener: Any?, tag: String?, work: Any?, clock: Any?) {
            calls += listOf(pkg, type, trigger, window, interval, flags, operation, listener, tag, work, clock)
            if (window == 0L || denyAgain) throw SecurityException("Caller needs SCHEDULE_EXACT_ALARM")
        }
    }
    private val method = Service::class.java.methods.single { it.name == "set" }
    private fun args() = arrayOf<Any?>("host", 2, 12345L, 0L, 0L, 5, Any(), null, "reconnect", null, null)

    @Test fun deniedReconnectIsScheduledOnceAsInexactWithoutMutatingCaller() {
        val service = Service(); val args = args()
        assertNull(GuestAlarmCompat.invoke("com.google.android.gms", service, method, args))
        assertEquals(2, service.calls.size)
        assertEquals(0L, args[3]); assertEquals(5, args[5])
        assertEquals(-1L, service.calls.last()[3]); assertEquals(4, service.calls.last()[5])
        assertSame(args[6], service.calls.last()[6])
        for (i in args.indices.filter { it != 3 && it != 5 }) assertEquals(args[i], service.calls.last()[i])
    }

    @Test fun grantedAlarmIsNotChanged() {
        val args = args().also { it[3] = 100L }; val service = Service()
        GuestAlarmCompat.invoke("com.google.android.gms", service, method, args)
        assertEquals(listOf(args.toList()), service.calls)
    }

    @Test fun repeatedDenialAndOtherGuestsAreNotReportedAsSuccess() {
        for ((guest, count) in listOf("com.google.android.gms" to 2, "com.ProjectMoon.LimbusCompany" to 1)) {
            val service = Service(true)
            assertThrows(SecurityException::class.java) { GuestAlarmCompat.invoke(guest, service, method, args()) }
            assertEquals(count, service.calls.size)
        }
    }

    @Test fun unrelatedPermissionAndAlarmClocksNeverDowngrade() {
        assertNull(GuestAlarmCompat.inexactRetry("com.google.android.gms", method.parameterTypes, args(), SecurityException("wrong UID")))
        for (index in listOf(7, 10)) {
            val args = args().also { it[index] = Any() }
            assertNull(GuestAlarmCompat.inexactRetry("com.google.android.gms", method.parameterTypes, args, SecurityException("SCHEDULE_EXACT_ALARM")))
        }
        assertNull(GuestAlarmCompat.inexactRetry("com.google.android.gms", arrayOf(String::class.java), args(), SecurityException("SCHEDULE_EXACT_ALARM")))
    }
}
