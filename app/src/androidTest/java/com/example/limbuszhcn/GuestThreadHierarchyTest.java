package com.example.limbuszhcn;

import android.os.Looper;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.lody.virtual.client.VClient;

import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import mirror.java.lang.ThreadGroupN;

import static org.junit.Assert.*;

/** The guest must retain Android's process-wide thread enumeration and crash chain. */
@RunWith(AndroidJUnit4.class)
public class GuestThreadHierarchyTest {
    private static Object previousInstrumentation;

    @BeforeClass public static void initializeGuestFixtureWithoutHostInjection() throws Exception {
        Class<?> type = com.lody.virtual.client.hook.delegate.AppInstrumentation.class;
        Field field = type.getDeclaredField("gDefault");
        field.setAccessible(true);
        previousInstrumentation = field.get(null);
        if (previousInstrumentation == null) {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(android.app.Instrumentation.class);
            constructor.setAccessible(true);
            field.set(null, constructor.newInstance(InstrumentationRegistry.getInstrumentation()));
        }
        VClient.get();
    }

    @AfterClass public static void restoreGuestFixture() throws Exception {
        Field field = com.lody.virtual.client.hook.delegate.AppInstrumentation.class.getDeclaredField("gDefault");
        field.setAccessible(true);
        field.set(null, previousInstrumentation);
    }

    @Test public void guestSetupPreservesThreadEnumerationAndExceptionHandlers() throws Exception {
        ThreadGroup root = Thread.currentThread().getThreadGroup();
        while (root.getParent() != null) root = root.getParent();
        ThreadGroup[] originalGroups = ThreadGroupN.groups.get(root);
        Map<ThreadGroup, ThreadGroup> parents = new IdentityHashMap<>();
        for (ThreadGroup group : originalGroups) {
            if (group != null) parents.put(group, group.getParent());
        }
        Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();
        Thread.UncaughtExceptionHandler mainHandler = Looper.getMainLooper().getThread().getUncaughtExceptionHandler();
        CountDownLatch ready = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread worker = new Thread(new ThreadGroup("guest-enumeration-test"), () -> {
            ready.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
        }, "guest-enumeration-worker");
        worker.start();
        try {
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            assertTrue(Thread.getAllStackTraces().containsKey(worker));
            Method setup = VClient.class.getDeclaredMethod("setupUncaughtHandler", String.class);
            setup.setAccessible(true);
            for (String pkg : new String[] {"com.ProjectMoon.LimbusCompany",
                    "com.google.android.gms", "com.android.vending"}) {
                setup.invoke(VClient.get(), pkg);
                Map<Thread, StackTraceElement[]> threads = Thread.getAllStackTraces();
                Log.i("BYBUS-ThreadTest", "package=" + pkg + " visible=" + threads.size()
                        + " worker=" + threads.containsKey(worker));
                assertTrue("Guest setup hid a live worker from Android", threads.containsKey(worker));
                assertTrue(threads.containsKey(Looper.getMainLooper().getThread()));
                for (Map.Entry<ThreadGroup, ThreadGroup> entry : parents.entrySet()) {
                    assertSame(entry.getValue(), entry.getKey().getParent());
                }
                assertSame(defaultHandler, Thread.getDefaultUncaughtExceptionHandler());
                assertSame(mainHandler, Looper.getMainLooper().getThread().getUncaughtExceptionHandler());
            }
        } finally {
            release.countDown();
            worker.join(5000);
        }
    }
}
