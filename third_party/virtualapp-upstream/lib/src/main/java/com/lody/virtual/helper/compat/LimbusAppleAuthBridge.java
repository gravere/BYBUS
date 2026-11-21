package com.lody.virtual.helper.compat;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import com.lody.virtual.client.core.VirtualCore;
import java.lang.ref.WeakReference;

/** Only the live guest Apple OAuth Activity can resolve and open the private authorization window. */
public final class LimbusAppleAuthBridge {
    public static final String WINDOW = "com.lody.virtual.client.stub.LimbusAppleAuthActivity";
    private static volatile WeakReference<Activity> pending = new WeakReference<>(null);
    private LimbusAppleAuthBridge() {}

    public static String forLog(Intent intent) {
        if (intent == null) return "null";
        Uri data = intent.getData();
        if (data != null && ("genericidp".equals(data.getScheme()) || "recaptcha".equals(data.getScheme()) ||
                "/__/auth/handler".equals(data.getPath())))
            return "Intent{action=" + intent.getAction() + " data=<auth> component=" + intent.getComponent() + "}";
        return intent.toString();
    }

    public static void created(Activity activity) {
        Intent intent = activity.getIntent();
        if (LimbusAppleAuthPolicy.ACTIVITY.equals(activity.getClass().getName()) &&
                LimbusAppleAuthPolicy.GAME.equals(activity.getPackageName()) && intent != null &&
                "apple.com".equals(intent.getStringExtra("com.google.firebase.auth.KEY_PROVIDER_ID"))) {
            pending = new WeakReference<>(activity);
        }
    }

    public static boolean active() {
        Activity activity = pending.get();
        return activity != null && !activity.isFinishing() && !activity.isDestroyed();
    }

    public static ResolveInfo resolveBrowserProbe(Intent intent) {
        if (!active() || intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) ||
                intent.getData() != null || intent.getType() != null || intent.getComponent() != null ||
                intent.getPackage() != null || intent.getSelector() != null) return null;
        try {
            ResolveInfo result = new ResolveInfo();
            result.activityInfo = VirtualCore.get().getUnHookPackageManager().getActivityInfo(
                    new android.content.ComponentName(VirtualCore.get().getHostPkg(), WINDOW), 0);
            return result;
        } catch (Exception ignored) { return null; }
    }

    public static Intent browserIntent(String caller, Intent intent) {
        if (!LimbusAppleAuthPolicy.GAME.equals(caller) || !active() || intent == null ||
                !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getSelector() != null ||
                LimbusAppleAuthPolicy.event(intent.getDataString()) == null) return null;
        // Retain the SDK's URL exactly. In particular apn and sha1Cert remain the game's identity.
        return new Intent(Intent.ACTION_VIEW, intent.getData())
                .setClassName(VirtualCore.get().getHostPkg(), WINDOW);
    }

    /** An unexported WebView window provides the expected event; no browser Bundle is forwarded. */
    public static Intent result(Intent incoming, String expectedEvent) {
        try {
            Uri uri = incoming.getData();
            if (!Intent.ACTION_VIEW.equals(incoming.getAction()) || uri == null ||
                    !"genericidp".equals(uri.getScheme()) || !"firebase.auth".equals(uri.getHost()) ||
                    !"/".equals(uri.getPath()) || uri.getPort() != -1 || uri.getUserInfo() != null ||
                    uri.getQuery() != null || uri.getFragment() != null || expectedEvent == null) return null;
            String event = incoming.getStringExtra("eventId");
            String error = incoming.getStringExtra("firebaseError");
            String link = incoming.getStringExtra("link");
            // Error callbacks from the official handler may omit eventId. The window is already
            // bound to the request, and only its trusted top-level handler can submit a callback.
            if (event != null && !expectedEvent.equals(event)) return null;
            Intent clean = new Intent(Intent.ACTION_VIEW, Uri.parse("genericidp://firebase.auth/"))
                    .setClassName(LimbusAppleAuthPolicy.GAME, LimbusAppleAuthPolicy.ACTIVITY)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            if (error != null) {
                if (error.isEmpty() || error.length() > 8192 || link != null) return null;
                return clean.putExtra("firebaseError", error);
            }
            if (!expectedEvent.equals(event) || link == null || link.isEmpty() || link.length() > 65536) return null;
            return clean.putExtra("eventId", event).putExtra("link", link)
                    .putExtra("encryptionEnabled", incoming.getBooleanExtra("encryptionEnabled", true));
        } catch (RuntimeException ignored) { return null; }
    }
}
