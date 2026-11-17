package com.lody.virtual.client.hook.providers;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextParams;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.view.Display;

/** Keeps guest resources/business identity while supplying a host-identity resolver. */
public final class ProviderRoutingContext extends ContextWrapper {
    private final Context transport;
    private final int physicalUser;
    private final RoutedContentResolver.Router router;
    private volatile ContentResolver resolver;

    public ProviderRoutingContext(Context base, Context transport, int physicalUser,
                                  RoutedContentResolver.Router router) {
        super(base);
        this.transport = transport;
        this.physicalUser = physicalUser;
        this.router = router;
    }

    @Override public ContentResolver getContentResolver() {
        ContentResolver current = resolver;
        if (current != null) return current;
        synchronized (this) {
            if (resolver == null) {
                try {
                    resolver = new RoutedContentResolver(transport, getBaseContext().getContentResolver(),
                            physicalUser, router);
                } catch (ReflectiveOperationException error) {
                    // Do not accidentally read a physical Google provider under a virtual authority.
                    throw new IllegalStateException("Android provider resolver contract is unavailable", error);
                }
            }
            return resolver;
        }
    }

    private Context derived(Context context) {
        if (context == getBaseContext()) return this;
        if (context instanceof ProviderRoutingContext) return context;
        // Explicit foreign-package contexts keep their own access boundary.
        if (!getPackageName().equals(context.getPackageName())) return context;
        return new ProviderRoutingContext(context, transport, physicalUser, router);
    }

    @Override public Context createConfigurationContext(Configuration config) {
        return derived(super.createConfigurationContext(config));
    }
    @Override public Context createDisplayContext(Display display) {
        return derived(super.createDisplayContext(display));
    }
    @TargetApi(24) @Override public Context createDeviceProtectedStorageContext() {
        return derived(super.createDeviceProtectedStorageContext());
    }
    // Hidden in the public SDK, but present on supported Android versions.
    @TargetApi(24) public Context createCredentialProtectedStorageContext() {
        try {
            return derived((Context) PlatformProviderRoute.invoke(
                    Context.class.getMethod("createCredentialProtectedStorageContext"), getBaseContext()));
        } catch (RuntimeException | Error error) {
            throw error;
        } catch (Throwable error) {
            throw new IllegalStateException("Credential storage context is unavailable", error);
        }
    }
    @Override public Context createPackageContext(String pkg, int flags) throws PackageManager.NameNotFoundException {
        return derived(super.createPackageContext(pkg, flags));
    }
    @TargetApi(26) @Override public Context createContextForSplit(String split) throws PackageManager.NameNotFoundException {
        return derived(super.createContextForSplit(split));
    }
    @TargetApi(30) @Override public Context createAttributionContext(String tag) {
        return derived(super.createAttributionContext(tag));
    }
    @TargetApi(31) @Override public Context createContext(ContextParams params) {
        return derived(super.createContext(params));
    }
    @TargetApi(34) @Override public Context createDeviceContext(int deviceId) {
        return derived(super.createDeviceContext(deviceId));
    }
    @TargetApi(30) @Override public Context createWindowContext(int type, android.os.Bundle options) {
        return derived(super.createWindowContext(type, options));
    }
    @TargetApi(31) @Override public Context createWindowContext(Display display, int type, android.os.Bundle options) {
        return derived(super.createWindowContext(display, type, options));
    }
}
