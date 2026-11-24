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
        android.content.pm.PackageInfo manifest = platform.getPackageInfo(context.getPackageName(),
                PackageManager.GET_ACTIVITIES | PackageManager.GET_SERVICES
                        | PackageManager.GET_RECEIVERS | PackageManager.GET_PROVIDERS
                        | PackageManager.MATCH_DISABLED_COMPONENTS);
        JSONObject mutation = new JSONObject();
        ComponentName platformMissing = new ComponentName(context.getPackageName(), context.getPackageName() + ".Missing");
        String platformMissingWrite = outcome(() -> {
            platform.setComponentEnabledSetting(platformMissing, 1, PackageManager.DONT_KILL_APP); return 0;
        });
        String platformNullWrite = outcome(() -> {
            platform.setComponentEnabledSetting(null, 1, PackageManager.DONT_KILL_APP); return 0;
        });
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
            String virtualMissingWrite = outcome(() -> {
                pm.setComponentEnabledSetting(known, 1, PackageManager.DONT_KILL_APP); return 0;
            });
            mutation.put("platform_missing_class", platformMissingWrite);
            mutation.put("container_missing_class", virtualMissingWrite);
            boolean mutationPassed = "IllegalArgumentException".equals(platformMissingWrite)
                    && platformMissingWrite.equals(virtualMissingWrite)
                    && defaultVirtual.equals(outcome(() -> pm.getComponentEnabledSetting(known)));
            String virtualNullWrite = outcome(() -> {
                pm.setComponentEnabledSetting(null, 1, PackageManager.DONT_KILL_APP); return 0;
            });
            mutation.put("null_component", new JSONObject().put("platform", platformNullWrite).put("container", virtualNullWrite));
            mutationPassed &= platformNullWrite.equals(virtualNullWrite);
            String absentUserWrite = outcome(() -> {
                VPackageManager.get().setComponentEnabledSetting(known, 1, PackageManager.DONT_KILL_APP, 9999); return 0;
            });
            mutation.put("absent_user_no_op", "state_0".equals(absentUserWrite));
            mutationPassed &= "state_0".equals(absentUserWrite);
            java.util.LinkedHashMap<String,String> classes = new java.util.LinkedHashMap<>();
            for (android.content.pm.ActivityInfo a : manifest.activities) {
                classes.putIfAbsent(a.targetActivity == null ? "activity" : "alias", a.name);
            }
            classes.put("receiver", manifest.receivers[0].name);
            classes.put("service", manifest.services[0].name);
            classes.put("provider", manifest.providers[0].name);
            require(classes.size() == 5, "all component kinds and activity alias");
            for (java.util.Map.Entry<String,String> entry : classes.entrySet()) {
                ComponentName original = new ComponentName(context.getPackageName(), entry.getValue());
                ComponentName virtual = new ComponentName(GAME, entry.getValue());
                int savedOriginal = platform.getComponentEnabledSetting(original);
                int savedVirtual = pm.getComponentEnabledSetting(virtual);
                try {
                    for (int next : new int[]{1, 2, 3, 4, -1, 99, 0}) {
                        String expected = writeThenRead(platform, original, next);
                        String actual = writeThenRead(pm, virtual, next);
                        mutation.put(entry.getKey() + "_" + next,
                                new JSONObject().put("platform", expected).put("container", actual));
                        mutationPassed &= expected.equals(actual);
                    }
                } finally {
                    platform.setComponentEnabledSetting(original, savedOriginal, PackageManager.DONT_KILL_APP);
                    pm.setComponentEnabledSetting(virtual, savedVirtual, PackageManager.DONT_KILL_APP);
                }
            }
            mutation.put("passed", mutationPassed);
            passed = "IllegalArgumentException".equals(missingPlatform) && missingPlatform.equals(missingVirtual)
                    && "state_0".equals(nullPlatform) && nullPlatform.equals(nullVirtual)
                    && "state_0".equals(defaultPlatform) && defaultPlatform.equals(defaultVirtual)
                    && "IllegalArgumentException".equals(hidden) && "state_2".equals(missingUser) && mutationPassed;
        } finally {
            boundField.set(VClient.get(), savedBound); configField.set(VClient.get(), savedConfig);
        }
        JSONObject persistence = com.lody.virtual.server.pm.ComponentStateProbe.inspect(context);
        return new JSONObject().put("passed", passed && persistence.getBoolean("passed"))
                .put("sdk", Build.VERSION.SDK_INT).put("ordinary_app_uid", true)
                .put("public_framework_api", true).put("server_over_binder", true)
                .put("game_started", false).put("client_restored", VClient.get().getCurrentApplicationInfo() == null)
                .put("results", results).put("mutation", mutation).put("persistence", persistence);
    }

    private static String writeThenRead(PackageManager pm, ComponentName component, int state) {
        String outcome = outcome(() -> {
            pm.setComponentEnabledSetting(component, state, PackageManager.DONT_KILL_APP); return 0;
        });
        return outcome + ";after=" + pm.getComponentEnabledSetting(component);
    }

    private interface Query { int get() throws Exception; }
    private static String outcome(Query query) {
        try { return "state_" + query.get(); }
        catch (Exception error) { return error.getClass().getSimpleName(); }
    }
    private static void require(boolean value, String name) { if (!value) throw new AssertionError(name); }
}
