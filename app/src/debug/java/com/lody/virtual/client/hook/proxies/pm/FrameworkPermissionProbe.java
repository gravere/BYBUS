package com.lody.virtual.client.hook.proxies.pm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.InvocationStubManager;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.proxies.permission.PermissionManagerStub;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.remote.ClientConfig;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.json.JSONObject;

/** Actual framework calls from a separate guest process to the production server over Binder. */
public final class FrameworkPermissionProbe {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final String OTHER = "io.github.gravere.query.fixture.other";
    private static final String ABSENT = "io.github.gravere.query.fixture.absent";
    private static final String INTERNET = "android.permission.INTERNET";
    private static final String ACCOUNT = "android.permission.ACCOUNT_MANAGER";

    public static JSONObject inspect(Context context) throws Exception {
        require(VirtualCore.get().isVAppProcess(), "actual guest process");
        require(VActivityManager.get().getSystemPid() != Process.myPid(), "separate server Binder");
        require(Process.myUid() >= 10000, "ordinary app UID");
        ApplicationInfo info = VPackageManager.get().getApplicationInfo(GAME, 0, 0);
        require(info != null, "server fixture prepared");
        int virtualUid = info.uid;
        // A newly bound guest gets a new ContextImpl, not the host receiver's pre-startup PM cache.
        Context queryContext = context.createConfigurationContext(context.getResources().getConfiguration());
        PackageManager pm = queryContext.getPackageManager();
        JSONObject checks = new JSONObject();
        check(checks, "platform_before_binding_denies_container_only_package",
                pm.checkPermission(INTERNET, GAME) == PackageManager.PERMISSION_DENIED);
        Field configField = VClient.class.getDeclaredField("clientConfig"); configField.setAccessible(true);
        Field boundField = VClient.class.getDeclaredField("mBoundApplication"); boundField.setAccessible(true);
        Object savedConfig = configField.get(VClient.get()), savedBound = boundField.get(VClient.get());
        require(savedConfig == null && savedBound == null, "unused guest client");
        try {
            ClientConfig config = new ClientConfig();
            config.vuid = virtualUid; config.packageName = GAME; config.processName = GAME;
            configField.set(VClient.get(), config);
            java.lang.reflect.Constructor<?> ctor = boundField.getType().getDeclaredConstructor(VClient.class);
            ctor.setAccessible(true); Object bound = ctor.newInstance(VClient.get());
            Field appInfo = bound.getClass().getDeclaredField("appInfo"); appInfo.setAccessible(true);
            appInfo.set(bound, info); boundField.set(VClient.get(), bound);
            check(checks, "public_declared_permission_granted", pm.checkPermission(INTERNET, GAME) == 0);
            check(checks, "public_undeclared_permission_denied", pm.checkPermission(INTERNET, OTHER) == -1);
            check(checks, "public_unknown_package_denied", pm.checkPermission(INTERNET, ABSENT) == -1);
            check(checks, "public_privileged_permission_denied", pm.checkPermission(ACCOUNT, GAME) == -1);
            PackageInfo metadata = pm.getPackageInfo(GAME, PackageManager.GET_PERMISSIONS);
            require(metadata.requestedPermissions != null && metadata.requestedPermissionsFlags != null,
                    "public metadata arrays");
            for (int i = 0; i < metadata.requestedPermissions.length; i++) {
                boolean granted = (metadata.requestedPermissionsFlags[i] & PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0;
                require(granted == (pm.checkPermission(metadata.requestedPermissions[i], GAME) == 0),
                        "public metadata and checkPermission agree");
            }
            check(checks, "public_permission_matches_package_metadata", true);
            check(checks, "context_virtual_uid_granted", context.checkPermission(INTERNET, -1, virtualUid) == 0);
            check(checks, "context_virtual_uid_undeclared_denied", context.checkPermission(INTERNET, -1, virtualUid + 1) == -1);
            check(checks, "context_unknown_uid_denied", context.checkPermission(INTERNET, -1, Integer.MAX_VALUE) == -1);
            check(checks, "context_real_uid_grant_preserved", context.checkSelfPermission(INTERNET) == 0);
            check(checks, "context_real_uid_privileged_denied", context.checkSelfPermission(ACCOUNT) == -1);
            PermissionManagerStub bridge = InvocationStubManager.getInstance().findInjector(PermissionManagerStub.class);
            require(bridge != null && !bridge.isEnvBad(), "production permission service installed");
            Object permissionService = bridge.getInvocationStub().getProxyInterface();
            Class<?> contract = Class.forName("android.permission.IPermissionManager");
            Method uidMethod = null, deviceMethod = null;
            for (Method method : contract.getMethods()) {
                if (method.getName().equals("checkUidPermission")) uidMethod = method;
                if (method.getName().equals("checkPermission") && method.getParameterTypes().length == 4) deviceMethod = method;
            }
            require(uidMethod != null, "framework UID query exists");
            Object[] uidArgs = Arrays.equals(uidMethod.getParameterTypes(), new Class<?>[]{String.class, int.class})
                    ? new Object[]{INTERNET, virtualUid} : new Object[]{virtualUid, INTERNET, 0};
            check(checks, "permission_service_virtual_uid_granted", (Integer) uidMethod.invoke(permissionService, uidArgs) == 0);
            if (deviceMethod != null) {
                check(checks, "nondefault_package_device_denied",
                        (Integer) deviceMethod.invoke(permissionService, GAME, INTERNET, "fixture:unavailable", 0) == -1);
                check(checks, "nondefault_uid_device_denied",
                        (Integer) uidMethod.invoke(permissionService, virtualUid, INTERNET, 99) == -1);
            }
            // Exercise the real previous path in the same process, without changing the production proxy.
            boundField.set(VClient.get(), null); configField.set(VClient.get(), null);
            check(checks, "platform_after_unbinding_still_denies", pm.checkPermission(INTERNET, GAME) == -1);
        } finally {
            boundField.set(VClient.get(), savedBound); configField.set(VClient.get(), savedConfig);
        }
        return new JSONObject().put("passed", true).put("sdk", Build.VERSION.SDK_INT)
                .put("public_framework_api", true).put("separate_guest_process", true)
                .put("server_over_binder", true).put("ordinary_app_uid", Process.myUid() >= 10000)
                .put("client_restored", VClient.get().getCurrentApplicationInfo() == null)
                .put("check_count", checks.length()).put("checks", checks);
    }

    private static void check(JSONObject checks, String name, boolean value) throws Exception {
        require(value, name); checks.put(name, true);
    }
    private static void require(boolean value, String name) { if (!value) throw new AssertionError(name); }
}
