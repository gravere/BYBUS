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
        if (!MicrogBillingIdentityPolicy.requiresAuthenticatorPermissionBridge(Build.VERSION.SDK_INT)
                || binder == null || info == null || intent == null
                || !MicrogBillingIdentityPolicy.isGoogleAuthenticator(info.packageName, info.name)
                || !AccountManager.ACTION_AUTHENTICATOR_INTENT.equals(intent.getAction())
                || !"com.google.android.gms".equals(VClient.get().getCurrentPackage())) return;
        try {
            if (!prepareBinder(context, binder, VirtualCore.get().myUid(), VClient.get().getSystemPid())) return;
            LimbusBillingDiagnostics.record("event=authenticator_permission_bridge_installed");
            Log.i("LimbusVA", "Prepared container Google authenticator permission boundary");
        } catch (ReflectiveOperationException | android.os.RemoteException | RuntimeException error) {
            LimbusBillingDiagnostics.record("event=authenticator_permission_bridge_unavailable");
            Log.w("LimbusVA", "Google authenticator compatibility unavailable: "
                    + error.getClass().getSimpleName());
        }
    }

    /** Called only after the exact service, action and virtual owner checks above. */
    private static boolean prepareBinder(Context context, IBinder binder, int hostUid, int serverPid)
            throws ReflectiveOperationException, android.os.RemoteException {
        if (hostUid <= 0 || serverPid <= 0 || binder == null
                || !"android.accounts.IAccountAuthenticator".equals(binder.getInterfaceDescriptor())) return false;
        Class<?> stub = Class.forName("android.accounts.IAccountAuthenticator$Stub");
        if (!stub.isInstance(binder)) return false;
        if (Build.VERSION.SDK_INT >= 34) {
            Field field = stub.getDeclaredField("mEnforcer");
            field.setAccessible(true);
            final Object original = field.get(binder);
            Class<?> enforcer = Class.forName("android.os.PermissionEnforcer");
            if (original == null || !enforcer.isInstance(original)) return false;
            final Method enforce = enforcer.getMethod("enforcePermission", String.class, int.class, int.class);
            Context scoped = scopedContext(context, hostUid, serverPid, (permission, pid, uid) -> {
                try {
                    enforce.invoke(original, permission, pid, uid);
                    return PackageManager.PERMISSION_GRANTED;
                } catch (ReflectiveOperationException denied) {
                    return PackageManager.PERMISSION_DENIED;
                }
            });
            field.set(binder, enforcer.getConstructor(Context.class).newInstance(scoped));
        } else {
            // Before Android 14 the framework Transport checks its enclosing authenticator's
            // Context instead of a generated PermissionEnforcer. Do not replace the Service context.
            Class<?> transport = Class.forName("android.accounts.AbstractAccountAuthenticator$Transport");
            if (binder.getClass() != transport) return false;
            Field ownerField = transport.getDeclaredField("this$0");
            ownerField.setAccessible(true);
            Object owner = ownerField.get(binder);
            Class<?> authenticator = android.accounts.AbstractAccountAuthenticator.class;
            if (!authenticator.isInstance(owner)) return false;
            Field contextField = authenticator.getDeclaredField("mContext");
            contextField.setAccessible(true);
            Context original = (Context) contextField.get(owner);
            if (original == null) return false;
            contextField.set(owner, scopedContext(original, hostUid, serverPid, original::checkPermission));
        }
        return true;
    }

    private interface PermissionDecision {
        int check(String permission, int pid, int uid);
    }

    private static Context scopedContext(Context base, int hostUid, int serverPid, PermissionDecision original) {
        final AtomicBoolean decisionRecorded = new AtomicBoolean();
        return new ContextWrapper(base) {
            @Override
            public int checkCallingOrSelfPermission(String permission) {
                return checkPermission(permission, Binder.getCallingPid(), Binder.getCallingUid());
            }

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
                return allowed ? PackageManager.PERMISSION_GRANTED : original.check(permission, pid, uid);
            }
        };
    }
}
