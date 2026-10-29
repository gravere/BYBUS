package com.example.limbuszhcn.container

import com.lody.virtual.helper.compat.MicrogAuthenticatorClient
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class MicrogAuthenticatorQueueTest {
    @Test fun slowAuthorizationDoesNotSerializeTheSecondRequest() {
        val started = CountDownLatch(2)
        val release = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val request = Runnable {
            started.countDown()
            try { release.await(5, TimeUnit.SECONDS) } finally { finished.countDown() }
        }
        try {
            assertTrue(MicrogAuthenticatorClient.execute(request))
            assertTrue(MicrogAuthenticatorClient.execute(request))
            assertTrue("Both requests must start before the first finishes", started.await(3, TimeUnit.SECONDS))
        } finally {
            release.countDown()
            assertTrue(finished.await(3, TimeUnit.SECONDS))
        }
    }
}
