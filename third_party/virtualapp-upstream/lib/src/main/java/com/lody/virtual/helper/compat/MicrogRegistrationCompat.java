package com.lody.virtual.helper.compat;

import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.ipc.VPackageManager;
import com.lody.virtual.os.VUserHandle;

/** Uses microG's own completed check-in when its legacy GServices entry is absent. */
public final class MicrogRegistrationCompat {
    private MicrogRegistrationCompat() { }

    public static boolean isRegistrationIdQuery(Object[] args) {
        if (args == null) return false;
        boolean rootUri = false;
        boolean selected = false;
        for (Object arg : args) {
            if (arg instanceof Uri) {
                String uri = arg.toString();
                rootUri |= "content://com.google.android.gsf.gservices".equals(uri)
                        || "content://com.google.android.gsf.gservices/".equals(uri);
            }
            String[] selection = arg instanceof Bundle ? ((Bundle) arg).getStringArray(
                            "android:query-arg-sql-selection-args") : null;
            selected |= MicrogBillingIdentityPolicy.isRegistrationIdSelection(selection);
        }
        return rootUri && selected;
    }

    // Caller must already have resolved the exact installed virtual GServicesProvider.
    public static Object resolveMissingId(Object[] args, Object original) {
        if (Build.VERSION.SDK_INT < 36
                || !"com.android.vending".equals(VClient.get().getCurrentPackage())) return original;
        try {
            if (!isRegistrationIdQuery(args) || !isMissingId(original)) return original;
            Context context = VClient.get().getCurrentApplication();
            if (context == null) return original;
            ProviderInfo owner = VPackageManager.get().resolveContentProvider(
                    "com.google.android.gms.microg.settings", 0, VUserHandle.myUserId());
            if (owner == null || !MicrogBillingIdentityPolicy.isGoogleSettingsProvider(
                    owner.packageName, owner.name, owner.authority)) return original;
            Uri settings = Uri.parse("content://com.google.android.gms.microg.settings/check-in");
            String[] projection = {"checkin_enable_service", "androidId", "lastCheckin"};
            String registeredId;
            try (Cursor state = context.getContentResolver().query(settings, projection, null, null, null)) {
                if (state == null || state.getColumnCount() != 3 || !state.moveToFirst()) return original;
                registeredId = MicrogBillingIdentityPolicy.registeredId(
                        state.getInt(0) != 0, state.getLong(1), state.getLong(2));
            }
            if (registeredId == null) {
                LimbusBillingDiagnostics.record("event=google_registration_id_bridge registered=0");
                return original;
            }
            MatrixCursor replacement = new MatrixCursor(new String[]{"name", "value"}, 1);
            replacement.addRow(new String[]{"android_id", registeredId});
            if (original instanceof Cursor) {
                try { ((Cursor) original).close(); } catch (Exception ignored) { }
            }
            LimbusBillingDiagnostics.record("event=google_registration_id_bridge registered=1");
            return replacement;
        } catch (Exception unavailable) {
            LimbusBillingDiagnostics.record("event=google_registration_id_bridge unavailable=1");
            return original;
        }
    }

    private static boolean isMissingId(Object result) {
        if (result == null) return true;
        if (!(result instanceof Cursor)) return false;
        Cursor cursor = (Cursor) result;
        int position = cursor.getPosition();
        try {
            if (cursor.getCount() == 0) return true;
            if (cursor.getCount() != 1 || cursor.getColumnCount() != 2 || !cursor.moveToFirst()
                    || !"android_id".equals(cursor.getString(0))) return false;
            String value = cursor.getString(1);
            return value == null || value.isEmpty() || "0".equals(value);
        } finally {
            cursor.moveToPosition(position);
        }
    }
}
