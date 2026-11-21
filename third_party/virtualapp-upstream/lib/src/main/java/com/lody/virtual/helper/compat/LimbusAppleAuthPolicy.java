package com.lody.virtual.helper.compat;

import java.net.URI;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Map;

/** URL validation only. The original game's Firebase SDK owns identity and result verification. */
public final class LimbusAppleAuthPolicy {
    public static final String GAME = "com.ProjectMoon.LimbusCompany";
    public static final String ACTIVITY = "com.google.firebase.auth.internal.GenericIdpActivity";
    private LimbusAppleAuthPolicy() {}

    public static String event(String url) {
        try {
            if (url == null || url.length() > 32768) return null;
            URI uri = new URI(url);
            if (!secure(uri) || !firebaseHost(uri.getHost()) || !"/__/auth/handler".equals(uri.getRawPath())) return null;
            Map<String, String> args = new HashMap<>();
            for (String part : uri.getRawQuery().split("&")) {
                String[] pair = part.split("=", 2);
                String key = URLDecoder.decode(pair[0], "UTF-8");
                String value = pair.length == 2 ? URLDecoder.decode(pair[1], "UTF-8") : "";
                if (args.put(key, value) != null) return null;
            }
            String event = args.get("eventId");
            if (!GAME.equals(args.get("apn")) || !"apple.com".equals(args.get("providerId")) ||
                    !"signInWithRedirect".equals(args.get("authType")) || event == null ||
                    !event.matches("[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) return null;
            if (empty(args.get("sessionId")) || empty(args.get("apiKey")) || empty(args.get("publicKey")) ||
                    !String.valueOf(args.get("sha1Cert")).matches("[a-fA-F0-9]{40}")) return null;
            return event;
        } catch (Exception ignored) { return null; }
    }

    public static boolean navigation(String url, String initialHost) {
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            return secure(uri) && host != null && (host.equals(initialHost) ||
                    host.equals("appleid.apple.com") || host.equals("account.apple.com") ||
                    host.equals("idmsa.apple.com"));
        } catch (Exception ignored) { return false; }
    }

    private static boolean firebaseHost(String host) {
        return host != null && host.matches("[a-z0-9-]+\\.(firebaseapp\\.com|web\\.app)");
    }
    private static boolean secure(URI uri) {
        return "https".equals(uri.getScheme()) && uri.getRawUserInfo() == null &&
                (uri.getPort() == -1 || uri.getPort() == 443);
    }
    private static boolean empty(String value) { return value == null || value.isEmpty(); }
}
