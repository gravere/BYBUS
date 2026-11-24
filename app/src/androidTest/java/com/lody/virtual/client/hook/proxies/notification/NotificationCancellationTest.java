package com.lody.virtual.client.hook.proxies.notification;

import android.app.NotificationManager;
import android.content.Context;
import android.os.Binder;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VNotificationManager;
import com.lody.virtual.server.interfaces.INotificationManager;
import com.lody.virtual.server.notification.VNotificationManagerService;
import org.junit.Test;
import org.junit.BeforeClass;
import org.junit.AfterClass;
import org.junit.runner.RunWith;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/** Own emulator with the bundled Google service installed; no account or purchase is used. */
@RunWith(AndroidJUnit4.class)
public class NotificationCancellationTest {
    private static final String GUEST = "com.google.android.gms";
    private static Object previousInstrumentation;

    @BeforeClass public static void initializeGuestUserFixtureWithoutInjectingHost() throws Exception {
        // VClient is normally initialized only in a guest. This test exercises its user-0 routing
        // in the instrumentation process; do not install a guest Instrumentation into ActivityThread.
        Class<?> type = com.lody.virtual.client.hook.delegate.AppInstrumentation.class;
        Field field = type.getDeclaredField("gDefault");
        field.setAccessible(true);
        previousInstrumentation = field.get(null);
        if (previousInstrumentation == null) {
            java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(android.app.Instrumentation.class);
            constructor.setAccessible(true);
            field.set(null, constructor.newInstance(InstrumentationRegistry.getInstrumentation()));
        }
    }

    @AfterClass public static void restoreInstrumentationFixture() throws Exception {
        Field field = com.lody.virtual.client.hook.delegate.AppInstrumentation.class.getDeclaredField("gDefault");
        field.setAccessible(true);
        field.set(null, previousInstrumentation);
    }

    public static class Api {
        int calls;
        public void cancelNotificationWithTag(String pkg, String tag, int id, int user) { calls++; }
        public void cancelNotificationWithTag(String pkg, String op, String tag, int id, int user) { calls++; }
        public void cancelNotificationWithTag(String pkg, String tag, int id, long extension) { calls++; }
        public void cancelAllNotifications(String pkg, int user) { calls++; }
    }

    @Test public void nullableTagsOnBothAidlFormsReachTheSameVirtualCancellation() throws Throwable {
        assertTrue("Bundled service fixture missing", VirtualCore.get().isAppInstalled(GUEST));
        Field field = VNotificationManager.class.getDeclaredField("mService");
        field.setAccessible(true);
        Object previous = field.get(VNotificationManager.get());
        Object[][] captured = {null};
        Binder binder = new Binder();
        Object service = Proxy.newProxyInstance(INotificationManager.class.getClassLoader(),
                new Class<?>[]{INotificationManager.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "asBinder": return binder;
                        case "dealNotificationId": return ((Integer) args[0]) + 100;
                        case "dealNotificationTag": return "mapped:" + args[2];
                        case "cancelNotification": captured[0] = args.clone(); return null;
                        default: throw new AssertionError(method.getName());
                    }
                });
        field.set(VNotificationManager.get(), service);
        try {
            Api api = new Api();
            for (String tag : new String[]{null, "browser"}) {
                for (boolean modern : new boolean[]{false, true}) {
                    Class<?>[] types = modern
                            ? new Class<?>[]{String.class, String.class, String.class, int.class, int.class}
                            : new Class<?>[]{String.class, String.class, int.class, int.class};
                    Object[] args = modern ? new Object[]{GUEST, GUEST, tag, 57, 0}
                            : new Object[]{GUEST, tag, 57, 0};
                    Object[] original = args.clone();
                    captured[0] = null;
                    new MethodProxies.CancelNotificationWithTag().call(api,
                            Api.class.getMethod("cancelNotificationWithTag", types), args);
                    assertArrayEquals(new Object[]{GUEST, "mapped:" + tag, 157, 0}, captured[0]);
                    assertArrayEquals(original, args);
                }
            }
            assertEquals(0, api.calls);
        } finally { field.set(VNotificationManager.get(), previous); }
    }

    @Test public void hostUninstalledAndUnknownSignaturesPassThroughUnchanged() throws Throwable {
        Api api = new Api();
        Method modern = Api.class.getMethod("cancelNotificationWithTag", String.class,
                String.class, String.class, int.class, int.class);
        for (String pkg : new String[]{VirtualCore.get().getHostPkg(), "missing.test.package"}) {
            // A tag resembling a virtual package must not be mistaken for the calling package.
            Object[] args = {pkg, pkg, GUEST, 57, 0};
            Object[] original = args.clone();
            new MethodProxies.CancelNotificationWithTag().call(api, modern, args);
            assertArrayEquals(original, args);
            Object[] all = {pkg, 0};
            new MethodProxies.CancelAllNotifications().call(api,
                    Api.class.getMethod("cancelAllNotifications", String.class, int.class), all);
            assertArrayEquals(new Object[]{pkg, 0}, all);
        }
        Object[] unknown = {GUEST, null, 57, 5L};
        new MethodProxies.CancelNotificationWithTag().call(api,
                Api.class.getMethod("cancelNotificationWithTag", String.class, String.class, int.class, long.class), unknown);
        assertEquals(5, api.calls);
    }

    @Test @SuppressWarnings("unchecked") public void sameIdWithDifferentTagOrUserRemainsRegistered() throws Exception {
        VNotificationManagerService service = new VNotificationManagerService();
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Field manager = VNotificationManagerService.class.getDeclaredField("mNotificationManager");
        manager.setAccessible(true);
        manager.set(service, context.getSystemService(NotificationManager.class));
        String pkg = "bybus.notification.test";
        int id = 0x42B11766;
        service.addNotification(id, "test:a", pkg, 0);
        service.addNotification(id, "test:b", pkg, 0);
        service.addNotification(id, "test:a", pkg, 1);
        service.cancelNotification(pkg, "test:a", id, 0);
        Field records = VNotificationManagerService.class.getDeclaredField("mNotifications");
        records.setAccessible(true);
        List<?> remaining = ((Map<String, List<?>>) records.get(service)).get(pkg);
        assertEquals(2, remaining.size());
        service.cancelNotification(pkg, "test:b", id, 0);
        assertEquals(1, remaining.size());
        service.cancelNotification(pkg, "test:a", id, 1);
        assertEquals(0, remaining.size());
    }
}
