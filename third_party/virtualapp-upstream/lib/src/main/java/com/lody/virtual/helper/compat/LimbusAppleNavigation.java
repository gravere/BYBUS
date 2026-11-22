package com.lody.virtual.helper.compat;

import java.net.URI;

/** Top-frame provenance survives HTTP/POST redirects before WebView.getUrl() catches up. */
public final class LimbusAppleNavigation {
    private final String initialHost;
    private boolean handlerRequested;
    private boolean observed;

    public LimbusAppleNavigation(String initialHost) { this.initialHost = initialHost; }

    public synchronized boolean requested(String url) {
        if (!LimbusAppleAuthPolicy.navigation(url, initialHost)) return false;
        observed = true;
        handlerRequested = isHandler(url);
        return true;
    }

    public synchronized boolean canReturn(String displayedUrl) {
        return observed ? handlerRequested : isHandler(displayedUrl);
    }

    private boolean isHandler(String url) {
        try {
            URI uri = new URI(url);
            return LimbusAppleAuthPolicy.navigation(url, initialHost)
                    && initialHost.equals(uri.getHost()) && "/__/auth/handler".equals(uri.getRawPath());
        } catch (Exception ignored) { return false; }
    }
}
