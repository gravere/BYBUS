package com.lody.virtual.client.hook.providers;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ProviderInfo;
import android.os.IInterface;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.proxies.am.ActivityManagerStub;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.helper.compat.ProviderRoutePolicy;
import com.lody.virtual.os.VUserHandle;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

/** One route for every supported virtual component; no manufacturer or model gates. */
public final class ProviderRouting {
    private static final AtomicBoolean reported = new AtomicBoolean();
    private static final AtomicBoolean failed = new AtomicBoolean();
    private static volatile PlatformProviderRoute route;
    private ProviderRouting() { }

    public static Context wrap(Context base) {
        VirtualCore core = VirtualCore.get();
        if (base == null || base instanceof ProviderRoutingContext || base == core.getContext()
                || !core.isVAppProcess() || !ProviderRoutePolicy.supportedCaller(VClient.get().getCurrentPackage())) {
            return base;
        }
        Context host = core.getContext();
        if (host == null || !core.getHostPkg().equals(host.getPackageName())) {
            throw new IllegalStateException("Host provider transport context is unavailable");
        }
        return new ProviderRoutingContext(base, host, VUserHandle.realUserId(), new RoutedContentResolver.Router() {
            @Override public boolean owns(String authority) {
                // A disabled or other-user virtual provider must not fall through to an unrelated
                // physical provider with the same authority.
                return VPackageManager.get().isVirtualAuthority(authority);
            }
            @Override public IInterface acquire(Context transport, String authority, int physicalUser,
                                                boolean stable, boolean existingOnly) throws Throwable {
                int user = VUserHandle.myUserId();
                ProviderInfo info = VPackageManager.get().resolveContentProvider(authority, 0, user);
                if (info == null || !info.enabled || !core.isAppInstalledAsUser(user, info.packageName)) return null;
                return platformRoute().acquire(transport, authority, physicalUser,
                        user, stable, existingOnly);
            }
        }, VClient.get().getCurrentPackage());
    }

    /** Called before component callbacks; Application attachment is handled by Instrumentation. */
    public static void install(Context component) {
        if (!(component instanceof ContextWrapper) || component == VirtualCore.get().getContext()) return;
        Context original = ((ContextWrapper) component).getBaseContext();
        Context replacement = wrap(original);
        if (replacement == original) return;
        try {
            Field base = ContextWrapper.class.getDeclaredField("mBase");
            base.setAccessible(true);
            base.set(component, replacement);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Component provider context could not be installed", error);
        }
    }

    private static PlatformProviderRoute platformRoute() throws ReflectiveOperationException {
        PlatformProviderRoute current = route;
        if (current != null) return current;
        synchronized (ProviderRouting.class) {
            if (route == null) {
                try {
                    route = new PlatformProviderRoute(VirtualCore.mainThread(), () -> {
                        ActivityManagerStub stub = InvocationStubManager.getInstance().findInjector(ActivityManagerStub.class);
                        return stub == null ? null : stub.getInvocationStub().getProxyInterface();
                    });
                    if (reported.compareAndSet(false, true)) Log.i("LimbusVA", "Virtual provider standard route ready");
                } catch (ReflectiveOperationException error) {
                    if (failed.compareAndSet(false, true)) Log.e("LimbusVA", "Virtual provider platform contract unavailable", error);
                    throw error;
                }
            }
            return route;
        }
    }
}
