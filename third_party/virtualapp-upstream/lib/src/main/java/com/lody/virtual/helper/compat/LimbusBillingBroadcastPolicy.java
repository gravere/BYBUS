package com.lody.virtual.helper.compat;

/** Scope the modern broadcast bridge to the game's own billing-client result. */
public final class LimbusBillingBroadcastPolicy {
    public static final String ACTION = "com.android.vending.billing.PURCHASES_UPDATED";
    public static final String LOCAL_ACTION = "com.android.vending.billing.LOCAL_BROADCAST_PURCHASES_UPDATED";
    private static final String GAME = "com.ProjectMoon.LimbusCompany";

    private LimbusBillingBroadcastPolicy() { }

    public static boolean isSelfResult(String caller, String targetPackage,
                                       String componentClass, String action) {
        return GAME.equals(caller) && GAME.equals(targetPackage)
                && componentClass == null && isResultAction(action);
    }

    public static boolean isResultAction(String action) {
        return ACTION.equals(action) || LOCAL_ACTION.equals(action);
    }

    /** The legacy Application context exposes the host package; normalize only a game self-result. */
    public static String resolveSelfTarget(String caller, String targetPackage, String hostPackage,
                                           String componentClass, String action) {
        if (!GAME.equals(caller) || componentClass != null || !isResultAction(action)) return null;
        if (GAME.equals(targetPackage)) return GAME;
        return hostPackage != null && !hostPackage.isEmpty() && hostPackage.equals(targetPackage)
                ? GAME : null;
    }
}
