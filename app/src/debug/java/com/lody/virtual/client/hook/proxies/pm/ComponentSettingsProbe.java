package com.lody.virtual.client.hook.proxies.pm;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.remote.ClientConfig;
import java.lang.reflect.Field;
import org.json.JSONObject;

/** Public API and cross-process production Binder comparison on a disposable emulator only. */
public final class ComponentSettingsProbe {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final String ABSENT = "io.github.gravere.query.fixture.absent";

    public static JSONObject inspect(Context context) throws Exception {
        require(VirtualCore.get().isVAppProcess(), "guest process");
        require(VActivityManager.get().getSystemPid() != Process.myPid(), "separate server");
        require(Process.myUid() >= 10000, "ordinary app UID");
        ApplicationInfo info = VPackageManager.get().getApplicationInfo(GAME, 0, 0);
        require(info != null, "prepared registry");
        PackageManager pm = context.createConfigurationContext(context.getResources().getConfiguration()).getPackageManager();
        // Even an unbound guest Context already has a hooked PackageManager. The saved
        // pre-injection instance is needed for a genuine platform control.
        PackageManager platform = VirtualCore.get().getUnHookPackageManager();
        Field pmBinder = platform.getClass().getDeclaredField("mPM"); pmBinder.setAccessible(true);
        require(!java.lang.reflect.Proxy.isProxyClass(pmBinder.get(platform).getClass()), "unhooked platform control");
        ComponentName missing = new ComponentName(ABSENT, ABSENT + ".Missing");
        JSONObject results = new JSONObject();
        String missingPlatform = outcome(() -> platform.getComponentEnabledSetting(missing));
        String nullPlatform = outcome(() -> platform.getComponentEnabledSetting(null));
        String defaultPlatform = outcome(() -> platform.getComponentEnabledSetting(
                new ComponentName(context.getPackageName(), context.getPackageName() + ".Missing")));
        results.put("platform_missing_package", missingPlatform);
        results.put("platform_null", nullPlatform);
        results.put("platform_known_package_unset_class", defaultPlatform);
        Field configField = VClient.class.getDeclaredField("clientConfig"); configField.setAccessible(true);
        Field boundField = VClient.class.getDeclaredField("mBoundApplication"); boundField.setAccessible(true);
        Object savedConfig = configField.get(VClient.get()), savedBound = boundField.get(VClient.get());
        require(savedConfig == null && savedBound == null, "unused client");
        boolean passed;
        try {
            ClientConfig config = new ClientConfig();
            config.vuid = info.uid; config.packageName = GAME; config.processName = GAME;
            configField.set(VClient.get(), config);
            java.lang.reflect.Constructor<?> ctor = boundField.getType().getDeclaredConstructor(VClient.class);
            ctor.setAccessible(true); Object bound = ctor.newInstance(VClient.get());
            Field appInfo = bound.getClass().getDeclaredField("appInfo"); appInfo.setAccessible(true);
            appInfo.set(bound, info); boundField.set(VClient.get(), bound);
            String missingVirtual = outcome(() -> pm.getComponentEnabledSetting(missing));
            String nullVirtual = outcome(() -> pm.getComponentEnabledSetting(null));
            ComponentName known = new ComponentName(GAME, GAME + ".Missing");
            String defaultVirtual = outcome(() -> pm.getComponentEnabledSetting(known));
            String hidden = outcome(() -> pm.getComponentEnabledSetting(
                    new ComponentName(context.getPackageName(), context.getPackageName() + ".Missing")));
            String missingUser = outcome(() -> VPackageManager.get().getComponentEnabledSetting(known, 9999));
            results.put("container_missing_package", missingVirtual);
            results.put("container_null", nullVirtual);
            results.put("container_known_package_unset_class", defaultVirtual);
            results.put("container_hidden_host", hidden);
            results.put("container_missing_user", missingUser);
            passed = "IllegalArgumentException".equals(missingPlatform) && missingPlatform.equals(missingVirtual)
                    && "state_0".equals(nullPlatform) && nullPlatform.equals(nullVirtual)
                    && "state_0".equals(defaultPlatform) && defaultPlatform.equals(defaultVirtual)
                    && "IllegalArgumentException".equals(hidden) && "state_2".equals(missingUser);
        } finally {
            boundField.set(VClient.get(), savedBound); configField.set(VClient.get(), savedConfig);
        }
        JSONObject persistence = com.lody.virtual.server.pm.ComponentStateProbe.inspect(context);
        return new JSONObject().put("passed", passed && persistence.getBoolean("passed"))
                .put("sdk", Build.VERSION.SDK_INT).put("ordinary_app_uid", true)
                .put("public_framework_api", true).put("server_over_binder", true)
                .put("game_started", false).put("client_restored", VClient.get().getCurrentApplicationInfo() == null)
                .put("results", results).put("persistence", persistence);
    }

    private interface Query { int get() throws Exception; }
    private static String outcome(Query query) {
        try { return "state_" + query.get(); }
        catch (Exception error) { return error.getClass().getSimpleName(); }
    }
    private static void require(boolean value, String name) { if (!value) throw new AssertionError(name); }
}
