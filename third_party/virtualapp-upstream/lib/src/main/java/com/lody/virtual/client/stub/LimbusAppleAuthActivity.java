package com.lody.virtual.client.stub;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import com.lody.virtual.client.ipc.VActivityManager;
import com.lody.virtual.helper.compat.LimbusAppleAuthBridge;
import com.lody.virtual.helper.compat.LimbusAppleAuthPolicy;

/** Private browser for the game's Apple flow. It intercepts the package-targeted return before
 * Android would open the separately installed original game. No JS bridge or credential access. */
public final class LimbusAppleAuthActivity extends Activity {
    private WebView browser;
    private TextView status;
    private String event, initialHost;
    private boolean delivered;

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        String url = getIntent().getDataString();
        event = LimbusAppleAuthPolicy.event(url);
        if (event == null) { finish(); return; }
        initialHost = Uri.parse(url).getHost();
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248, 248, 242));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout header = new LinearLayout(this);
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);
        Button back = new Button(this);
        back.setText("返回"); back.setOnClickListener(v -> finish());
        header.addView(back);
        status = new TextView(this);
        status.setText("Apple 账号登录"); status.setTextSize(16);
        status.setTextColor(Color.rgb(31, 67, 56));
        header.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        root.addView(header);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        try { browser = new WebView(this); }
        catch (RuntimeException unavailable) {
            status.setText("请先启用或更新 Android System WebView");
            setContentView(root); return;
        }
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setSaveFormData(false);
        // OAuth uses a redirect in the same window; never expose a Javascript interface.
        settings.setSupportMultipleWindows(false);
        browser.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) {
                progress.setProgress(value); progress.setVisibility(value == 100 ? View.INVISIBLE : View.VISIBLE);
            }
        });
        browser.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return request.isForMainFrame() ? navigate(view, request.getUrl().toString()) : false;
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view, String target) { return navigate(view, target); }
            @Override public void onPageFinished(WebView view, String target) {
                if (!delivered && LimbusAppleAuthPolicy.navigation(target, initialHost))
                    status.setText("Apple 账号登录 · " + Uri.parse(target).getHost());
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) status.setText("登录页面未能加载，请返回后重试");
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, android.webkit.WebResourceResponse response) {
                if (request.isForMainFrame()) status.setText("登录服务暂时不可用，请返回后重试");
            }
            @Override public boolean onRenderProcessGone(WebView view, android.webkit.RenderProcessGoneDetail detail) {
                root.removeView(view); view.destroy(); browser = null;
                status.setText("登录窗口已关闭，请返回后重试"); return true;
            }
        });
        root.addView(browser, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        if (saved == null || browser.restoreState(saved) == null) browser.loadUrl(url);
    }

    private boolean navigate(WebView view, String url) {
        if (delivered) return true;
        if (url.startsWith("intent:") || url.startsWith("genericidp:")) {
            // Only the official Firebase handler may deliver a result to this bound session.
            String current = view.getUrl();
            if (current == null || !LimbusAppleAuthPolicy.navigation(current, initialHost) ||
                    !initialHost.equals(Uri.parse(current).getHost())) return true;
            try {
                Intent incoming = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                Intent result = LimbusAppleAuthBridge.result(incoming, event);
                if (result == null) { status.setText("登录结果无效，请返回后重试"); return true; }
                int code = VActivityManager.get().startActivity(result, 0);
                android.util.Log.i("LimbusVA", "Apple OAuth return forwarded result=" + code);
                if (code == 0) { delivered = true; view.stopLoading(); finish(); }
                else status.setText("未能返回游戏，请返回后重试");
            } catch (Exception ignored) { status.setText("登录结果无效，请返回后重试"); }
            return true;
        }
        if (LimbusAppleAuthPolicy.navigation(url, initialHost)) return false;
        status.setText("请在当前窗口完成 Apple 登录");
        return true;
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        if (browser != null) browser.saveState(state);
        super.onSaveInstanceState(state);
    }
    @Override protected void onDestroy() {
        if (browser != null) { browser.stopLoading(); browser.destroy(); browser = null; }
        super.onDestroy();
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
