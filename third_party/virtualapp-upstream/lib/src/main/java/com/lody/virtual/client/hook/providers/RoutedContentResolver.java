package com.lody.virtual.client.hook.providers;

import android.content.ContentResolver;
import android.content.Context;
import android.content.IContentProvider;
import android.os.IInterface;

import com.lody.virtual.helper.compat.ProviderRoutePolicy;

import java.lang.reflect.Method;

/** Real host transport identity, virtual application identity remains on the owning Context. */
public final class RoutedContentResolver extends ContentResolver {
    public interface Router {
        boolean owns(String authority);
        IInterface acquire(Context transport, String authority, int user, boolean stable,
                           boolean existingOnly) throws Throwable;
    }

    private final Context transport;
    private final ContentResolver original;
    private final int physicalUser;
    private final Router router;
    private final Method acquire, acquireExisting, acquireUnstable, release, releaseUnstable, died, anr;

    public RoutedContentResolver(Context transport, ContentResolver original, int physicalUser,
                                 Router router) throws ReflectiveOperationException {
        super(transport);
        this.transport = transport;
        this.original = original;
        this.physicalUser = physicalUser;
        this.router = router;
        acquire = method("acquireProvider", Context.class, String.class);
        acquireExisting = method("acquireExistingProvider", Context.class, String.class);
        acquireUnstable = method("acquireUnstableProvider", Context.class, String.class);
        release = method("releaseProvider", IContentProvider.class);
        releaseUnstable = method("releaseUnstableProvider", IContentProvider.class);
        died = method("unstableProviderDied", IContentProvider.class);
        anr = method("appNotRespondingViaProvider", IContentProvider.class);
    }

    private IContentProvider acquire(String raw, boolean stable, boolean existingOnly) {
        String authority = ProviderRoutePolicy.localAuthority(raw, physicalUser);
        try {
            if (router != null && authority != null && router.owns(authority)) {
                return (IContentProvider) router.acquire(transport, authority, physicalUser, stable, existingOnly);
            }
            return (IContentProvider) PlatformProviderRoute.invoke(
                    existingOnly ? acquireExisting : stable ? acquire : acquireUnstable,
                    original, transport, raw);
        } catch (Throwable error) { throw propagate(error); }
    }

    // Hidden platform overrides: not present in the public SDK stub, retained explicitly for R8.
    protected IContentProvider acquireProvider(Context ignored, String authority) { return acquire(authority, true, false); }
    protected IContentProvider acquireExistingProvider(Context ignored, String authority) { return acquire(authority, true, true); }
    protected IContentProvider acquireUnstableProvider(Context ignored, String authority) { return acquire(authority, false, false); }
    public boolean releaseProvider(IContentProvider provider) { return (Boolean) forward(release, provider); }
    public boolean releaseUnstableProvider(IContentProvider provider) { return (Boolean) forward(releaseUnstable, provider); }
    public void unstableProviderDied(IContentProvider provider) { forward(died, provider); }
    public void appNotRespondingViaProvider(IContentProvider provider) { forward(anr, provider); }

    private Object forward(Method method, IContentProvider provider) {
        try { return PlatformProviderRoute.invoke(method, original, provider); }
        catch (Throwable error) { throw propagate(error); }
    }

    private static Method method(String name, Class<?>... types) throws NoSuchMethodException {
        Method method = ContentResolver.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method;
    }

    private static RuntimeException propagate(Throwable error) {
        if (error instanceof RuntimeException) return (RuntimeException) error;
        if (error instanceof Error) throw (Error) error;
        return new IllegalStateException("Provider routing failed", error);
    }
}
