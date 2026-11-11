package com.lody.virtual.helper.compat;

import android.app.Activity;
import android.graphics.Rect;
import android.content.res.Configuration;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowCompat;
import com.lody.virtual.client.VClient;

/** Known layouts in the pinned microG build that predate enforced edge-to-edge windows. */
public final class MicrogActivityInsets {
    private MicrogActivityInsets() { }

    public static void apply(Activity activity) {
        String packageName = VClient.get().getCurrentPackage();
        if ("com.android.vending".equals(packageName)) {
            String name = activity.getClass().getName();
            // Both pinned implementations use raw full-window content with no inset owner.
            if ("org.microg.vending.billing.ui.InAppBillingHostActivity".equals(name)
                    || "org.microg.vending.billing.ui.PlayWebViewActivity".equals(name)) {
                View content = activity.findViewById(android.R.id.content);
                if (content != null) applyToLegacyRoot(content, activity.getWindow().getDecorView());
            }
            return;
        }
        if (!"com.google.android.gms".equals(packageName)) return;
        boolean assistant = false;
        for (Class<?> type = activity.getClass(); type != null; type = type.getSuperclass()) {
            if ("org.microg.gms.auth.login.AssistantActivity".equals(type.getName())) assistant = true;
        }
        if (!assistant) return; // MainSettingsActivity already owns fitsSystemWindows.
        int id = activity.getResources().getIdentifier("auth_root", "id", "com.google.android.gms");
        View root = id == 0 ? null : activity.findViewById(id);
        if (root != null) {
            applyToLegacyRoot(root, activity.getWindow().getDecorView());
            // The reserved status area uses the theme's plain background, not the blue header.
            // Android 15+ makes that bar transparent; retain legible icons in both themes.
            boolean night = (activity.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView())
                    .setAppearanceLightStatusBars(!night);
        }
    }

    /** Shared with the debug layout fixture; never adds navigation padding to the game. */
    public static void applyToLegacyRoot(View root, View decor) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop();
        final int r = root.getPaddingRight(), b = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout());
            int[] position = new int[2], origin = new int[2];
            view.getLocationInWindow(position); decor.getLocationInWindow(origin);
            int[] overlap = InsetOverlap.calculate(decor.getWidth(), decor.getHeight(),
                    position[0] - origin[0], position[1] - origin[1], view.getWidth(), view.getHeight(),
                    safe.left, safe.top, safe.right, safe.bottom);
            // adjustResize can shrink DecorView itself. Subtracting the full IME height from
            // that smaller decor would reserve the keyboard twice. Measure its remaining
            // overlap against the visible display frame in screen coordinates instead.
            if (insets.isVisible(WindowInsetsCompat.Type.ime())
                    && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > 0) {
                Rect visible = new Rect();
                view.getWindowVisibleDisplayFrame(visible);
                int[] screen = new int[2]; view.getLocationOnScreen(screen);
                if (visible.height() > 0) overlap[3] = Math.max(overlap[3],
                        InsetOverlap.keyboardBottom(screen[1], view.getHeight(), visible.bottom));
            }
            if (view.getPaddingLeft() != l + overlap[0] || view.getPaddingTop() != t + overlap[1]
                    || view.getPaddingRight() != r + overlap[2] || view.getPaddingBottom() != b + overlap[3]) {
                view.setPadding(l + overlap[0], t + overlap[1], r + overlap[2], b + overlap[3]);
            }
            return insets.inset(overlap[0], overlap[1], overlap[2], overlap[3]);
        });
        root.addOnLayoutChangeListener((v, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (left != oldLeft || top != oldTop || right != oldRight || bottom != oldBottom)
                ViewCompat.requestApplyInsets(v);
        });
        ViewCompat.requestApplyInsets(root);
    }
}
