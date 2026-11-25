package com.lody.virtual.helper.compat;

import com.lody.virtual.client.core.VirtualCore;

/** Bounded diagnostic stages, never strings from OAuth payloads or game intents. */
public enum ContainerRuntimeEvent {
    PROCESS_REQUESTED, PROCESS_EMPTY_REPLY, PROCESS_INVALID_CLIENT, PROCESS_ATTACHED,
    GAME_STOP_REQUESTED, GAME_STOP_ALL, GAME_FINISH_PACKAGE, GAME_STOP_PACKAGE, GAME_STOP_DIRECT, GAME_STOP_CAPACITY,
    NEW_INTENT_DELIVERED, NEW_INTENT_FAILED,
    PACKAGE_REGISTRY_READ_FAILED, GAME_REGISTRATION_DEFERRED, GAME_PACKAGE_CACHE_REBUILT,
    GAME_DATA_CLEAR_REQUESTED, GAME_UNINSTALL_REQUESTED,
    APPLE_WINDOW_OPENED, APPLE_CALLBACK_REJECTED, APPLE_RETURN, APPLE_ERROR_RETURN;

    public void record(int value) {
        try { VirtualCore.getConfig().onContainerRuntimeEvent(this, value); }
        catch (RuntimeException ignored) { /* Observation cannot affect dispatch. */ }
    }
}
