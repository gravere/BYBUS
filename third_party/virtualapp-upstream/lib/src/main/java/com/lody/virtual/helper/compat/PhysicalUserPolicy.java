package com.lody.virtual.helper.compat;

/** Virtual user IDs are container keys, not Android profile IDs used by system Binder services. */
public final class PhysicalUserPolicy {
    private PhysicalUserPolicy() { }

    public static int systemUser(String caller, int requested, int virtualUser, int physicalUser) {
        return ProviderRoutePolicy.supportedCaller(caller) && requested == virtualUser && physicalUser >= 0
                ? physicalUser : requested;
    }
}
