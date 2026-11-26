package com.example.limbuszhcn

import android.app.PendingIntent
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.Icon
import android.net.Uri
import android.util.AtomicFile
import java.io.ByteArrayOutputStream
import java.io.File

internal enum class DesktopIconStyle(val label: String, val alias: String, val drawable: Int) {
    Default("深绿", "LauncherDefault", R.mipmap.ic_launcher),
    Light("浅色", "LauncherLight", R.mipmap.ic_launcher_light),
    Dark("深色", "LauncherDark", R.mipmap.ic_launcher_dark),
    Wordmark("横排文字", "LauncherWordmark", R.mipmap.ic_launcher_wordmark),
    StackedWordmark("双行文字", "LauncherStackedWordmark", R.mipmap.ic_launcher_stacked_wordmark),
    Locomotive("火车头", "LauncherLocomotive", R.mipmap.ic_launcher_locomotive),
    FullLogo("组合标志", "LauncherFullLogo", R.mipmap.ic_launcher_full_logo);
    fun component(context: Context) = ComponentName(context.packageName, "com.example.limbuszhcn.$alias")
}

/** Built-in aliases change the application entry; personal images use a separately identified pinned shortcut. */
internal class DesktopIcons(private val context: Context) {
    private val pm = context.packageManager
    private val shortcuts get() = context.getSystemService(ShortcutManager::class.java)
    private val directory get() = File(context.filesDir, "desktop-icon").apply { mkdirs() }
    private val draft get() = File(directory, "custom.png")
    private val pinned get() = File(directory, "pinned.png")
    val preferences get() = context.getSharedPreferences("desktop-icon", Context.MODE_PRIVATE)
    val pinSupported get() = shortcuts?.isRequestPinShortcutSupported == true

    fun selected(): DesktopIconStyle = DesktopIconStyle.entries.firstOrNull(::enabled) ?: DesktopIconStyle.Default
    private fun enabled(style: DesktopIconStyle): Boolean = when (pm.getComponentEnabledSetting(style.component(context))) {
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> style == DesktopIconStyle.Default
        else -> false
    }

    fun select(style: DesktopIconStyle) = synchronized(LOCK) {
        val previous = selected()
        val states = DesktopIconStyle.entries.associateWith { pm.getComponentEnabledSetting(it.component(context)) }
        try {
            // Enable first. MainActivity itself is never disabled, so old explicit intents keep working.
            if (shortcuts?.pinnedShortcuts?.any { it.id == CUSTOM_ID } == true) {
                set(style, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                reattachCustom(style)
            }
            applyStates(style)
            preferences.edit().putString("style", style.name).apply()
        } catch (error: Exception) {
            runCatching { set(previous, PackageManager.COMPONENT_ENABLED_STATE_ENABLED); reattachCustom(previous) }
            states.forEach { (item, state) -> runCatching { set(item, state) } }
            if (DesktopIconStyle.entries.none(::enabled)) runCatching { set(DesktopIconStyle.Default, PackageManager.COMPONENT_ENABLED_STATE_ENABLED) }
            throw IllegalStateException("桌面图标未能切换，原启动入口已保留", error)
        }
    }

    private fun set(style: DesktopIconStyle, value: Int) =
        pm.setComponentEnabledSetting(style.component(context), value, PackageManager.DONT_KILL_APP)

    private fun applyStates(style: DesktopIconStyle) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            pm.setComponentEnabledSettings(DesktopIconStyle.entries.map {
                PackageManager.ComponentEnabledSetting(it.component(context),
                    if (it == style) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP)
            })
        } else {
            set(style, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
            DesktopIconStyle.entries.filter { it != style }.forEach { set(it, PackageManager.COMPONENT_ENABLED_STATE_DISABLED) }
        }
    }

    fun repairEntry() {
        val active = DesktopIconStyle.entries.filter(::enabled)
        if (active.isEmpty()) set(DesktopIconStyle.Default, PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
        else if (active.size > 1) {
            val selected = active.firstOrNull { it.name == preferences.getString("style", null) } ?: active.first()
            reattachCustom(selected)
            applyStates(selected)
        }
    }

    fun readCustom(): Bitmap? = runCatching {
        AtomicFile(draft).openRead().use { input ->
            if (input.channel.size() > 2 * 1024 * 1024) null else BitmapFactory.decodeStream(input)
        }
    }.getOrNull()

    fun importImage(uri: Uri): Bitmap {
        val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                require(output.size() + n <= 16 * 1024 * 1024) { "请选择不超过 16 MB 的图片" }
                output.write(buffer, 0, n)
            }
            output.toByteArray()
        } ?: error("无法读取所选图片")
        val png = com.example.limbuszhcn.translation.TranslationIcon.normalize(bytes, 384)
        atomicWrite(draft, png)
        return requireNotNull(readCustom()) { "图片读取失败" }
    }

    private fun shortcut(bitmap: Bitmap, style: DesktopIconStyle) = ShortcutInfo.Builder(context, CUSTOM_ID)
        .setShortLabel("BYBUS")
        .setLongLabel("BYBUS")
        .setActivity(style.component(context))
        .setIcon(Icon.createWithAdaptiveBitmap(bitmap))
        .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        .build()

    fun requestCustom(): String {
        check(pinSupported) { "当前桌面不支持添加自定义图标，可以使用上面的内置样式" }
        val image = requireNotNull(readCustom()) { "请先选择图片" }
        val manager = requireNotNull(shortcuts)
        val entry = shortcut(image, selected())
        val alreadyPinned = manager.pinnedShortcuts.any { it.id == CUSTOM_ID }
        if (alreadyPinned) check(manager.updateShortcuts(listOf(entry))) { "桌面暂时无法更新图标，请稍后重试" }
        atomicWrite(pinned, draft.readBytes())
        val callback = PendingIntent.getBroadcast(context, 742,
            Intent(context, DesktopIconReceiver::class.java).setAction(PINNED_ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        check(manager.requestPinShortcut(entry, callback.intentSender)) { "桌面未接受添加请求，请检查桌面设置" }
        return if (alreadyPinned) "已更新自定义图标；若桌面弹出确认，请允许添加。" else "请在桌面的提示中确认添加。"
    }

    private fun reattachCustom(style: DesktopIconStyle) {
        val manager = shortcuts ?: return
        if (manager.pinnedShortcuts.none { it.id == CUSTOM_ID }) return
        val bitmap = AtomicFile(pinned).openRead().use { BitmapFactory.decodeStream(it) } ?: error("自定义图标无法读取")
        check(manager.updateShortcuts(listOf(shortcut(bitmap, style)))) { "自定义桌面入口暂时无法更新" }
        manager.enableShortcuts(listOf(CUSTOM_ID))
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(bytes); atomic.finishWrite(output) }
        catch (error: Throwable) { atomic.failWrite(output); throw error }
    }

    companion object {
        private val LOCK = Any()
        const val CUSTOM_ID = "bybus_custom_desktop"
        const val PINNED_ACTION = "io.github.gravere.bybus.DESKTOP_ICON_PINNED"
    }
}

class DesktopIconReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val icons = DesktopIcons(context)
        when (intent.action) {
            Intent.ACTION_MY_PACKAGE_REPLACED -> runCatching { icons.repairEntry() }
            DesktopIcons.PINNED_ACTION -> icons.preferences.edit().putLong("pinned_at", System.currentTimeMillis()).apply()
        }
    }
}
