package com.lody.virtual.client.hook.providers;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.IInterface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Uses the same holders and reference accounting as ActivityThread, without OEM acquisition. */
public final class PlatformProviderRoute {
    public interface Manager { Object get(); }
    private final Object thread;
    private final Manager manager;
    private final Method existing, getProvider, install, appThread;
    private final Field provider, info, noRelease;

    public PlatformProviderRoute(Object thread, Manager manager) throws ReflectiveOperationException {
        this.thread = thread;
        this.manager = manager;
        Class<?> holder = Class.forName("android.app.ContentProviderHolder");
        Class<?> threadType = Class.forName("android.app.ActivityThread");
        Class<?> applicationThread = Class.forName("android.app.IApplicationThread");
        Class<?> am = Class.forName("android.app.IActivityManager");
        existing = accessible(threadType.getDeclaredMethod("acquireExistingProvider",
                Context.class, String.class, int.class, boolean.class));
        install = accessible(threadType.getDeclaredMethod("installProvider", Context.class,
                holder, ProviderInfo.class, boolean.class, boolean.class, boolean.class));
        if (install.getReturnType() != holder) throw new NoSuchMethodException("Provider holder result");
        appThread = accessible(threadType.getDeclaredMethod("getApplicationThread"));
        Method selected;
        try {
            selected = am.getMethod("getContentProvider", applicationThread, String.class,
                    String.class, int.class, boolean.class);
        } catch (NoSuchMethodException oldPlatform) {
            selected = am.getMethod("getContentProvider", applicationThread, String.class,
                    int.class, boolean.class);
        }
        if (selected.getReturnType() != holder) throw new NoSuchMethodException("Provider service result");
        getProvider = accessible(selected);
        provider = holder.getField("provider");
        info = holder.getField("info");
        noRelease = holder.getField("noReleaseNeeded");
        if (info.getType() != ProviderInfo.class || noRelease.getType() != boolean.class
                || !IInterface.class.isAssignableFrom(provider.getType())) {
            throw new NoSuchFieldException("Provider holder contract");
        }
    }

    public IInterface acquire(Context transport, String authority, int user, boolean stable,
                              boolean existingOnly) throws Throwable {
        return acquire(transport, authority, user, user, stable, existingOnly);
    }

    public IInterface acquire(Context transport, String authority, int physicalUser, int cacheUser,
                              boolean stable, boolean existingOnly) throws Throwable {
        // Framework provider keys use the ProviderInfo's virtual user. System requests use the
        // physical Android user, including when BYBUS itself runs inside a work profile.
        IInterface cached = (IInterface) invoke(existing, thread, transport, authority, cacheUser, stable);
        if (cached != null || existingOnly) return cached;
        return acquireFresh(transport, authority, physicalUser, stable);
    }

    /** The virtual AM proxy resolves the provider Binder synchronously, including placeholder holders. */
    public IInterface acquireFresh(Context transport, String authority, int user, boolean stable) throws Throwable {
        Object service = manager.get();
        if (service == null) throw new IllegalStateException("Virtual ActivityManager is unavailable");
        Object caller = invoke(appThread, thread);
        Object holder = getProvider.getParameterTypes().length == 5
                ? invoke(getProvider, service, caller, transport.getPackageName(), authority, user, stable)
                : invoke(getProvider, service, caller, authority, user, stable);
        if (holder == null) return null;
        // Never manufacture a holder or a configuration provider in the caller process.
        if (provider.get(holder) == null) throw new IllegalStateException("Virtual provider Binder is unavailable");
        Object installed = invoke(install, thread, transport, holder, info.get(holder),
                true, noRelease.getBoolean(holder), stable);
        return installed == null ? null : (IInterface) provider.get(installed);
    }

    private static Method accessible(Method method) { method.setAccessible(true); return method; }

    static Object invoke(Method method, Object receiver, Object... args) throws Throwable {
        try { return method.invoke(receiver, args); }
        catch (InvocationTargetException e) { throw e.getCause(); }
    }
}
