package com.example.limbuszhcn

import android.os.DeadObjectException
import android.os.RemoteException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lody.virtual.client.ipc.DeadServiceQuery
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DeadServiceQueryTest {
    @Test fun reacquiresDeadReadOnceAndKeepsTheRealResult() {
        var calls = 0
        var invalidations = 0
        val result = DeadServiceQuery.run({
            if (calls++ == 0) throw DeadObjectException()
            "actual-result"
        }, { invalidations++ })
        assertEquals("actual-result", result)
        assertEquals(2, calls)
        assertEquals(1, invalidations)
    }
    @Test fun persistentDeathDoesNotLoopOrInventEmptySuccess() {
        var calls = 0
        assertThrows(DeadObjectException::class.java) {
            DeadServiceQuery.run<Any>({ calls++; throw DeadObjectException() }, {})
        }
        assertEquals(2, calls)
    }
    @Test fun permissionAndOtherServiceErrorsAreNotRetried() {
        for (error in listOf(SecurityException("denied"), RemoteException("service failure"))) {
            var calls = 0
            val thrown = assertThrows(error.javaClass) {
                DeadServiceQuery.run<Any>({ calls++; throw error }, { fail("must not invalidate") })
            }
            assertSame(error, thrown)
            assertEquals(1, calls)
        }
    }
}
