package com.lody.virtual.helper.compat;

import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

import com.lody.virtual.client.core.VirtualCore;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keeps real caller PID available without blocking the account service's main thread. */
public final class MicrogAuthenticatorClient {
    private static final ThreadPoolExecutor CALLS = new ThreadPoolExecutor(
            0, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "bybus-google-auth");
                thread.setDaemon(true);
                return thread;
            });

    private MicrogAuthenticatorClient() { }

    public static boolean isRequired(ServiceInfo info) {
        return Build.VERSION.SDK_INT >= 36 && VirtualCore.get().isServerProcess()
                && info != null
                && MicrogBillingIdentityPolicy.isGoogleAuthenticator(info.packageName, info.name);
    }

    public static IBinder wrap(IBinder remote) throws RemoteException {
        if (remote == null || !"android.accounts.IAccountAuthenticator".equals(remote.getInterfaceDescriptor())) {
            throw new RemoteException("Unexpected Google authenticator interface");
        }
        return (IBinder) Proxy.newProxyInstance(MicrogAuthenticatorClient.class.getClassLoader(),
                new Class<?>[]{IBinder.class}, (proxy, method, args) -> {
                    if ("queryLocalInterface".equals(method.getName())) return null;
                    if ("transact".equals(method.getName()) && args != null && args.length == 4) {
                        int code = (Integer) args[0];
                        int flags = (Integer) args[3];
                        if (code >= IBinder.FIRST_CALL_TRANSACTION && code <= IBinder.LAST_CALL_TRANSACTION
                                && (flags & IBinder.FLAG_ONEWAY) != 0) {
                            // Oneway omits the kernel PID. Only this container authenticator uses
                            // synchronous transport; the original account-result Binder is unchanged.
                            Parcel reply = Parcel.obtain();
                            try {
                                boolean handled = remote.transact(code, (Parcel) args[1], reply,
                                        flags & ~IBinder.FLAG_ONEWAY);
                                if (reply.dataSize() > 0) reply.readException();
                                if (!handled) throw new RemoteException("Unsupported authenticator request");
                                return true;
                            } finally {
                                reply.recycle();
                            }
                        }
                    }
                    try {
                        return method.invoke(remote, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
    }

    public static boolean execute(Runnable call) {
        try {
            CALLS.execute(call);
            return true;
        } catch (RejectedExecutionException full) {
            return false;
        }
    }
}
