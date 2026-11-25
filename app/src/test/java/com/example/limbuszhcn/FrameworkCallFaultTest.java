package com.example.limbuszhcn;

import com.lody.virtual.helper.compat.FrameworkCallFault;
import org.junit.Test;
import static org.junit.Assert.*;

public class FrameworkCallFaultTest {
    @Test public void callerAndFramesSurviveWithoutMessagesOrPrivateNames() throws Exception {
        Throwable error = new SecurityException("secret@example.com https://login.invalid/?token=secret");
        error.setStackTrace(new StackTraceElement[]{
                new StackTraceElement("private.account.Name", "privateMethod", "private-file", 2),
                new StackTraceElement("android.os.Parcel", "readException", "private-file", 42),
                new StackTraceElement("com.lody.virtual.client.hook.base.MethodInvocationStub", "invoke", "private-file", 100)
        });
        String value = FrameworkCallFault.describe(Runnable.class.getMethod("run"), error);
        assertTrue(value.contains("method=java.lang.Runnable.run type=java.lang.SecurityException"));
        assertTrue(value.contains("frames=android.os.Parcel.readException,"));
        for (String forbidden : new String[]{"secret", "https", "token", "private", "42", "100"}) assertFalse(value.contains(forbidden));
        assertEquals("secret@example.com https://login.invalid/?token=secret", error.getMessage());
    }
}
