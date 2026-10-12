package com.lody.virtual.helper.compat;

import android.accounts.AccountManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import com.lody.virtual.client.VClient;
import com.lody.virtual.client.core.VirtualCore;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Adapts only microG's local authenticator to the container's account-manager process. */
public final class MicrogAuthenticatorCompat {
    private MicrogAuthenticatorCompat() { }

    public static void prepare(Context context, ServiceInfo info, Intent intent, IBinder binder) {
        if (Build.VERSION.SDK_INT < 36 || binder == null || info == null || intent == null
                || !MicrogBillingIdentityPolicy.isGoogleAuthenticator(info.packageName, info.name)
                || !AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction())
                || !"com.google.android.gms".equals(VClient.get().getCurrentPackage())) return;
        try {
            if (!"android.accounts.IAccountAuthenticator".equals(binder.getInterfaceDescriptor())) return;
            Class<?> stub = Class.forName("android.accounts.IAccountAuthenticator$Stub");
            if (!stub.isInstance(binder)) return;
            Field field = stub.getDeclaredField("mEnforcer");
            field.setAccessible(true);
            final Object original = field.get(binder);
            Class<?> enforcer = Class.forName("android.os.PermissionEnforcer");
            if (original == null || !enforcer.isInstance(original)) return;
            final Method enforce = enforcer.getMethod("enforcePermission", String.class, int.class, int.class);
            final int hostUid = VirtualCore.get().myUid();
            final int serverPid = VClient.get().getSystemPid();
            if (hostUid <= 0 || serverPid <= 0) return;
            final AtomicBoolean decisionRecorded = new AtomicBoolean();
            Context scopedContext = new ContextWrapper(context) {
                @Override
                public int checkPermission(String permission, int pid, int uid) {
                    boolean allowed = pid == Binder.getCallingPid() && uid == Binder.getCallingUid()
                            && MicrogBillingIdentityPolicy.isVirtualAccountManagerCaller(
                                    permission, uid, pid, hostUid, serverPid);
                    if (decisionRecorded.compareAndSet(false, true)) {
                        LimbusBillingDiagnostics.record("event=authenticator_permission_check allowed="
                                + (allowed ? 1 : 0) + " caller_pid_present=" + (pid > 0 ? 1 : 0)
                                + " server_pid_match=" + (pid == serverPid ? 1 : 0)
                                + " host_uid_match=" + (uid == hostUid ? 1 : 0));
                    }
                    if (allowed) {
                        return PackageManager.PERMISSION_GRANTED;
                    }
                    // Unknown callers and permissions retain the original platform decision.
                    try {
                        enforce.invoke(original, permission, pid, uid);
                        return PackageManager.PERMISSION_GRANTED;
                    } catch (ReflectiveOperationException denied) {
                        return PackageManager.PERMISSION_DENIED;
                    }
                }
            };
            field.set(binder, enforcer.getConstructor(Context.class).newInstance(scopedContext));
            LimbusBillingDiagnostics.record("event=authenticator_permission_bridge_installed");
            Log.i("LimbusVA", "Prepared container Google authenticator permission boundary");
        } catch (ReflectiveOperationException | android.os.RemoteException | RuntimeException error) {
            // Leave the original enforcer in place if this platform's implementation differs.
            LimbusBillingDiagnostics.record("event=authenticator_permission_bridge_unavailable");
            Log.w("LimbusVA", "Google authenticator compatibility unavailable: "
                    + error.getClass().getSimpleName());
        }
    }
}
