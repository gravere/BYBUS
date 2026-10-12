package com.lody.virtual.helper.compat;

import android.os.Bundle;
import android.database.Cursor;
import android.content.Intent;

import com.lody.virtual.client.core.VirtualCore;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Locale;

/** Bounded protocol metadata for OEMs that withhold app logcat from ADB. */
public final class LimbusBillingDiagnostics {
    private static int records;
    private static boolean registrationIdRecorded;

    private LimbusBillingDiagnostics() { }

    /** Only the real scalar response code, never the result payload or purchase data. */
    public static void recordResultBroadcast(boolean received, Intent intent) {
        try {
            if (intent == null || !LimbusBillingBroadcastPolicy.isResultAction(intent.getAction())) return;
            int response = intent.getIntExtra("RESPONSE_CODE", -1);
            if (response < -3 || response > 20) response = -1;
            record("event=billing_update_" + (received ? "received" : "sent") + " code=" + response
                    + " action=" + (LimbusBillingBroadcastPolicy.LOCAL_ACTION.equals(intent.getAction())
                    ? "local" : "external"));
        } catch (Exception ignored) {
            // An unreadable diagnostic must not change dispatch or the original result.
        }
    }

    /** Inspect only the registration query's shape and presence; never persist its value. */
    public static synchronized void recordRegistrationIdQuery(Object[] args, Object result) {
        if (registrationIdRecorded || args == null) return;
        if (!MicrogRegistrationCompat.isRegistrationIdQuery(args)) return;
        registrationIdRecorded = true;
        if (!(result instanceof Cursor)) {
            record("event=google_registration_id readable=0 nonzero=0");
            return;
        }
        Cursor cursor = (Cursor) result;
        int position = cursor.getPosition();
        boolean readable = false;
        boolean nonzero = false;
        try {
            if (cursor.getCount() == 1 && cursor.getColumnCount() == 2 && cursor.moveToFirst()
                    && "android_id".equals(cursor.getString(0))) {
                String value = cursor.getString(1);
                readable = value != null && value.matches("-?[0-9]{1,20}");
                nonzero = readable && new java.math.BigInteger(value).signum() != 0;
            }
        } finally {
            cursor.moveToPosition(position);
        }
        record("event=google_registration_id readable=" + (readable ? 1 : 0)
                + " nonzero=" + (nonzero ? 1 : 0));
    }

    public static String describeCall(Object[] args, Object result) {
        int api = -1;
        String caller = "none";
        String productType = "none";
        int itemCount = -1;
        if (args != null && args.length >= 3 && args[0] instanceof Integer &&
                args[1] instanceof String && args[2] instanceof String) {
            int requestedApi = (Integer) args[0];
            if (requestedApi >= 0 && requestedApi <= 100) api = requestedApi;
            caller = "com.ProjectMoon.LimbusCompany".equals(args[1]) ? "game" :
                    VirtualCore.get().getHostPkg().equals(args[1]) ? "host" : "other";
            productType = "inapp".equals(args[2]) ? "inapp" :
                    "subs".equals(args[2]) ? "subs" : "other";
            if (args.length > 3 && args[3] instanceof Bundle) {
                ArrayList<String> items = ((Bundle) args[3]).getStringArrayList("ITEM_ID_LIST");
                if (items != null) itemCount = items.size();
            }
        }
        String reason = "none";
        if (result instanceof Bundle) {
            String message = ((Bundle) result).getString("DEBUG_MESSAGE");
            if (message != null && !message.isEmpty()) {
                // Fixed categories only: never persist the service's message.
                String lower = message.length() > 4096 ? "" : message.toLowerCase(Locale.ROOT);
                reason = lower.contains("df-dferh-01") ? "play_backend" :
                        lower.contains("uid") ? "uid" :
                        lower.contains("package") ? "package" :
                        lower.contains("signature") ? "signature" :
                        lower.contains("permission") ? "permission" :
                        lower.contains("account") ? "account" :
                        lower.contains("owned") ? "ownership" :
                        lower.contains("version") ? "version" :
                        lower.contains("not supported") ? "unsupported" :
                        lower.contains("internal") ? "internal" :
                        lower.contains("network") ? "network" :
                        lower.contains("product") || lower.contains("sku") ? "product" : "other";
            }
        }
        return " api=" + api + " caller=" + caller + " type=" + productType +
                " items=" + itemCount + " debug=" + reason;
    }

    // Callers supply fixed event names, method names, response codes and counts.
    // Never pass a service message, argument, account, product ID or receipt.
    public static synchronized void record(String metadata) {
        if (records >= 72 || metadata == null ||
                !metadata.matches("[A-Za-z0-9._= -]{1,256}")) return;
        records++;
        try {
            File directory = new File(VirtualCore.get().getContext().getFilesDir(),
                    "translation-cache/translation-index");
            String request;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    new FileInputStream(new File(directory, "launch.request")), StandardCharsets.UTF_8))) {
                request = reader.readLine();
            }
            if (request == null || !request.matches("[0-9a-f-]{36}")) return;
            File target = new File(directory, "billing-state.log");
            if (target.length() >= 32 * 1024 - 512) return;
            String line = (target.length() == 0 ?
                    "request=" + request + " pid=" + android.os.Process.myPid() + "\n" : "")
                    + metadata + "\n";
            try (FileOutputStream output = new FileOutputStream(target, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // Diagnostics cannot affect service binding, initialization or payment.
        }
    }
}
