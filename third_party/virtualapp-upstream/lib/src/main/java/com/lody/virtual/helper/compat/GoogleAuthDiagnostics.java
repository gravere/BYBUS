package com.lody.virtual.helper.compat;

import android.app.Activity;
import com.lody.virtual.client.core.VirtualCore;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Fixed protocol stages only; usable even when an OEM encrypts logcat. */
public final class GoogleAuthDiagnostics {
    public enum Event { BRIDGE_START, BRIDGE_FAILED, BRIDGE_RESULT,
        IDENTITY_CREATE, IDENTITY_RESUME, IDENTITY_FINISH, IDENTITY_RECREATE,
        ACCOUNT_CREATE, ACCOUNT_RESUME, ACCOUNT_FINISH, ACCOUNT_RECREATE,
        LOGIN_CREATE, LOGIN_RESUME, LOGIN_FINISH, LOGIN_RECREATE,
        BROKER_REQUEST, BROKER_RESULT, SERVICE_REQUEST, RECIPIENT_GONE }
    private static String request;
    private static int count;
    private GoogleAuthDiagnostics() { }

    public static void activity(Activity activity, String phase) {
        if (!MicrogAuthUiPolicy.GMS.equals(activity.getPackageName())) return;
        String type = activity.getClass().getName();
        String prefix = MicrogAuthUiPolicy.ASSISTED.equals(type) ? "IDENTITY_"
                : MicrogAuthUiPolicy.AUTH.equals(type) ? "ACCOUNT_"
                : MicrogAuthUiPolicy.LOGIN.equals(type) ? "LOGIN_" : null;
        if (prefix == null) return;
        try {
            int result = activity.isFinishing() ? mirror.android.app.Activity.mResultCode.get(activity) : 0;
            record(Event.valueOf(prefix + phase), result, activity.isChangingConfigurations());
        } catch (Exception ignored) { }
    }

    public static synchronized void record(Event event, int result, boolean present) {
        if (event == null) return;
        try {
            File root = new File(VirtualCore.get().getContext().getFilesDir(), "translation-cache/translation-index");
            String current = "none";
            File launch = new File(root, "launch.request");
            if (launch.isFile()) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(launch), StandardCharsets.UTF_8))) {
                    String value = reader.readLine();
                    if (value != null && value.matches("[0-9a-f-]{36}")) current = value;
                }
            }
            if (!current.equals(request)) { request = current; count = 0; }
            if (count >= 160 || (!root.isDirectory() && !root.mkdirs())) return;
            File target = new File(root, "google-auth-state.log");
            if (target.length() > 32 * 1024 - 512) return;
            String line = "time=" + System.currentTimeMillis() + " pid=" + android.os.Process.myPid()
                    + " request=" + current + " event=" + event.name() + " result=" + result
                    + " present=" + (present ? 1 : 0) + "\n";
            try (FileOutputStream output = new FileOutputStream(target, true)) {
                output.write(line.getBytes(StandardCharsets.UTF_8));
            }
            count++;
        } catch (Exception ignored) {
            // Observation must not alter an account operation or its result.
        }
    }
}
