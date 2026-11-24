package com.lody.virtual.server.pm;

import android.content.ComponentName;
import android.content.pm.PackageManager;
import android.os.Parcel;
import org.json.JSONObject;

/** Exercises the existing schema using Parcels and a disposable settings fixture. */
public final class ComponentStateProbe {
    public static JSONObject inspect(android.content.Context context) throws Exception {
        JSONObject result = new JSONObject();
        java.io.File file = java.io.File.createTempFile("component-state-", ".fixture", context.getCacheDir());
        if (!file.delete()) throw new java.io.IOException("Unable to prepare fixture");
        try {
            ComponentStateManager empty = new ComponentStateManager(file);
            java.lang.reflect.Field field = ComponentStateManager.class.getDeclaredField("states"); field.setAccessible(true);
            ComponentName component = new ComponentName("fixture.only", "Unseen");
            int state = empty.getComponentState(component, 123);
            result.put("read_without_allocation", state == 0 && ((android.util.SparseArray<?>) field.get(empty)).size() == 0);
            empty.setComponentState(component, 1, 0);
            empty.setComponentState(component, 2, 12);
            ComponentStateManager restored = new ComponentStateManager(file);
            result.put("saved_settings_loaded", restored.getComponentState(component, 0) == 1
                    && restored.getComponentState(component, 12) == 2 && restored.getComponentState(component, 123) == 0);
            boolean rejected = false;
            try { restored.setComponentState(component, 99, 0); }
            catch (IllegalArgumentException expected) { rejected = true; }
            result.put("invalid_write_preserves_settings", rejected && restored.getComponentState(component, 0) == 1);
        } finally { if (file.exists() && !file.delete()) throw new java.io.IOException("Unable to clean fixture"); }
        for (int count : new int[]{0, 2}) {
            Parcel parcel = Parcel.obtain();
            try {
                ComponentStateManager.UserComponentState source = new ComponentStateManager.UserComponentState();
                ComponentName enabled = new ComponentName("fixture.only", "Enabled");
                ComponentName disabled = new ComponentName("fixture.only", "Disabled");
                if (count > 0) {
                    source.getOrCreate(enabled).state = PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
                    source.getOrCreate(disabled).state = PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
                }
                source.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                ComponentStateManager.UserComponentState copy = ComponentStateManager.UserComponentState.CREATOR.createFromParcel(parcel);
                boolean same = copy.states.size() == count && parcel.dataAvail() == 0;
                if (count > 0) same &= copy.states.get(enabled).state == 1 && copy.states.get(disabled).state == 2;
                result.put("roundtrip_" + count, same ? "preserved" : "changed");
            } catch (Exception error) {
                result.put("roundtrip_" + count, error.getClass().getSimpleName());
            } finally { parcel.recycle(); }
        }
        boolean legacyPreserved = true;
        for (int stored : new int[]{-1, 0, 1, 2, 3, 4, 99}) {
            Parcel parcel = Parcel.obtain();
            try {
                parcel.writeInt(stored);
                parcel.setDataPosition(0);
                int restored = ComponentState.CREATOR.createFromParcel(parcel).state;
                int expected = stored >= 0 && stored <= 2 ? stored : 0;
                legacyPreserved &= restored == expected && parcel.dataAvail() == 0;
            } finally { parcel.recycle(); }
        }
        result.put("legacy_invalid_values_reset", legacyPreserved);
        result.put("passed", result.getBoolean("saved_settings_loaded") && result.getBoolean("read_without_allocation") && "preserved".equals(result.getString("roundtrip_0"))
                && "preserved".equals(result.getString("roundtrip_2"))
                && result.getBoolean("invalid_write_preserves_settings") && legacyPreserved);
        return result;
    }
}
