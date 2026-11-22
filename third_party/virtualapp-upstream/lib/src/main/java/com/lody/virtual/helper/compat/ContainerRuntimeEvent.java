package com.lody.virtual.helper.compat;

import com.lody.virtual.client.core.VirtualCore;

/** Bounded diagnostic stages, never strings from OAuth payloads or game intents. */
public enum ContainerRuntimeEvent {
    PROCESS_REQUESTED, PROCESS_EMPTY_REPLY, PROCESS_INVALID_CLIENT, PROCESS_ATTACHED,
    NEW_INTENT_DELIVERED, NEW_INTENT_FAILED,
    APPLE_WINDOW_OPENED, APPLE_CALLBACK_REJECTED, APPLE_RETURN, APPLE_ERROR_RETURN;

    public void record(int value) {
        try { VirtualCore.getConfig().onContainerRuntimeEvent(this, value); }
        catch (RuntimeException ignored) { /* Observation cannot affect dispatch. */ }
    }
}
