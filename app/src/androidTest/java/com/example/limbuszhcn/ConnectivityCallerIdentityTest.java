package com.example.limbuszhcn;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.lody.virtual.client.hook.proxies.connectivity.ConnectivityStub;
import com.lody.virtual.helper.compat.ConnectivityCallerIdentity;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.Assert.*;

/** Real Binder regression; no Google credentials or account mutation. */
@RunWith(AndroidJUnit4.class)
public class ConnectivityCallerIdentityTest {
    private static final String GOOGLE = "com.google.android.gms";

    private Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private Method method() throws Exception {
        Class<?> type = Class.forName("android.net.IConnectivityManager");
        for (Method m : type.getMethods()) {
            if (m.getName().equals("getNetworkCapabilities")) return m;
        }
        throw new AssertionError("No network capabilities method");
    }

    private Object[] args(Method method, Network network, String caller, String tag) {
        return method.getParameterCount() == 3 ? new Object[]{network, caller, tag}
                : new Object[]{network, caller};
    }

    private Object service() {
        return mirror.android.net.ConnectivityManager.mService.get(
                (ConnectivityManager) context().getSystemService(Context.CONNECTIVITY_SERVICE));
    }

    @Test public void realServiceRejectsGuestPackageAndAcceptsMappedCaller() throws Exception {
        Method m = method();
        org.junit.Assume.assumeTrue(m.getParameterCount() >= 2);
        ConnectivityManager manager = (ConnectivityManager) context().getSystemService(Context.CONNECTIVITY_SERVICE);
        Network network = manager.getActiveNetwork();
        assertNotNull("Network is required for the real service test", network);
        Object[] call = args(m, network, GOOGLE, null);
        try { m.invoke(service(), call); fail("Real UID must not own the Google package"); }
        catch (InvocationTargetException error) { assertTrue(error.getCause() instanceof SecurityException); }
        assertTrue(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(m, call, GOOGLE, context().getPackageName()));
        NetworkCapabilities actual = (NetworkCapabilities) m.invoke(service(), call);
        NetworkCapabilities expected = manager.getNetworkCapabilities(network);
        assertNotNull(actual);
        assertEquals(expected, actual);
    }

    @Test public void missingNetworkRemainsMissing() throws Exception {
        Method m = method();
        org.junit.Assume.assumeTrue(m.getParameterCount() >= 2);
        Object[] call = args(m, null, GOOGLE, null);
        assertTrue(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(m, call, GOOGLE, context().getPackageName()));
        assertNull(m.invoke(service(), call));
    }

    @Test public void foreignCallerKeepsItsRealPermissionFailure() throws Exception {
        Method m = method();
        org.junit.Assume.assumeTrue(m.getParameterCount() >= 2);
        Object[] call = args(m, null, "not.a.real.bybus.caller", null);
        assertFalse(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(m, call, GOOGLE, context().getPackageName()));
        try { m.invoke(service(), call); fail("Foreign identity must not be accepted"); }
        catch (InvocationTargetException error) { assertTrue(error.getCause() instanceof SecurityException); }
    }

    @Test public void onlyCallerIsMappedForEachSupportedGuest() throws Exception {
        Method m = method();
        org.junit.Assume.assumeTrue(m.getParameterCount() >= 2);
        Network network = ((ConnectivityManager) context().getSystemService(Context.CONNECTIVITY_SERVICE)).getActiveNetwork();
        assertNotNull(network);
        for (String guest : new String[]{GOOGLE, "com.android.vending", "com.ProjectMoon.LimbusCompany"}) {
            Object[] call = args(m, network, guest, guest);
            assertTrue(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(m, call, guest, context().getPackageName()));
            assertSame(network, call[0]);
            assertEquals(context().getPackageName(), call[1]);
            if (call.length == 3) assertEquals(guest, call[2]);
            assertFalse(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(m, call, guest, context().getPackageName()));
        }
    }

    public interface OtherService {
        NetworkCapabilities getNetworkCapabilities(Network network, String caller, String tag);
    }

    @Test public void unknownCallerContractAndMalformedArgumentsAreNotChanged() throws Exception {
        Method m = method();
        Object[] original = args(m, null, GOOGLE, null);
        Object[] call = original.clone();
        assertFalse(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(m, call, "another.guest", context().getPackageName()));
        assertArrayEquals(original, call);
        assertFalse(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(m, new Object[]{GOOGLE}, GOOGLE, context().getPackageName()));
        Method other = OtherService.class.getMethod("getNetworkCapabilities", Network.class, String.class, String.class);
        assertFalse(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(other, call, GOOGLE, context().getPackageName()));
        assertFalse(ConnectivityCallerIdentity.rewriteCapabilitiesCaller(m, call, GOOGLE, null));
        assertArrayEquals(original, call);
    }

    @Test public void productionStubRegistersTheMethod() {
        assertNotNull(new ConnectivityStub().getInvocationStub().getMethodProxy("getNetworkCapabilities"));
    }
}
