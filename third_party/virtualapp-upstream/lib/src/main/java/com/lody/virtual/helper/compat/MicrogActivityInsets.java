package com.lody.virtual.helper.compat;

import android.app.Activity;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.lody.virtual.client.VClient;

/** The pinned microG AssistantActivity layout predates enforced edge-to-edge windows. */
public final class MicrogActivityInsets {
    private MicrogActivityInsets() { }

    public static void apply(Activity activity) {
        if (!"com.google.android.gms".equals(VClient.get().getCurrentPackage())) return;
        boolean assistant = false;
        for (Class<?> type = activity.getClass(); type != null; type = type.getSuperclass()) {
            if ("org.microg.gms.auth.login.AssistantActivity".equals(type.getName())) assistant = true;
        }
        if (!assistant) return; // Modern settings/Compose pages own their inset handling.
        int id = activity.getResources().getIdentifier("auth_root", "id", "com.google.android.gms");
        View root = id == 0 ? null : activity.findViewById(id);
        if (root != null) applyToLegacyRoot(root, activity.getWindow().getDecorView());
    }

    /** Shared with the debug layout fixture; never adds navigation padding to the game. */
    public static void applyToLegacyRoot(View root, View decor) {
        final int l = root.getPaddingLeft(), t = root.getPaddingTop();
        final int r = root.getPaddingRight(), b = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safe = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout() | WindowInsetsCompat.Type.ime());
            int[] position = new int[2], origin = new int[2];
            view.getLocationInWindow(position); decor.getLocationInWindow(origin);
            int[] overlap = InsetOverlap.calculate(decor.getWidth(), decor.getHeight(),
                    position[0] - origin[0], position[1] - origin[1], view.getWidth(), view.getHeight(),
                    safe.left, safe.top, safe.right, safe.bottom);
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
