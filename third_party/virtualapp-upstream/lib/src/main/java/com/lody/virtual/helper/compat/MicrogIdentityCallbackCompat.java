package com.lody.virtual.helper.compat;

import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.RemoteException;
import android.util.Log;

import com.lody.virtual.client.ipc.VActivityManager;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/** Keeps a disconnected game's asynchronous result from crashing microG's main thread. */
public final class MicrogIdentityCallbackCompat {
    static final String GMS = "com.google.android.gms";
    static final String GAME = "com.ProjectMoon.LimbusCompany";
    static final String COMPONENT = "org.microg.gms.auth.credentials.identity.IdentitySignInService";
    static final String BROKER = "com.google.android.gms.common.internal.IGmsServiceBroker";
    static final String CALLBACKS = "com.google.android.gms.common.internal.IGmsCallbacks";
    static final String IDENTITY = "com.google.android.gms.auth.api.identity.internal.ISignInService";
    static final String STATUS = "com.google.android.gms.common.api.internal.IStatusCallback";
    private static final AtomicInteger dropped = new AtomicInteger();

    private MicrogIdentityCallbackCompat() {}

    public static IBinder wrap(String currentPackage, ServiceInfo info, IBinder binder) {
        if (info == null || !GMS.equals(currentPackage) || !GMS.equals(info.packageName)
                || !COMPONENT.equals(info.name) || !hasDescriptor(binder, BROKER)) return binder;
        return wrapBroker(binder, () -> Binder.getCallingUid() == Process.myUid()
                && GAME.equals(VActivityManager.get().getAppProcessName(Binder.getCallingPid())));
    }

    private static boolean hasDescriptor(IBinder binder, String descriptor) {
        if (binder == null) return false;
        try { return descriptor.equals(binder.getInterfaceDescriptor()); }
        catch (RemoteException unavailable) { return false; }
    }

    static IBinder wrapBroker(IBinder delegate, BooleanSupplier gameCaller) {
        return new Relay(delegate, BROKER) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                // The pinned broker's getService: IGmsCallbacks, GetServiceRequest.
                if (code != 46 || !gameCaller.getAsBoolean()) return super.onTransact(code, data, reply, flags);
                final int clientPid = Binder.getCallingPid();
                int position = data.dataPosition();
                Parcel request = Parcel.obtain();
                try {
                    data.enforceInterface(BROKER);
                    IBinder callback = data.readStrongBinder();
                    request.writeInterfaceToken(BROKER);
                    request.writeStrongBinder(callback == null ? null : connectionCallback(callback, clientPid));
                    appendTail(request, data);
                    return delegate.transact(code, request, reply, flags);
                } finally {
                    data.setDataPosition(position);
                    request.recycle();
                }
            }
        };
    }

    private static IBinder connectionCallback(IBinder callback, int clientPid) {
        return new Relay(callback, CALLBACKS) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != 1 && code != 3) return super.onTransact(code, data, reply, flags);
                int position = data.dataPosition();
                Parcel result = Parcel.obtain();
                try {
                    data.enforceInterface(CALLBACKS);
                    int status = data.readInt();
                    IBinder service = data.readStrongBinder();
                    result.writeInterfaceToken(CALLBACKS);
                    result.writeInt(status);
                    result.writeStrongBinder(status == 0 && hasDescriptor(service, IDENTITY)
                            ? identityService(service, clientPid) : service);
                    // Bundle / ConnectionInfo stays opaque, with its Binder objects intact.
                    appendTail(result, data);
                    return deliver(callback, code, result, reply, flags);
                } finally {
                    data.setDataPosition(position);
                    result.recycle();
                }
            }
        };
    }

    private static IBinder identityService(IBinder service, int clientPid) {
        return new Relay(service, IDENTITY) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                // Only signOut(IStatusCallback, String), from the client that opened this service.
                if (code != 2 || Binder.getCallingUid() != Process.myUid()
                        || Binder.getCallingPid() != clientPid) return super.onTransact(code, data, reply, flags);
                int position = data.dataPosition();
                Parcel request = Parcel.obtain();
                try {
                    data.enforceInterface(IDENTITY);
                    IBinder callback = data.readStrongBinder();
                    request.writeInterfaceToken(IDENTITY);
                    request.writeStrongBinder(callback == null ? null : statusCallback(callback));
                    appendTail(request, data);
                    return service.transact(code, request, reply, flags);
                } finally {
                    data.setDataPosition(position);
                    request.recycle();
                }
            }
        };
    }

    static IBinder statusCallback(IBinder callback) {
        return new Relay(callback, STATUS) {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code != 1) return super.onTransact(code, data, reply, flags);
                int position = data.dataPosition();
                try { data.enforceInterface(STATUS); }
                finally { data.setDataPosition(position); }
                // Do not decode, replace or synthesize the authentication result.
                return deliver(callback, code, data, reply, flags);
            }
        };
    }

    private static boolean deliver(IBinder target, int code, Parcel data, Parcel reply, int flags)
            throws RemoteException {
        try { return target.transact(code, data, reply, flags); }
        catch (DeadObjectException disconnected) {
            // Acknowledge disposal to the sender only. No result can reach the dead recipient.
            if (reply != null && (flags & IBinder.FLAG_ONEWAY) == 0) {
                reply.setDataSize(0);
                reply.setDataPosition(0);
                reply.writeNoException();
            }
            if (dropped.getAndIncrement() < 4)
                Log.i("LimbusVA", "Google identity callback recipient disconnected; result discarded");
            return true;
        }
    }

    private static void appendTail(Parcel output, Parcel input) {
        output.appendFrom(input, input.dataPosition(), input.dataAvail());
    }

    private static class Relay extends Binder {
        final IBinder delegate;
        Relay(IBinder delegate, String descriptor) {
            this.delegate = delegate;
            // Force the generated stub through the verified AIDL parcel path, even locally.
            attachInterface(null, descriptor);
        }
        @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
            return delegate.transact(code, data, reply, flags);
        }
    }
}
