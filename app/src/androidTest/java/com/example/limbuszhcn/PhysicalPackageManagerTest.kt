package com.example.limbuszhcn

import android.content.pm.PackageInfo
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.hook.utils.MethodParameterUtils
import com.lody.virtual.helper.compat.PackageManagerUserScope
import mirror.android.app.ActivityThread
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.InvocationTargetException

/** Run under a secondary emulator user, as an ordinary application UID. */
@RunWith(AndroidJUnit4::class)
class PhysicalPackageManagerTest {
    @Test fun hostMetadataUsesTheRealProfileWithoutCrossUserPermission() {
        val user = Process.myUid() / 100000
        assumeTrue("Requires a secondary Android user", user > 0)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val base = ActivityThread.sPackageManager.get()
        val interfaces = MethodParameterUtils.getAllInterface(base.javaClass)
        val method = Class.forName("android.content.pm.IPackageManager").methods.single {
            it.name == "getPackageInfo" && it.parameterTypes.size == 3
        }
        val flags: Any = if (method.parameterTypes[1] == java.lang.Long.TYPE) 0L else 0
        val legacy = assertThrows(InvocationTargetException::class.java) {
            method.invoke(base, context.packageName, flags, 0)
        }
        assertTrue(legacy.cause is SecurityException)
        val routed = PackageManagerUserScope.wrap(base, interfaces, { 0 }, { user })
        val info = method.invoke(routed, context.packageName, flags, 0) as PackageInfo
        assertEquals(context.packageName, info.packageName)
        assertEquals(Process.myUid(), info.applicationInfo!!.uid)
    }
}
