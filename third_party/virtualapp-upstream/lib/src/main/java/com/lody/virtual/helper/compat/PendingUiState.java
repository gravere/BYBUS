package com.lody.virtual.helper.compat;

/** Only the child UIs owned by ShadowPendingActivity may survive recreation. */
public final class PendingUiState {
    public static final int GOOGLE_IDENTITY = 0x4c47;
    public static final int BILLING = 0x4c42;
    public static final int GOOGLE_CONSENT = 0x4c43;
    private int request;

    public void restore(int saved) {
        request = valid(saved) ? saved : 0;
    }

    public void begin(int requestCode) {
        if (request != 0 || !valid(requestCode)) {
            throw new IllegalStateException("Invalid pending UI transition");
        }
        request = requestCode;
    }

    public boolean finish(int requestCode) {
        if (request == 0 || request != requestCode) return false;
        request = 0;
        return true;
    }

    public int savedRequest() { return request; }
    public boolean waiting() { return request != 0; }

    private static boolean valid(int code) {
        return code == GOOGLE_IDENTITY || code == BILLING || code == GOOGLE_CONSENT;
    }
}
