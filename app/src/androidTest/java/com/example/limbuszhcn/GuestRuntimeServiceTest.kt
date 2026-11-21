package com.example.limbuszhcn

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.stub.GuestRuntimeService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class GuestRuntimeServiceTest {
    @Test fun boundEngineIsPrivateAndDoesNotPublishAServiceNotification() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext
        val component = ComponentName(app, GuestRuntimeService::class.java)
        val service = app.packageManager.getServiceInfo(component, 0)
        assertFalse(service.exported)
        assertTrue(service.processName.endsWith(":x"))
        val notifications = app.getSystemService(NotificationManager::class.java)
        val before = notifications.activeNotifications.map { it.id to it.tag }.toSet()
        val ready = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) { ready.countDown() }
            override fun onServiceDisconnected(name: ComponentName) { }
        }
        assertTrue(app.bindService(Intent().setComponent(component), connection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT))
        try {
            assertTrue("Engine binding must succeed", ready.await(15, TimeUnit.SECONDS))
            assertEquals(before, notifications.activeNotifications.map { it.id to it.tag }.toSet())
        } finally { app.unbindService(connection) }
    }
}
