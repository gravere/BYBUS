package com.lody.virtual.client.hook.proxies.pm;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Process;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.remote.ClientConfig;
import com.lody.virtual.helper.compat.ParceledListSliceCompat;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import org.json.JSONObject;

/** Debug-only JNI/public-API fixture. No game, account or protection library is started. */
public final class NativePackageQueryProbe {
    private static final String GAME = "com.ProjectMoon.LimbusCompany";
    private static native int[] query(Context context, boolean typed, boolean applications, boolean attached);

    public static JSONObject inspect(Context context) throws Exception {
        require(VirtualCore.get().isVAppProcess(), "guest process");
        require(VActivityManager.get().getSystemPid() != Process.myPid(), "separate server");
        require(Process.myUid() >= 10000, "ordinary app UID");
        System.loadLibrary("bybus_query_probe");
        ApplicationInfo info = VPackageManager.get().getApplicationInfo(GAME, 0, 0);
        require(info != null, "prepared registry");
        Field configField = VClient.class.getDeclaredField("clientConfig"); configField.setAccessible(true);
        Field boundField = VClient.class.getDeclaredField("mBoundApplication"); boundField.setAccessible(true);
        Object savedConfig = configField.get(VClient.get()), savedBound = boundField.get(VClient.get());
        require(savedConfig == null && savedBound == null, "unused client");
        JSONObject checks = new JSONObject();
        try {
            ClientConfig config = new ClientConfig();
            config.vuid = info.uid; config.packageName = GAME; config.processName = GAME;
            configField.set(VClient.get(), config);
            java.lang.reflect.Constructor<?> ctor = boundField.getType().getDeclaredConstructor(VClient.class);
            ctor.setAccessible(true); Object bound = ctor.newInstance(VClient.get());
            Field appInfo = bound.getClass().getDeclaredField("appInfo"); appInfo.setAccessible(true);
            appInfo.set(bound, info); boundField.set(VClient.get(), bound);
            Context wrapper = new ContextWrapper(context.createConfigurationContext(context.getResources().getConfiguration()));
            PackageManager pm = wrapper.getPackageManager();
            List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_META_DATA);
            List<ApplicationInfo> applications = pm.getInstalledApplications(PackageManager.GET_META_DATA);
            require(packages.size() == 2 && applications.size() == 2, "both fixture records returned");
            for (PackageInfo item : packages) {
                require(item.applicationInfo != null && item.applicationInfo.metaData != null
                        && item.applicationInfo.metaData.getInt("bybus.fixture") == 488, "package metadata round trip");
            }
            for (ApplicationInfo item : applications) {
                require(item.metaData != null && item.metaData.getInt("bybus.fixture") == 488, "application metadata round trip");
            }
            checks.put("java_metadata_lists", true);
            // Exercise the concrete framework return type, including its real parcel format.
            Method installedMethod = null;
            for (Method method : Class.forName("android.content.pm.IPackageManager").getMethods()) {
                if (method.getName().equals("getInstalledPackages")) installedMethod = method;
            }
            require(installedMethod != null, "framework installed-packages contract");
            for (int count : new int[]{0, 1, 2}) {
                Object slice = ParceledListSliceCompat.create(packages.subList(0, count), installedMethod);
                require(installedMethod.getReturnType().isInstance(slice), "exact declared Binder return type");
                android.os.Parcel parcel = android.os.Parcel.obtain();
                try {
                    ((android.os.Parcelable) slice).writeToParcel(parcel, 0);
                    parcel.setDataPosition(0);
                    android.os.Parcelable.Creator<?> creator = (android.os.Parcelable.Creator<?>)
                            slice.getClass().getField("CREATOR").get(null);
                    Object restored = creator.createFromParcel(parcel);
                    List<?> list = ParceledListSliceCompat.getList(restored);
                    require(restored.getClass() == slice.getClass() && list.size() == count, "concrete slice parcel round trip");
                    for (Object item : list) {
                        require(((PackageInfo) item).applicationInfo.metaData.getInt("bybus.fixture") == 488,
                                "metadata preserved in concrete parcel format");
                    }
                    require(ParceledListSliceCompat.createLike(list, slice).getClass() == slice.getClass(),
                            "argument wrapper preserves concrete class");
                } finally { parcel.recycle(); }
                checks.put("slice_roundtrip_" + count, true);
            }
            for (boolean typed : new boolean[]{false, true}) {
                if (typed && Build.VERSION.SDK_INT < 33) continue;
                if (typed) {
                    require(pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(128)).size() == 2
                            && pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(128)).size() == 2,
                            "typed Java queries");
                    checks.put("java_typed_lists", true);
                }
                for (boolean apps : new boolean[]{false, true}) {
                    for (boolean attached : new boolean[]{false, true}) {
                        String key = "jni_" + (apps ? "applications" : "packages")
                                + (typed ? "_typed" : "_int") + (attached ? "_attached" : "_java_thread");
                        int[] result = query(wrapper, typed, apps, attached);
                        require(result != null && result.length == 2 && result[0] == 0 && result[1] == 2,
                                key + ": " + java.util.Arrays.toString(result));
                        checks.put(key, true);
                    }
                }
            }
        } finally {
            boundField.set(VClient.get(), savedBound); configField.set(VClient.get(), savedConfig);
        }
        return new JSONObject().put("passed", true).put("sdk", Build.VERSION.SDK_INT)
                .put("ordinary_app_uid", true).put("server_over_binder", true)
                .put("native_attached_thread", true).put("client_restored", VClient.get().getCurrentApplicationInfo() == null)
                .put("game_started", false).put("checks", checks).put("check_count", checks.length());
    }

    private static void require(boolean value, String name) { if (!value) throw new AssertionError(name); }
}
