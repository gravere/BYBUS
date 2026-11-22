package com.lody.virtual.client.stub;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Binder;
import android.os.IBinder;
import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;
import com.lody.virtual.helper.utils.VLog;

/** The visible guest owns the engine's process importance, without a permanent started service. */
public final class GuestRuntimeService extends Service {
    private final IBinder binder = new Binder();
    private static ServiceConnection guestConnection;

    public static synchronized void bindForGame() {
        if (!"com.ProjectMoon.LimbusCompany".equals(VClient.get().getCurrentPackage())
                || guestConnection != null) return;
        final Context context = VirtualCore.get().getContext();
        ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name, IBinder service) { }
            @Override public void onServiceDisconnected(ComponentName name) { }
            @Override public void onBindingDied(ComponentName name) { release(); }
            @Override public void onNullBinding(ComponentName name) { release(); }
            private void release() {
                synchronized (GuestRuntimeService.class) {
                    if (guestConnection != this) return;
                    guestConnection = null;
                    try { context.unbindService(this); }
                    catch (RuntimeException error) {
                        // Cleanup may race the death of system_server itself (DeadSystemException).
                        // The binding is already gone; cleanup must not crash the game again.
                        VLog.w("LimbusVA", "Guest engine binding released: " + error.getClass().getSimpleName());
                    }
                }
            }
        };
        try {
            if (context.bindService(new Intent(context, GuestRuntimeService.class), connection,
                    Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT)) guestConnection = connection;
            else VLog.w("LimbusVA", "Guest engine binding was not established");
        } catch (RuntimeException error) {
            VLog.w("LimbusVA", "Guest engine binding failed: " + error.getClass().getSimpleName());
        }
    }

    @Override public IBinder onBind(Intent intent) { return binder; }
}
