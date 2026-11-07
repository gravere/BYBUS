package com.lody.virtual.client.hook.proxies.permission;

import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import com.lody.virtual.client.hook.base.BinderInvocationProxy;
import com.lody.virtual.client.hook.base.BinderInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.proxies.pm.PackageManagerStub;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.os.VUserHandle;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

/** Read-only permission queries for the game; grant/revoke and other guests keep the platform path. */
public final class PermissionManagerStub extends BinderInvocationProxy {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final String SERVICE = "permissionmgr";
    // Android VirtualDeviceManager.PERSISTENT_DEVICE_ID_DEFAULT; this container has no remote devices.
    private static final String DEFAULT_DEVICE = "default:0";

    public PermissionManagerStub() throws ClassNotFoundException {
        super(new BinderInvocationStub(Class.forName("android.permission.IPermissionManager$Stub"),
                mirror.android.os.ServiceManager.getService.call(SERVICE)) {
            @Override protected void onInvocationResult(Method method, boolean proxied, Object result, Throwable error) {
                PackageManagerStub.recordQueryResult(method, proxied, result, error);
            }
        }, SERVICE);
    }

    @Override protected void onBindMethods() {
        super.onBindMethods();
        addMethodProxy(new QueryPermission(false));
        addMethodProxy(new QueryPermission(true));
    }

    @Override public void inject() throws Throwable {
        if (getInvocationStub().getBaseInterface() == null)
            throw new IllegalStateException("Permission service unavailable");
        super.inject();
        // ServiceManager alone is insufficient: public framework methods cache this interface.
        Field service = Class.forName("android.app.ActivityThread").getDeclaredField("sPermissionManager");
        service.setAccessible(true);
        service.set(null, getInvocationStub().getProxyInterface());
        Class<?> manager = Class.forName("android.permission.PermissionManager");
        disableLocalCache(manager, "disablePackageNamePermissionCache");
        disableLocalCache(manager, "disablePermissionCache");
        Log.i("LimbusVA", "Read-only permission query bridge installed sdk=" + Build.VERSION.SDK_INT);
    }

    private static void disableLocalCache(Class<?> manager, String name) throws Exception {
        try {
            manager.getDeclaredMethod(name).invoke(null);
        } catch (NoSuchMethodException ignored) {
            // Some framework generations do not cache this query.
        }
    }

    private static boolean signature(Method method, Class<?>... types) {
        return method.getReturnType() == int.class && Arrays.equals(method.getParameterTypes(), types);
    }

    private static final class QueryPermission extends MethodProxy {
        private final boolean uidQuery;

        QueryPermission(boolean uidQuery) { this.uidQuery = uidQuery; }

        @Override public String getMethodName() { return uidQuery ? "checkUidPermission" : "checkPermission"; }

        @Override public boolean isEnable() {
            return isAppProcess() && GAME.equals(getAppPkg());
        }

        @Override public Object call(Object who, Method method, Object... args) throws Throwable {
            if (uidQuery) {
                String permission;
                int uid;
                if (signature(method, String.class, int.class)) {
                    permission = (String) args[0]; uid = (Integer) args[1];
                } else if (signature(method, int.class, String.class, int.class)) {
                    if ((Integer) args[2] != 0) return PackageManager.PERMISSION_DENIED;
                    uid = (Integer) args[0]; permission = (String) args[1];
                } else {
                    return method.invoke(who, args);
                }
                return VActivityManager.get().checkPermission(permission, -1, uid);
            }
            String permission, packageName;
            if (signature(method, String.class, String.class, int.class)) {
                // Android 11: permission precedes package.
                permission = (String) args[0]; packageName = (String) args[1];
            } else if (signature(method, String.class, String.class, String.class, int.class)) {
                // Android 15+: package precedes permission; never discard device identity.
                if (!DEFAULT_DEVICE.equals(args[2])) return PackageManager.PERMISSION_DENIED;
                packageName = (String) args[0]; permission = (String) args[1];
            } else {
                return method.invoke(who, args);
            }
            return VPackageManager.get().checkPermission(permission, packageName, VUserHandle.myUserId());
        }
    }
}
