package com.lody.virtual.client.hook.proxies.pm;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.VersionedPackage;
import android.os.Build;
import android.os.Process;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.remote.ClientConfig;
import com.lody.virtual.server.pm.PackageSetting;
import com.lody.virtual.server.pm.VPackageManagerService;
import com.lody.virtual.server.pm.parser.VPackage;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** Uses the actual production proxy classes and package registry, never a real game or account. */
public final class PackageQueryProbe {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static final String SIBLING = "io.github.gravere.query.fixture.shared";
    private static final String OTHER = "io.github.gravere.query.fixture.other";
    private static final String ABSENT = "io.github.gravere.query.fixture.absent";

    public static JSONObject inspect(Context context) throws Throwable {
        require(VActivityManager.get().getSystemPid() == Process.myPid(), "server process");
        VPackageManagerService service = VPackageManagerService.get();
        Field registry = VPackageManagerService.class.getDeclaredField("mPackages");
        registry.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, VPackage> packages = (Map<String, VPackage>) registry.get(service);
        Field configField = VClient.class.getDeclaredField("clientConfig");
        configField.setAccessible(true);
        Object savedConfig = configField.get(VClient.get());
        Field boundField = VClient.class.getDeclaredField("mBoundApplication");
        boundField.setAccessible(true);
        Object savedBound = boundField.get(VClient.get());
        Field serviceField = VPackageManager.class.getDeclaredField("mService");
        serviceField.setAccessible(true);
        Object savedService = serviceField.get(VPackageManager.get());
        require(savedConfig == null && VClient.get().getCurrentApplicationInfo() == null, "unused virtual client");
        JSONObject checks = new JSONObject();
        int realUid = VirtualCore.get().myUid();
        synchronized (packages) {
            for (String name : new String[]{GAME, SIBLING, OTHER, ABSENT}) require(!packages.containsKey(name), "empty fixture namespace");
            int uid = 19001;
            while (used(packages, uid) || used(packages, uid + 1) || uid == realUid || uid + 1 == realUid) uid += 2;
            require(uid < 19990, "available fixture UID");
            VPackage game = fixture(context, GAME, uid, new byte[]{1, 2});
            VPackage sibling = fixture(context, SIBLING, uid, new byte[]{1, 2});
            VPackage other = fixture(context, OTHER, uid + 1, new byte[]{3, 4});
            packages.put(GAME, game); packages.put(SIBLING, sibling); packages.put(OTHER, other);
            ClientConfig config = new ClientConfig();
            config.vuid = uid; config.packageName = GAME; config.processName = GAME;
            configField.set(VClient.get(), config);
            serviceField.set(VPackageManager.get(), service);
            try {
                // A shared UID does not identify which member is the running application.
                // Model the bound app explicitly, as a real virtual process does.
                Class<?> boundType = boundField.getType();
                java.lang.reflect.Constructor<?> constructor = boundType.getDeclaredConstructor(VClient.class);
                constructor.setAccessible(true);
                Object bound = constructor.newInstance(VClient.get());
                Field appInfo = boundType.getDeclaredField("appInfo"); appInfo.setAccessible(true);
                appInfo.set(bound, game.applicationInfo);
                boundField.set(VClient.get(), bound);
                check(checks, "unknown_uid", call(new MethodProxies.GetPackagesForUid(), Integer.MAX_VALUE) == null);
                check(checks, "negative_uid", call(new MethodProxies.GetPackagesForUid(), -1) == null);
                String[] self = (String[]) call(new MethodProxies.GetPackagesForUid(), realUid);
                check(checks, "physical_uid_shared_members", self != null && self.length == 2 && Arrays.asList(self).containsAll(Arrays.asList(GAME, SIBLING)));
                ((PackageSetting) sibling.mExtras).setInstalled(0, false);
                check(checks, "removed_shared_member", Arrays.equals(service.getPackagesForUid(uid), new String[]{GAME}));
                check(checks, "own_uid_name", GAME.equals(call(new MethodProxies.getNameForUid(), realUid)));
                check(checks, "missing_user_name", service.getNameForUid(999900000 + uid) == null);
                check(checks, "missing_user_package_uid", service.getPackageUid(GAME, 9999) == -1);
                ApplicationInfo single = (ApplicationInfo) call(new MethodProxies.GetApplicationInfo(), GAME, 0, 0);
                check(checks, "single_application_uid", single != null && single.uid == realUid);
                PackageInfo singlePackage = (PackageInfo) call(new MethodProxies.GetPackageInfo(), GAME, 0, 0);
                check(checks, "single_package_uid", singlePackage != null && singlePackage.applicationInfo.uid == realUid);
                check(checks, "package_uid", ((Integer) call(new MethodProxies.GetPackageUid(), GAME, 0, 0)) == realUid);
                List<?> applications = list(call(new MethodProxies.GetInstalledApplications(), 0, 0));
                check(checks, "application_list_uid", applications.stream().anyMatch(it -> GAME.equals(((ApplicationInfo) it).packageName) && ((ApplicationInfo) it).uid == realUid));
                List<?> installed = list(call(new MethodProxies.GetInstalledPackages(), 0, 0));
                check(checks, "package_list_uid", installed.stream().anyMatch(it -> GAME.equals(((PackageInfo) it).packageName) && ((PackageInfo) it).applicationInfo.uid == realUid));
                check(checks, "other_uid_unchanged", ((ApplicationInfo) call(new MethodProxies.GetApplicationInfo(), OTHER, 0, 0)).uid == uid + 1);
                PackageInfo nested = new PackageInfo();
                ActivityInfo ownActivity = new ActivityInfo(); ownActivity.applicationInfo = new ApplicationInfo(game.applicationInfo);
                ActivityInfo otherActivity = new ActivityInfo(); otherActivity.applicationInfo = new ApplicationInfo(other.applicationInfo);
                nested.activities = new ActivityInfo[]{ownActivity, otherActivity};
                Method normalize = MethodProxies.class.getDeclaredMethod("fixLimbusOwnPackageInfo", PackageInfo.class);
                normalize.setAccessible(true); normalize.invoke(null, nested);
                check(checks, "nested_component_uid", ownActivity.applicationInfo.uid == realUid);
                check(checks, "nested_other_uid_unchanged", otherActivity.applicationInfo.uid == uid + 1);
                check(checks, "unknown_package_signature", (Integer) call(new MethodProxies.CheckSignatures(), ABSENT, ABSENT) == PackageManager.SIGNATURE_UNKNOWN_PACKAGE);
                check(checks, "unknown_uid_signature", (Integer) call(new MethodProxies.checkUidSignatures(), Integer.MAX_VALUE, Integer.MAX_VALUE) == PackageManager.SIGNATURE_UNKNOWN_PACKAGE);
                check(checks, "known_signature", (Integer) call(new MethodProxies.CheckSignatures(), GAME, GAME) == PackageManager.SIGNATURE_MATCH);
                check(checks, "different_signature", (Integer) call(new MethodProxies.CheckSignatures(), GAME, OTHER) == PackageManager.SIGNATURE_NO_MATCH);
                check(checks, "physical_uid_signature", (Integer) call(new MethodProxies.checkUidSignatures(), realUid, realUid) == PackageManager.SIGNATURE_MATCH);
                PackageInfo exact = (PackageInfo) call(new MethodProxies.GetPackageInfoVersioned(), new VersionedPackage(GAME, 4097), 0, 0);
                check(checks, "versioned_exact", exact != null && exact.versionCode == 4097 && exact.applicationInfo.uid == realUid);
                check(checks, "versioned_latest", call(new MethodProxies.GetPackageInfoVersioned(), new VersionedPackage(GAME, PackageManager.VERSION_CODE_HIGHEST), 0, 0) != null);
                check(checks, "versioned_mismatch", call(new MethodProxies.GetPackageInfoVersioned(), new VersionedPackage(GAME, 4098), 0, 0) == null);
                check(checks, "versioned_visibility", call(new MethodProxies.GetPackageInfoVersioned(), new VersionedPackage(context.getPackageName(), PackageManager.VERSION_CODE_HIGHEST), 0, 0) == null);
                ((PackageSetting) game.mExtras).setInstalled(0, false);
                check(checks, "uninstalled_uid", service.getPackagesForUid(uid) == null);
                check(checks, "uninstalled_package_uid", service.getPackageUid(GAME, 0) == -1);
                check(checks, "uninstalled_name", service.getNameForUid(uid) == null);
                check(checks, "uninstalled_signature", service.checkSignatures(GAME, GAME) == PackageManager.SIGNATURE_UNKNOWN_PACKAGE);
            } finally {
                boundField.set(VClient.get(), savedBound);
                serviceField.set(VPackageManager.get(), savedService);
                configField.set(VClient.get(), savedConfig);
                packages.remove(GAME); packages.remove(SIBLING); packages.remove(OTHER);
            }
            require(!packages.containsKey(GAME) && !packages.containsKey(SIBLING) && !packages.containsKey(OTHER), "registry cleanup");
        }
        verifyPassiveObserver(checks);
        return new JSONObject().put("passed", true).put("sdk", Build.VERSION.SDK_INT)
                .put("production_package_proxies", true).put("synthetic_package_registry", true)
                .put("registry_restored", true).put("ordinary_app_uid", Process.myUid() >= 10000)
                .put("check_count", checks.length()).put("checks", checks);
    }

