package com.lody.virtual.server.am;

import android.content.ComponentName;
import android.content.ContextWrapper;
import android.content.Intent;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class BackgroundServiceStartTest {
    private static class ControlledContext extends ContextWrapper {
        RuntimeException failure;
        ComponentName result;
        int calls;
        Thread caller;
        ControlledContext() { super(null); }
        @Override public ComponentName startService(Intent intent) {
            calls++;
            caller = Thread.currentThread();
            if (failure != null) throw failure;
            return result;
        }
    }

    @Test public void deniedBackgroundStartReturnsFailureAndForegroundRetryCanSucceed() {
        ControlledContext context = new ControlledContext();
        for (RuntimeException failure : new RuntimeException[]{
                new IllegalStateException("background"), new SecurityException("restricted")}) {
            context.failure = failure;
            assertFalse(ActiveServices.startProxyService(context, new Intent()));
            assertSame(Thread.currentThread(), context.caller);
        }
        context.failure = null;
        assertFalse(ActiveServices.startProxyService(context, new Intent()));
        context.result = new ComponentName("local.test", "Service");
        assertTrue(ActiveServices.startProxyService(context, new Intent()));
        assertEquals(4, context.calls);
    }

    @Test public void unrelatedFailuresAreNotReportedAsBackgroundDenial() {
        ControlledContext context = new ControlledContext();
        NullPointerException failure = new NullPointerException("programming error");
        context.failure = failure;
        try { ActiveServices.startProxyService(context, new Intent()); fail("Expected original exception"); }
        catch (NullPointerException actual) { assertSame(failure, actual); }
    }
}
