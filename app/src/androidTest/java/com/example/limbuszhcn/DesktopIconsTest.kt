package com.example.limbuszhcn

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DesktopIconsTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun emulatorOnly() { check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish")) }

    @Test fun everyPresetKeepsOneLauncherAndLeavesMainActivityUsable() {
        emulatorOnly()
        val icons = DesktopIcons(context)
        val before = icons.selected()
        try {
            for (style in DesktopIconStyle.entries) {
                icons.select(style)
                assertEquals(style, icons.selected())
                val launch = requireNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
                assertEquals(style.component(context), launch.component)
                assertNotEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    context.packageManager.getComponentEnabledSetting(ComponentName(context, MainActivity::class.java)))
                val entries = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_LAUNCHER).setPackage(context.packageName), 0)
                assertEquals(1, entries.size)
                assertNotNull(context.packageManager.resolveActivity(Intent(context, MainActivity::class.java)
                    .setAction(MANAGE_BYBUS_ACTION), 0))
            }
            DesktopIconReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            assertEquals(DesktopIconStyle.entries.last(), icons.selected())
        } finally { icons.select(before) }
    }

    @Test fun packageUpgradeRecoveryRestoresAnAccidentallyMissingEntry() {
        emulatorOnly()
        val icons = DesktopIcons(context)
        val before = icons.selected()
        try {
            DesktopIconStyle.entries.forEach { context.packageManager.setComponentEnabledSetting(it.component(context),
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
            DesktopIconReceiver().onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))
            assertNotNull(context.packageManager.getLaunchIntentForPackage(context.packageName))
        } finally { icons.select(before) }
    }

    @Test fun personalImageIsBoundedAndKeepsTransparency() {
        emulatorOnly()
        val source = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        source.setPixel(150, 100, Color.RED)
        val file = File(context.cacheDir, "desktop-icon-test.png")
        file.outputStream().use { source.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val result = DesktopIcons(context).importImage(Uri.fromFile(file))
        assertEquals(384, result.width)
        assertEquals(384, result.height)
        assertEquals(0, Color.alpha(result.getPixel(0, 0)))
        file.writeText("not an image")
        assertThrows(IllegalArgumentException::class.java) { DesktopIcons(context).importImage(Uri.fromFile(file)) }
        assertNotNull(DesktopIcons(context).readCustom())
    }
}
