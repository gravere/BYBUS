package com.lody.virtual.client.hook.proxies.content;

import android.os.Binder;
import android.os.DeadObjectException;
import android.os.RemoteException;
import android.database.IContentObserver;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.lody.virtual.client.ipc.VContentManager;
import com.lody.virtual.server.interfaces.IContentService;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ContentObserverCleanupTest {
    public static class Framework {
        int calls;
        public void unregisterContentObserver(IContentObserver observer) { calls++; }
    }

    private interface Cleanup { void run() throws Throwable; }

    private static IContentService service(Cleanup cleanup) {
        Binder binder = new Binder();
        return (IContentService) Proxy.newProxyInstance(IContentService.class.getClassLoader(),
                new Class<?>[]{IContentService.class}, (proxy, method, args) -> {
                    if (method.getName().equals("asBinder")) return binder;
                    if (method.getName().equals("unregisterContentObserver")
                            || method.getName().equals("removeStatusChangeListener")) {
                        cleanup.run();
                        return null;
                    }
                    throw new AssertionError("Unexpected reconnect or call: " + method.getName());
                });
    }

    @Test public void frameworkObserverUnregistersWithoutVirtualService() throws Throwable {
        Field cache = VContentManager.class.getDeclaredField("mService");
        cache.setAccessible(true);
        Object before = cache.get(VContentManager.get());
        try {
            cache.set(VContentManager.get(), null);
            Framework framework = new Framework();
            MethodProxies.unregisterContentObserver(framework,
                    Framework.class.getMethod("unregisterContentObserver", IContentObserver.class),
                    new Object[]{null});
            VContentManager.get().removeStatusChangeListener(null);
            assertEquals(1, framework.calls);
            assertNull("Cleanup must not start a new virtual server", cache.get(VContentManager.get()));
        } finally { cache.set(VContentManager.get(), before); }
    }

    @Test public void liveAndFailedVirtualCleanupStillUnregistersFrameworkObserver() throws Throwable {
        Field cache = VContentManager.class.getDeclaredField("mService");
        cache.setAccessible(true);
        Object before = cache.get(VContentManager.get());
        try {
            int[] calls = {0};
            Framework framework = new Framework();
            for (boolean disconnected : new boolean[]{false, true}) {
                cache.set(VContentManager.get(), service(() -> {
                    calls[0]++;
                    if (disconnected) throw new RemoteException("temporary transport failure");
                }));
                MethodProxies.unregisterContentObserver(framework,
                        Framework.class.getMethod("unregisterContentObserver", IContentObserver.class),
                        new Object[]{null});
            }
            assertEquals(2, calls[0]);
            assertEquals(2, framework.calls);
        } finally { cache.set(VContentManager.get(), before); }
    }

    @Test public void oldServerDeathDoesNotClearReconnectedService() throws Exception {
        Field cache = VContentManager.class.getDeclaredField("mService");
        cache.setAccessible(true);
        Object before = cache.get(VContentManager.get());
        try {
            int[] newCalls = {0};
            IContentService replacement = service(() -> newCalls[0]++);
            cache.set(VContentManager.get(), service(() -> {
                cache.set(VContentManager.get(), replacement);
                throw new DeadObjectException();
            }));
            VContentManager.get().unregisterContentObserver(null);
            assertSame(replacement, cache.get(VContentManager.get()));
            VContentManager.get().removeStatusChangeListener(null);
            assertEquals(1, newCalls[0]);
        } finally { cache.set(VContentManager.get(), before); }
    }

    @Test public void deadServerIsClearedButProgrammingFailuresAreNotHidden() throws Exception {
        Field cache = VContentManager.class.getDeclaredField("mService");
        cache.setAccessible(true);
        Object before = cache.get(VContentManager.get());
        try {
            cache.set(VContentManager.get(), service(() -> { throw new DeadObjectException(); }));
            VContentManager.get().removeStatusChangeListener(null);
            assertNull(cache.get(VContentManager.get()));
            SecurityException failure = new SecurityException("denied");
            cache.set(VContentManager.get(), service(() -> { throw failure; }));
            try { VContentManager.get().unregisterContentObserver(null); fail("Expected original exception"); }
            catch (SecurityException actual) { assertSame(failure, actual); }
        } finally { cache.set(VContentManager.get(), before); }
    }
}
