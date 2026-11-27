package com.lody.virtual.helper.compat;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.DeadObjectException;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;
import static com.lody.virtual.helper.compat.MicrogIdentityCallbackCompat.*;

/** Exact pinned AIDL parcel path; no real account, credential or purchase. */
@RunWith(AndroidJUnit4.class)
public class MicrogIdentityCallbackCompatTest {
    interface Call { boolean run(int code, Parcel data, Parcel reply, int flags) throws RemoteException; }
    private IBinder binder(String descriptor, Call call) {
        return new Binder() {
            { attachInterface(null, descriptor); }
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                if (code == INTERFACE_TRANSACTION) return super.onTransact(code, data, reply, flags);
                return call.run(code, data, reply, flags);
            }
        };
    }
    private IBinder throwing(Exception failure) {
        return (IBinder) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{IBinder.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("transact")) throw failure;
                    if (method.getName().equals("getInterfaceDescriptor")) return STATUS;
                    if (method.getName().equals("isBinderAlive")) return !(failure instanceof DeadObjectException);
                    return null;
                });
    }
    private void result(IBinder target, int flags) throws RemoteException {
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(STATUS);
            data.writeInt(123); // Opaque error status; must not be changed to success.
            assertTrue(target.transact(1, data, reply, flags));
            if (flags == 0) reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }
    @Test public void liveStatusAndPayloadRemainExact() throws Exception {
        AtomicReference<IBinder> tokenSeen = new AtomicReference<>();
        IBinder token = new Binder();
        IBinder wrapped = statusCallback(binder(STATUS, (code, data, reply, flags) -> {
            data.enforceInterface(STATUS);
            assertEquals(123, data.readInt());
            tokenSeen.set(data.readStrongBinder());
            assertEquals("opaque payload", data.readString());
            assertEquals(0, data.dataAvail());
            reply.writeNoException(); reply.writeInt(27);
            return true;
        }));
        Parcel data = Parcel.obtain(), reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(STATUS); data.writeInt(123); data.writeStrongBinder(token); data.writeString("opaque payload");
            assertTrue(wrapped.transact(1, data, reply, 0));
            reply.readException(); assertEquals(27, reply.readInt()); assertSame(token, tokenSeen.get());
        } finally { data.recycle(); reply.recycle(); }
    }
    @Test public void deadRecipientDoesNotEscapeToServiceMainThread() throws Exception {
        result(statusCallback(throwing(new DeadObjectException())), 0);
        result(statusCallback(throwing(new DeadObjectException())), IBinder.FLAG_ONEWAY);
    }
    @Test public void otherRemoteErrorsStillPropagate() throws Exception {
        try { result(statusCallback(throwing(new RemoteException("fixture"))), 0); fail(); }
        catch (RemoteException expected) { assertEquals("fixture", expected.getMessage()); }
    }
    @Test public void permissionFailuresRemainFailures() throws Exception {
        try { result(statusCallback(throwing(new SecurityException("fixture permission"))), 0); fail(); }
        catch (SecurityException expected) { assertEquals("fixture permission", expected.getMessage()); }
    }
    @Test public void unrelatedCallerAndServiceRemainUnchanged() throws Exception {
        IBinder base = binder(BROKER, (code, data, reply, flags) -> { reply.writeNoException(); return true; });
        ServiceInfo info = new ServiceInfo(); info.packageName = GMS; info.name = COMPONENT;
        assertSame(base, wrap(GAME, info, base));
        info.name = "different.Service";
        assertSame(base, wrap(GMS, info, base));
        info.name = COMPONENT;
        IBinder other = new Binder(); assertSame(other, wrap(GMS, info, other));
        IBinder callback = new Binder();
        IBinder spy = binder(BROKER, (code, data, reply, flags) -> {
            data.enforceInterface(BROKER); assertSame(callback, data.readStrongBinder());
            reply.writeNoException(); return true;
        });
        Parcel data=Parcel.obtain(), reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(BROKER); data.writeStrongBinder(callback);
            wrapBroker(spy, () -> false).transact(46,data,reply,0); reply.readException();
        } finally { data.recycle(); reply.recycle(); }
    }
    @Test public void fullBrokerAndConnectionInfoPathProtectsOnlySignOut() throws Exception {
        AtomicReference<IBinder> delivered = new AtomicReference<>();
        IBinder originalCallback = binder(STATUS, (code, data, reply, flags) -> {
            throw new DeadObjectException();
        });
        IBinder infoBinder = new Binder();
        IBinder identity = binder(IDENTITY, (code, data, reply, flags) -> {
            data.enforceInterface(IDENTITY);
            IBinder callback = data.readStrongBinder();
            assertEquals("request tag",data.readString());
            assertEquals(0,data.dataAvail());
            if (code == 2) { assertNotSame(originalCallback, callback); result(callback, 0); }
            else assertSame(originalCallback, callback);
            reply.writeNoException(); return true;
        });
        IBinder connection = binder(CALLBACKS, (code,data,reply,flags) -> {
            data.enforceInterface(CALLBACKS); assertEquals(0,data.readInt());
            delivered.set(data.readStrongBinder());
            assertEquals(5678,data.readInt()); assertSame(infoBinder,data.readStrongBinder());
            assertEquals(0,data.dataAvail()); reply.writeNoException(); return true;
        });
        IBinder broker = binder(BROKER, (code,data,reply,flags) -> {
            data.enforceInterface(BROKER); IBinder callback = data.readStrongBinder();
            assertEquals(9012,data.readInt()); assertEquals(0,data.dataAvail());
            Parcel answer=Parcel.obtain(), ack=Parcel.obtain();
            try {
                answer.writeInterfaceToken(CALLBACKS); answer.writeInt(0); answer.writeStrongBinder(identity);
                answer.writeInt(5678); answer.writeStrongBinder(infoBinder);
                callback.transact(3,answer,ack,0); ack.readException();
            } finally { answer.recycle(); ack.recycle(); }
            reply.writeNoException(); return true;
        });
        Parcel data=Parcel.obtain(), reply=Parcel.obtain();
        try {
            data.writeInterfaceToken(BROKER); data.writeStrongBinder(connection); data.writeInt(9012);
            wrapBroker(broker,()->true).transact(46,data,reply,0); reply.readException();
        } finally { data.recycle(); reply.recycle(); }
        assertNotNull(delivered.get()); assertNotSame(identity,delivered.get());
        for (int code : new int[]{1,2,3,4}) {
            data=Parcel.obtain(); reply=Parcel.obtain();
            try {
                data.writeInterfaceToken(IDENTITY); data.writeStrongBinder(originalCallback); data.writeString("request tag");
                delivered.get().transact(code,data,reply,0); reply.readException();
            } finally { data.recycle(); reply.recycle(); }
        }
    }
    @Test public void realRemoteBinderDeathIsContained() throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getContext();
        AtomicReference<IBinder> remote=new AtomicReference<>(); CountDownLatch connected=new CountDownLatch(1);
        ServiceConnection connection=new ServiceConnection() {
            public void onServiceConnected(ComponentName name,IBinder service) { remote.set(service); connected.countDown(); }
            public void onServiceDisconnected(ComponentName name) {}
        };
        assertTrue(context.bindService(new Intent(context,Recipient.class),connection,Context.BIND_AUTO_CREATE));
        try {
            assertTrue(connected.await(15,TimeUnit.SECONDS));
            IBinder target=remote.get(); assertNotNull(target); result(statusCallback(target),0);
            CountDownLatch died=new CountDownLatch(1); target.linkToDeath(died::countDown,0);
            Parcel data=Parcel.obtain();
            try { target.transact(IBinder.LAST_CALL_TRANSACTION,data,null,IBinder.FLAG_ONEWAY); }
            finally { data.recycle(); }
            assertTrue(died.await(15,TimeUnit.SECONDS)); assertFalse(target.isBinderAlive());
            try { result(target,0); fail("Original remote callback must fail after process death"); }
            catch (DeadObjectException expected) {}
            result(statusCallback(target),0);
        } finally { context.unbindService(connection); }
    }
    public static class Recipient extends Service {
        public IBinder onBind(Intent ignored) {
            return new Binder() {
                { attachInterface(null, STATUS); }
                protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
                    if (code==IBinder.LAST_CALL_TRANSACTION) { android.os.Process.killProcess(android.os.Process.myPid()); return true; }
                    if (code==1) { data.enforceInterface(STATUS); if (reply!=null) reply.writeNoException(); return true; }
                    return super.onTransact(code,data,reply,flags);
                }
            };
        }
    }
}