    public interface ObserverFixture { String value(); void fail(); }
    private static void verifyPassiveObserver(JSONObject checks) throws Exception {
        final SecurityException original = new SecurityException("fixture");
        ObserverFixture target = new ObserverFixture() {
            public String value() { return "original"; }
            public void fail() { throw original; }
        };
        MethodInvocationStub<ObserverFixture> stub = new MethodInvocationStub<ObserverFixture>(target, ObserverFixture.class) {
            @Override protected void onInvocationResult(Method method, boolean proxied, Object value, Throwable error) {
                throw new IllegalStateException("observer failed");
            }
        };
        check(checks, "observer_preserves_value", "original".equals(stub.getProxyInterface().value()));
        try { stub.getProxyInterface().fail(); throw new AssertionError("original exception missing"); }
        catch (SecurityException actual) { check(checks, "observer_preserves_exception", actual == original); }
    }

    private static boolean used(Map<String, VPackage> packages, int uid) {
        for (VPackage pkg : packages.values()) if (((PackageSetting) pkg.mExtras).appId == uid) return true;
        return false;
    }

    private static VPackage fixture(Context context, String name, int uid, byte[] signature) {
        VPackage pkg = new VPackage(); pkg.packageName = name; pkg.mVersionCode = 4097;
        pkg.applicationInfo = new ApplicationInfo(context.getApplicationInfo());
        pkg.applicationInfo.packageName = name; pkg.applicationInfo.uid = uid;
        pkg.applicationInfo.metaData = null;
        pkg.activities = new ArrayList<>(); pkg.receivers = new ArrayList<>(); pkg.providers = new ArrayList<>();
        pkg.services = new ArrayList<>(); pkg.instrumentation = new ArrayList<>(); pkg.permissions = new ArrayList<>();
        pkg.permissionGroups = new ArrayList<>(); pkg.requestedPermissions = new ArrayList<>(); pkg.protectedBroadcasts = new ArrayList<>();
        pkg.mSignatures = new Signature[]{new Signature(signature)};
        PackageSetting settings = new PackageSetting(); settings.packageName = name; settings.appId = uid;
        settings.setInstalled(0, true); pkg.mExtras = settings;
        return pkg;
    }

    private static Object call(MethodProxy proxy, Object... args) throws Throwable {
        for (Method method : Class.forName("android.content.pm.IPackageManager").getMethods()) {
            if (method.getName().equals(proxy.getMethodName()) && method.getParameterTypes().length == args.length) {
                Class<?>[] types = method.getParameterTypes();
                for (int i = 0; i < args.length; i++) if (types[i] == long.class && args[i] instanceof Integer) args[i] = ((Integer) args[i]).longValue();
                return proxy.call(mirror.android.app.ActivityThread.sPackageManager.get(), method, args);
            }
        }
        throw new AssertionError("Framework method missing: " + proxy.getMethodName());
    }

    private static List<?> list(Object result) throws Exception {
        if (result instanceof List) return (List<?>) result;
        return (List<?>) result.getClass().getMethod("getList").invoke(result);
    }
    private static void check(JSONObject checks, String name, boolean passed) throws Exception { require(passed, name); checks.put(name, true); }
    private static void require(boolean passed, String name) { if (!passed) throw new AssertionError(name); }
}
