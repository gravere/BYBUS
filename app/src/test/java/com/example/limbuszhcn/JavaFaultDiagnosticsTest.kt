package com.example.limbuszhcn

import org.junit.Assert.*
import org.junit.Test

class JavaFaultDiagnosticsTest {
    private val prefix = "1795524380.763 2523 2523 E chromium: "

    @Test fun chromiumExceptionKeepsTypeAndCauseWithoutLoginPayload() {
        assertEquals("time=1795524380.763 pid=2523 event=java_exception type=java.lang.SecurityException category=package_uid_mismatch",
            safeJavaFaultMarker(prefix + "[ERROR:jni_android.cc(306)] java.lang.SecurityException: private@example.com does not belong to uid 123; token=private-value"))
        assertEquals("time=1795524380.763 pid=2523 event=java_exception type=android.os.DeadObjectException category=unspecified",
            safeJavaFaultMarker(prefix + "Caused by: android.os.DeadObjectException"))
    }

    @Test fun stackContainsMethodOnlyAndSupportsInnerClasses() {
        assertEquals("time=1795524380.763 pid=2523 event=java_frame method=android.app.IActivityManager\$Stub\$Proxy.bindServiceInstance",
            safeJavaFaultMarker(prefix + "  at android.app.IActivityManager\$Stub\$Proxy.bindServiceInstance(IActivityManager.java:5219)"))
        assertNotNull(safeJavaFaultMarker(prefix + "at org.chromium.base.JniAndroid.handleException(Native Method)"))
        assertNotNull(safeJavaFaultMarker(prefix.replace("E chromium", "W System.err") + "java.lang.IllegalStateException: payload omitted"))
    }

    @Test fun appOpsRejectionWithoutTheWordUidKeepsOnlyItsCategory() {
        val marker = safeJavaFaultMarker(prefix + "java.lang.SecurityException: Package private.example.account does not belong to 10174; token=secret-value")
        assertEquals("time=1795524380.763 pid=2523 event=java_exception type=java.lang.SecurityException category=package_uid_mismatch", marker)
        assertFalse(marker!!.contains("private"))
        assertFalse(marker.contains("10174"))
        assertFalse(marker.contains("secret"))
    }

    @Test fun nativeFatalMarkerIsAcceptedButArbitraryBrowserTextIsNot() {
        assertEquals("time=1795524380.763 pid=2523 event=chromium_jni_fatal",
            safeJavaFaultMarker(prefix + "[FATAL:jni_android.cc(306)] Please include Java exception stack in crash report"))
        listOf("https://accounts.example/login?token=fixture", "Authorization: Bearer fixture", "Purchase receipt=fixture",
            "java.lang.SecurityException: hello\naccount=fixture", "at android.fake.method(https://secret.example)").forEach {
            assertNull(safeJavaFaultMarker(prefix + it))
        }
        assertNull(safeJavaFaultMarker(prefix.replace("chromium", "Billing") + "java.lang.SecurityException"))
        assertNull(safeJavaFaultMarker(prefix.replace("2523", "9999999999") + "java.lang.SecurityException"))
    }

    @Test fun capturesActualContainerAndChromiumNotificationFailureFrames() {
        assertEquals("time=1795524380.763 pid=2523 event=java_frame method=com.lody.virtual.client.hook.proxies.notification.MethodProxies\$CancelNotificationWithTag.call",
            safeJavaFaultMarker(prefix + "at com.lody.virtual.client.hook.proxies.notification.MethodProxies\$CancelNotificationWithTag.call(MethodProxies.java:363)"))
        assertEquals("time=1795524380.763 pid=2523 event=java_frame method=WV.uq.run",
            safeJavaFaultMarker(prefix + "at WV.uq.run(chromium-TrichromeWebViewGoogle6432.apk-stable-636771938:32)"))
        assertNotNull(safeJavaFaultMarker(prefix + "at com.example.limbuszhcn.MainActivity.onCreate(MainActivity.kt:44)"))
        assertNull(safeJavaFaultMarker(prefix + "at com.lody.virtual.Helper.call(https://private.example/?token=fixture)"))
    }

    @Test fun tailIsByteBoundedAndKeepsMostRecentCompleteLines() {
        assertEquals(listOf("[truncated to recent data]", "丁戊己", "end"),
            boundedDiagnosticTail(sequenceOf("甲乙丙", "丁戊己", "end"), 16, 50))
        assertEquals(listOf("a", "b"), boundedDiagnosticTail(sequenceOf("a", "b", "c"), 20, 2))
        assertEquals(listOf("[oversized diagnostic line omitted]", "end"),
            boundedDiagnosticTail(sequenceOf("x".repeat(20_000), "end"), 512, 5))
    }
}
