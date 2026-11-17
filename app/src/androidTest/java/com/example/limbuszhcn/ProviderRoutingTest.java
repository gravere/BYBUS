package com.example.limbuszhcn;

import android.app.Application;
import android.app.Instrumentation;
import android.content.ContentProviderClient;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.IContentProvider;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.lody.virtual.client.hook.providers.PlatformProviderRoute;
import com.lody.virtual.client.hook.providers.ProviderRoutingContext;
import com.lody.virtual.client.hook.providers.RoutedContentResolver;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class ProviderRoutingTest {
    private static final String OWNED = "io.github.gravere.bybus.test.routing";
    private static final String DENIED = "io.github.gravere.bybus.test.denied";
    private Context host;
    private GuestContext guest;
    private ProbeResolver original;
    private IContentProvider provider;
    private int calls;

    @Before public void setUp() throws Exception {
        Class.forName("org.lsposed.hiddenapibypass.HiddenApiBypass")
                .getMethod("addHiddenApiExemptions", String[].class).invoke(null, (Object) new String[] { "Landroid/" });
        host = InstrumentationRegistry.getInstrumentation().getTargetContext();
        guest = new GuestContext(host);
        provider = (IContentProvider) Proxy.newProxyInstance(IContentProvider.class.getClassLoader(),
                new Class<?>[] { IContentProvider.class }, (proxy, method, args) -> {
                    if (method.getName().equals("asBinder")) return new Binder();
                    if (method.getName().equals("call")) {
                        // The virtual package remains on the guest Context, not the Binder request.
                        if (Build.VERSION.SDK_INT >= 31) {
                            Object source = args[0];
                            assertEquals(host.getPackageName(), source.getClass().getMethod("getPackageName").invoke(source));
                            assertEquals(host.getApplicationInfo().uid, source.getClass().getMethod("getUid").invoke(source));
                        } else {
                            assertEquals(host.getPackageName(), args[0]);
                        }
                        calls++;
                        Bundle value = new Bundle(); value.putString("result", "fixture"); return value;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        original = new ProbeResolver(guest, provider);
        guest.resolver = original;
    }

    @Test public void reproducesOemPackageRejectionAndUsesHostTransportWithoutChangingGuest() throws Exception {
        assertThrows(SecurityException.class, () -> original.call(Uri.parse("content://system.fixture"), "read", null, null));
        assertEquals(1, original.acquisitions);
        ProviderRoutingContext context = new ProviderRoutingContext(guest, host, 0, null);
        assertEquals("com.google.android.gms", context.getPackageName());
        assertSame(guest.getResources(), context.getResources());
        assertEquals(guest.getFilesDir(), context.getFilesDir());
        Bundle result = context.getContentResolver().call(Uri.parse("content://system.fixture"), "read", null, null);
        assertEquals("fixture", result.getString("result"));
        assertEquals(1, calls);
        assertEquals(1, original.stableReleases);
    }

    @Test public void virtualRouteAvoidsOemAndBalancesStableUnstableAndExistingClients() throws Exception {
        final int[] routes = new int[3];
        RoutedContentResolver resolver = new RoutedContentResolver(host, original, 0, new RoutedContentResolver.Router() {
            @Override public boolean owns(String authority) { return OWNED.equals(authority); }
            @Override public IInterface acquire(Context context, String authority, int user, boolean stable, boolean existingOnly) {
                assertSame(host, context); assertEquals(0, user);
                routes[existingOnly ? 2 : stable ? 0 : 1]++; return provider;
            }
        });
        try (ContentProviderClient stable = resolver.acquireContentProviderClient(OWNED);
             ContentProviderClient unstable = resolver.acquireUnstableContentProviderClient(OWNED)) {
            assertNotNull(stable); assertNotNull(unstable);
            stable.call("read", null, null); unstable.call("read", null, null);
        }
        Method existing = RoutedContentResolver.class.getDeclaredMethod("acquireExistingProvider", Context.class, String.class);
        existing.setAccessible(true);
        assertSame(provider, existing.invoke(resolver, guest, OWNED));
        resolver.releaseProvider(provider);
        resolver.unstableProviderDied(provider); resolver.appNotRespondingViaProvider(provider);
        assertArrayEquals(new int[] {1, 1, 1}, routes);
        assertEquals(0, original.acquisitions);
        assertEquals(2, original.stableReleases); assertEquals(1, original.unstableReleases);
        assertEquals(1, original.deaths); assertEquals(1, original.anrs);
        assertEquals(2, calls);
    }

    @Test public void errorsAreNotHiddenRetriedOrRedirectedToPhysicalGoogleData() throws Exception {
        original.rejectHost = true;
        RoutedContentResolver external = new RoutedContentResolver(host, original, 0, null);
        SecurityException denied = assertThrows(SecurityException.class,
                () -> external.call(Uri.parse("content://system.fixture"), "write", null, null));
        assertEquals("fixture permission denied", denied.getMessage());
        assertEquals(1, original.acquisitions); assertEquals(0, calls);
        RoutedContentResolver unavailable = new RoutedContentResolver(host, original, 0, new RoutedContentResolver.Router() {
            @Override public boolean owns(String authority) { return true; }
            @Override public IInterface acquire(Context c, String a, int u, boolean s, boolean e) throws Throwable {
                throw new NoSuchMethodException("future platform contract");
            }
        });
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> unavailable.call(Uri.parse("content://" + OWNED), "write", null, null));
        assertTrue(error.getCause() instanceof NoSuchMethodException);
        assertEquals(1, original.acquisitions); assertEquals(0, calls);
    }

    @Test public void applicationAttachmentAndDerivedContextsKeepRoutingBeforeCallbacks() throws Exception {
        ProviderRoutingContext context = new ProviderRoutingContext(guest, host, 0, null);
        assertSame(context.getContentResolver(), context.getContentResolver());
        Context config = context.createConfigurationContext(new Configuration(guest.getResources().getConfiguration()));
        assertEquals(guest.getPackageName(), config.getPackageName());
        assertTrue(config.getContentResolver() instanceof RoutedContentResolver);
        assertTrue(context.createDeviceProtectedStorageContext().getContentResolver() instanceof RoutedContentResolver);
        assertTrue(context.createAttributionContext(null).getContentResolver() instanceof RoutedContentResolver);
        AttachedApplication app = (AttachedApplication) new Instrumentation().newApplication(
                AttachedApplication.class.getClassLoader(), AttachedApplication.class.getName(), context);
        assertTrue(app.routedAtAttachment);
        assertEquals(guest.getPackageName(), app.getPackageName());
    }

    @Test public void realFrameworkHolderSupportsRemoteClientsCacheAndBalancedReferences() throws Throwable {
        // Warm only the disposable fixture so this tests a published remote Binder, as the virtual
        // AM proxy supplies. No native container, Google account, or payment operation is involved.
        ContentResolver system = host.getContentResolver();
        try (ContentProviderClient warm = system.acquireContentProviderClient(OWNED)) {
            assertNotNull(warm);
            Object thread = currentThread();
            PlatformProviderRoute route = new PlatformProviderRoute(thread, ProviderRoutingTest::activityManager);
            IInterface fresh = route.acquireFresh(host, OWNED, 0, true);
            assertNotNull(fresh);
            IInterface cached = route.acquire(host, OWNED, 0, false, false);
            assertSame(fresh.asBinder(), cached.asBinder());
            ContentResolver routed = new RoutedContentResolver(host, system, 0, new RoutedContentResolver.Router() {
                @Override public boolean owns(String a) { return OWNED.equals(a); }
                @Override public IInterface acquire(Context c, String a, int u, boolean s, boolean e) throws Throwable {
                    return route.acquire(c, a, u, s, e);
                }
            });
            Bundle result = routed.call(Uri.parse("content://" + OWNED), "read", "payload", null);
            assertEquals(host.getPackageName(), result.getString("caller"));
            assertEquals("read", result.getString("method")); assertEquals("payload", result.getString("arg"));
            assertTrue(((RoutedContentResolver) routed).releaseProvider((IContentProvider) fresh));
            assertTrue(((RoutedContentResolver) routed).releaseUnstableProvider((IContentProvider) cached));
            // Warm stable reference remains valid after the other references were released.
            assertEquals(host.getPackageName(), warm.call("after-release", null, null).getString("caller"));
        }
    }

    @Test public void realPlatformPermissionFailureRemainsVisible() throws Throwable {
        PlatformProviderRoute route = new PlatformProviderRoute(currentThread(), ProviderRoutingTest::activityManager);
        try {
            route.acquireFresh(host, DENIED, 0, true);
            fail("Protected provider must reject this UID");
        } catch (SecurityException expected) {
            assertTrue(expected.getMessage().contains("Permission Denial"));
        }
    }

    private static Object currentThread() throws Exception {
        Method method = Class.forName("android.app.ActivityThread").getDeclaredMethod("currentActivityThread");
        method.setAccessible(true); return method.invoke(null);
    }
    private static Object activityManager() {
        try {
            Method method = Class.forName("android.app.ActivityManager").getDeclaredMethod("getService");
            method.setAccessible(true); return method.invoke(null);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static final class AttachedApplication extends Application {
        boolean routedAtAttachment;
        @Override protected void attachBaseContext(Context context) {
            routedAtAttachment = context.getContentResolver() instanceof RoutedContentResolver;
            super.attachBaseContext(context);
        }
    }
    private static final class GuestContext extends ContextWrapper {
        ContentResolver resolver;
        GuestContext(Context base) { super(base); }
        @Override public String getPackageName() { return "com.google.android.gms"; }
        @Override public String getOpPackageName() { return getPackageName(); }
        @Override public ContentResolver getContentResolver() { return resolver == null ? super.getContentResolver() : resolver; }
        private Context derived(Context c) { GuestContext value = new GuestContext(c); value.resolver = resolver; return value; }
        @Override public Context createConfigurationContext(Configuration c) { return derived(super.createConfigurationContext(c)); }
        @Override public Context createDeviceProtectedStorageContext() { return derived(super.createDeviceProtectedStorageContext()); }
        @Override public Context createAttributionContext(String tag) { return derived(super.createAttributionContext(tag)); }
    }
    private static final class ProbeResolver extends ContentResolver {
        final IContentProvider provider;
        int acquisitions, stableReleases, unstableReleases, deaths, anrs;
        boolean rejectHost;
        ProbeResolver(Context c, IContentProvider p) { super(c); provider = p; }
        protected IContentProvider acquireProvider(Context c, String name) {
            acquisitions++;
            if ("com.google.android.gms".equals(c.getPackageName())) throw new SecurityException("package does not match physical UID");
            if (rejectHost) throw new SecurityException("fixture permission denied");
            return provider;
        }
        protected IContentProvider acquireExistingProvider(Context c, String name) { return acquireProvider(c, name); }
        protected IContentProvider acquireUnstableProvider(Context c, String name) { return acquireProvider(c, name); }
        public boolean releaseProvider(IContentProvider p) { stableReleases++; return true; }
        public boolean releaseUnstableProvider(IContentProvider p) { unstableReleases++; return true; }
        public void unstableProviderDied(IContentProvider p) { deaths++; }
        public void appNotRespondingViaProvider(IContentProvider p) { anrs++; }
    }
}
