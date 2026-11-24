package com.example.limbuszhcn;

import android.content.pm.PackageInfo;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Process;
import android.os.RemoteException;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.lody.virtual.client.hook.base.MethodInvocationStub;
import com.lody.virtual.client.hook.base.MethodProxy;
import com.lody.virtual.client.hook.proxies.pm.PackageManagerStub;
import com.lody.virtual.helper.compat.RuntimeInterfaceAdapter;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class RuntimeInterfaceAdapterTest {
    public interface Parent {
        default String inherited() { return "default"; }
    }

    public interface Contract extends Parent {
        long flags(long value, int user);
        double wide(double left, long right, float last);
        boolean truth(boolean value);
        byte narrow(byte value);
        short narrow(short value);
        char character(char value);
        Object nullable(Object value);
        String[] array(String[] value);
        void set(int value);
        int get();
        void fail() throws RemoteException;
    }

    public static final class Delegate implements Contract {
        int value;
        Throwable failure;
        public long flags(long flags, int user) { assertEquals(19, user); return flags; }
        public double wide(double a, long b, float c) { return a + b + c; }
        public boolean truth(boolean v) { return v; }
        public byte narrow(byte v) { return v; }
        public short narrow(short v) { return v; }
        public char character(char v) { return v; }
        public Object nullable(Object v) { return v; }
        public String[] array(String[] v) { return v; }
        public void set(int v) { value = v; }
        public int get() { return value; }
        public void fail() throws RemoteException {
            if (failure instanceof RemoteException) throw (RemoteException) failure;
            if (failure instanceof RuntimeException) throw (RuntimeException) failure;
            if (failure instanceof Error) throw (Error) failure;
        }
    }

    @Test public void preservesAllParameterKindsAndInheritedMethods() throws Exception {
        Delegate delegate = new Delegate();
        Contract concrete = RuntimeInterfaceAdapter.wrap(Contract.class, delegate);
        assertFalse(Proxy.isProxyClass(concrete.getClass()));
        assertThrows(IllegalArgumentException.class, () -> Proxy.getInvocationHandler(concrete));
        assertEquals(1L << 45, concrete.flags(1L << 45, 19));
        assertEquals(8.75, concrete.wide(0.25, 8, 0.5f), 0);
        assertTrue(concrete.truth(true));
        assertEquals((byte) -123, concrete.narrow((byte) -123));
        assertEquals((short) 30001, concrete.narrow((short) 30001));
        assertEquals('\u4e2d', concrete.character('\u4e2d'));
        assertNull(concrete.nullable(null));
        Object marker = new Object();
        assertSame(marker, concrete.nullable(marker));
        String[] array = {"a", null};
        assertSame(array, concrete.array(array));
        concrete.set(7);
        assertEquals(7, delegate.value);
        assertEquals("default", concrete.inherited());
    }

    @Test public void preservesExactExceptionObjectsAndSeparateDelegates() throws Exception {
        Delegate first = new Delegate();
        Contract a = RuntimeInterfaceAdapter.wrap(Contract.class, first);
        Contract b = RuntimeInterfaceAdapter.wrap(Contract.class, new Delegate());
        assertSame(a.getClass(), b.getClass());
        a.set(11); b.set(22);
        assertEquals(11, a.get()); assertEquals(22, b.get());
        Throwable[] failures = {new RemoteException("test"), new SecurityException("test"), new LinkageError("test")};
        for (Throwable failure : failures) {
            first.failure = failure;
            try { a.fail(); fail("Expected original exception"); }
            catch (Throwable actual) { assertSame(failure, actual); }
        }
    }

    @Test public void retainsExistingVirtualDispatchInsteadOfCallingBase() throws Exception {
        MethodInvocationStub<Contract> stub = new MethodInvocationStub<>(new Delegate(), Contract.class);
        stub.addMethodProxy(new MethodProxy() {
            @Override public String getMethodName() { return "get"; }
            @Override public Object call(Object who, Method method, Object... args) { return 73; }
        });
        assertEquals(73, stub.getProxyInterface().get());
        Contract wrapped = RuntimeInterfaceAdapter.wrap(Contract.class, stub.getProxyInterface());
        assertEquals(73, wrapped.get());
    }

    @Test @SuppressWarnings({"unchecked", "rawtypes"})
    public void implementsActualDevicePackageInterfaceAndRetainsBinder() throws Exception {
        android.content.Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        IInterface base = mirror.android.app.ActivityThread.sPackageManager.get();
        Class contract = Class.forName("android.content.pm.IPackageManager");
        IInterface adapted = (IInterface) RuntimeInterfaceAdapter.wrap(contract, base);
        IBinder binder = base.asBinder();
        assertSame(binder, adapted.asBinder());
        assertFalse(Proxy.isProxyClass(adapted.getClass()));
        for (Method method : contract.getMethods()) {
            if (java.lang.reflect.Modifier.isStatic(method.getModifiers())) continue;
            assertEquals(method.getReturnType(), adapted.getClass()
                    .getMethod(method.getName(), method.getParameterTypes()).getReturnType());
            if (method.getName().equals("getPackageInfo") && method.getParameterTypes().length == 3) {
                Object flags = method.getParameterTypes()[1] == long.class ? (Object) 0L : 0;
                PackageInfo info = (PackageInfo) method.invoke(adapted, context.getPackageName(), flags, Process.myUid() / 100000);
                assertEquals(context.getPackageName(), info.packageName);
                assertEquals(Process.myUid(), info.applicationInfo.uid);
            }
        }
    }

    @Test public void doesNotEnableForGoogleOrHost() {
        IInterface before = mirror.android.app.ActivityThread.sPackageManager.get();
        PackageManagerStub.prepareGameInterface("com.google.android.gms");
        PackageManagerStub.prepareGameInterface("io.github.gravere.bybus");
        assertSame(before, mirror.android.app.ActivityThread.sPackageManager.get());
    }
}
