package com.lody.virtual.helper.compat;

import android.app.IBinderSession;
import android.content.ComponentName;
import android.os.IBinder;
import com.lody.virtual.client.hook.secondary.ServiceConnectionDelegate;
import org.junit.Test;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import static org.junit.Assert.*;

public class ServiceConnectionCompatTest {
    public interface Legacy { void connected(ComponentName name, IBinder service); }
    public interface Oreo { void connected(ComponentName name, IBinder service, boolean dead); }
    public interface Session { void connected(ComponentName name, IBinder service, IBinderSession session, boolean dead); }
    public interface Unknown { void connected(ComponentName name, IBinder service, Object session, boolean dead); }
    public static class Receiver implements Legacy, Oreo, Session {
        Object[] received;
        RuntimeException failure;
        private void capture(Object... values) { if (failure != null) throw failure; received = values; }
        public void connected(ComponentName n, IBinder b) { capture(n, b); }
        public void connected(ComponentName n, IBinder b, boolean d) { capture(n, b, d); }
        public void connected(ComponentName n, IBinder b, IBinderSession s, boolean d) { capture(n, b, s, d); }
    }
    private static <T> T token(Class<T> type) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class[]{type},
                (proxy, method, args) -> null));
    }

    @Test public void receiverImplementsTheExactNewFrameworkSignature() throws Exception {
        Method callback = ServiceConnectionDelegate.class.getDeclaredMethod("connected",
                ComponentName.class, IBinder.class, IBinderSession.class, boolean.class);
        assertEquals(void.class, callback.getReturnType());
        assertFalse(java.lang.reflect.Modifier.isAbstract(callback.getModifiers()));
    }
    @Test public void prefersSessionFormWhenMultipleFormsExist() {
        assertEquals(4, ServiceConnectionCompat.resolve(Receiver.class).getParameterCount());
    }
    @Test public void preservesSessionBinderAndDeadFlag() throws Exception {
        Receiver receiver = new Receiver();
        IBinder binder = token(IBinder.class);
        IBinderSession session = token(IBinderSession.class);
        ServiceConnectionCompat.forward(ServiceConnectionCompat.resolve(Session.class), receiver, null, binder, session, true);
        assertSame(binder, receiver.received[1]);
        assertSame(session, receiver.received[2]);
        assertEquals(true, receiver.received[3]);
    }
    @Test public void nullBindingAndNullSessionAreDelivered() throws Exception {
        Receiver receiver = new Receiver();
        ServiceConnectionCompat.forward(ServiceConnectionCompat.resolve(Session.class), receiver, null, null, null, false);
        assertArrayEquals(new Object[]{null, null, null, false}, receiver.received);
    }
    @Test public void olderCallbackReceivesDeadFlagInItsOwnPosition() throws Exception {
        Receiver receiver = new Receiver();
        IBinder binder = token(IBinder.class);
        ServiceConnectionCompat.forward(ServiceConnectionCompat.resolve(Oreo.class), receiver, null, binder, null, true);
        assertArrayEquals(new Object[]{null, binder, true}, receiver.received);
    }
    @Test public void legacyCallbackRemainsSupported() throws Exception {
        Receiver receiver = new Receiver();
        IBinder binder = token(IBinder.class);
        ServiceConnectionCompat.forward(ServiceConnectionCompat.resolve(Legacy.class), receiver, null, binder, null, false);
        assertArrayEquals(new Object[]{null, binder}, receiver.received);
    }
    @Test public void unknownSessionTypeIsNotTreatedAsTheFrameworkSignature() {
        assertThrows(IllegalStateException.class, () -> ServiceConnectionCompat.resolve(Unknown.class));
    }
    @Test public void callbackFailuresAreNotSwallowedOrChanged() {
        Receiver receiver = new Receiver();
        receiver.failure = new IllegalArgumentException("callback failure");
        RuntimeException caught = assertThrows(IllegalArgumentException.class, () ->
                ServiceConnectionCompat.forward(ServiceConnectionCompat.resolve(Session.class), receiver, null, null, null, false));
        assertSame(receiver.failure, caught);
    }
}
