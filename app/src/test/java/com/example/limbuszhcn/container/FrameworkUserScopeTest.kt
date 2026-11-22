package com.example.limbuszhcn.container

import android.app.Notification
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import com.lody.virtual.helper.compat.FrameworkUserScope
import org.junit.Assert.*
import org.junit.Test

class FrameworkUserScopeTest {
    interface Calls {
        fun getIntentSender(type: Int, pkg: String, token: IBinder?, who: String?, request: Int,
                            intents: Array<Intent>?, types: Array<String>?, flags: Int, options: Bundle?, user: Int): String
        fun getIntentSenderWithFeature(type: Int, pkg: String, feature: String?, token: IBinder?, who: String?, request: Int,
                                       intents: Array<Intent>?, types: Array<String>?, flags: Int, options: Bundle?, user: Int): String
        fun getAppActiveNotifications(pkg: String, user: Int): String
        fun getNotificationChannel(caller: String, user: Int, pkg: String, channel: String): String
        fun getConversationNotificationChannel(caller: String, user: Int, pkg: String, channel: String, parent: Boolean, conversation: String): String
        fun getNotificationChannels(caller: String, pkg: String, user: Int): String
        fun enqueueNotificationWithTag(pkg: String, opPkg: String, tag: String, id: Int, notification: Notification?, user: Int)
        fun cancelNotificationWithTag(pkg: String, tag: String, id: Int, user: Int)
        fun cancelNotificationWithTag(pkg: String, opPkg: String, tag: String, id: Int, user: Int)
        fun cancelAllNotifications(pkg: String, user: Int)
        fun getEnabledAccessibilityServiceList(feedbackType: Int, user: Int): String
        fun getWindowToken(window: Int, user: Int): String
        fun interrupt(user: Int)
        fun getInstalledAccessibilityServiceList(user: Int): String
        fun resetAllModes(user: Int, pkg: String)
        fun whitelistAppTemporarily(pkg: String, duration: Long, user: Int)
    }
    interface VendorCalls {
        fun getIntentSenderWithFeature(type: Int, pkg: String, user: Int): String
        fun getAppActiveNotifications(pkg: String, user: Int, flags: Int): String
        fun getAppActiveNotifications(pkg: String, user: Long): String
        fun checkOperation(code: Int, uid: Int, pkg: String): Int
    }
    private fun arguments(method: java.lang.reflect.Method): Array<Any?> = method.parameterTypes.map {
        when (it) { String::class.java -> "fixture"; Int::class.javaPrimitiveType -> 0; Long::class.javaPrimitiveType -> 1234L; else -> null }
    }.toTypedArray()
    private fun userIndex(method: java.lang.reflect.Method) = when (method.name) {
        "resetAllModes" -> 0; "getNotificationChannel", "getConversationNotificationChannel" -> 1; else -> method.parameterCount - 1
    }

    @Test fun allKnownRoutesChangeOnlyTheUserAndNeverMutateVirtualArguments() {
        for (method in Calls::class.java.methods) {
            val args = arguments(method)
            val routed = FrameworkUserScope.arguments(method, args, 0, 11)
            val index = userIndex(method)
            assertEquals(method.name, 11, routed[index])
            assertEquals(method.name, 0, args[index])
            args.indices.filter { it != index }.forEach { assertEquals("${method.name} argument $it", args[it], routed[it]) }
        }
    }
    @Test fun physicalOtherAndSpecialUsersStayUnchanged() {
        for (method in Calls::class.java.methods) {
            val args = arguments(method)
            for (user in listOf(-3, -2, -1, 10, 11, 12)) {
                args[userIndex(method)] = user
                assertSame(method.name, args, FrameworkUserScope.arguments(method, args, 0, 11))
            }
            args[userIndex(method)] = 2
            assertEquals(11, FrameworkUserScope.arguments(method, args, 2, 11)[userIndex(method)])
        }
    }
    @Test fun ownerProfileDoesNotTouchArguments() {
        for (method in Calls::class.java.methods) {
            val args = arguments(method)
            assertSame(args, FrameworkUserScope.arguments(method, args, 0, 0))
            assertSame(args, FrameworkUserScope.arguments(method, args, 0, -1))
        }
    }
    @Test fun unknownVendorSignaturesAndUidsAreNeverGuessed() {
        for (method in VendorCalls::class.java.methods) {
            val args = arguments(method)
            assertSame(method.name, args, FrameworkUserScope.arguments(method, args, 0, 11))
        }
    }
    @Test fun realPermissionFailuresPropagateWithoutGrantingOrRetrying() {
        var calls = 0
        val base = java.lang.reflect.Proxy.newProxyInstance(Calls::class.java.classLoader, arrayOf(Calls::class.java)) { _, _, args ->
            calls++; assertEquals(11, args!!.last()); throw SecurityException("fixture denied")
        } as Calls
        val routed = FrameworkUserScope.wrap(base, arrayOf(Calls::class.java), { 0 }, { 11 })
        val error = assertThrows(SecurityException::class.java) { routed.getAppActiveNotifications("fixture", 0) }
        assertEquals("fixture denied", error.message)
        assertEquals(1, calls)
    }
}
