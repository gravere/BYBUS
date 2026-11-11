package com.lody.virtual.helper.compat;

/** Insets not already excluded by the platform's content frame. All arguments are window pixels. */
public final class InsetOverlap {
    private InsetOverlap() { }

    public static int keyboardBottom(int screenY, int height, int visibleBottom) {
        return Math.min(Math.max(0, height), Math.max(0, screenY + height - visibleBottom));
    }

    public static int[] calculate(int width, int height, int x, int y, int contentWidth, int contentHeight,
                                  int left, int top, int right, int bottom) {
        if (width <= 0 || height <= 0 || contentWidth <= 0 || contentHeight <= 0) return new int[4];
        return new int[] {
                Math.min(contentWidth, Math.max(0, left - x)),
                Math.min(contentHeight, Math.max(0, top - y)),
                Math.min(contentWidth, Math.max(0, x + contentWidth - width + right)),
                Math.min(contentHeight, Math.max(0, y + contentHeight - height + bottom))
        };
    }
}
