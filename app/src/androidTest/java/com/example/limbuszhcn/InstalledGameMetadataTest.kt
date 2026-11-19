package com.example.limbuszhcn

import android.app.Instrumentation
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.IInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.lody.virtual.client.VClient
import com.lody.virtual.client.core.InvocationStubManager
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.hook.delegate.AppInstrumentation
import com.lody.virtual.client.hook.proxies.pm.PackageManagerStub
import com.lody.virtual.client.interfaces.IInjector
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import mirror.android.app.ActivityThread
import mirror.android.content.pm.ApplicationInfoL
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Tests metadata in the disposable host process without starting a guest or changing installed packages. */
@RunWith(AndroidJUnit4::class)
class InstalledGameMetadataTest {
    private val game = "com.ProjectMoon.LimbusCompany"

    private fun clearProcessApplicationInfoCache() {
        val field = try {
            PackageManager::class.java.getDeclaredField("sApplicationInfoCache")
        } catch (_: NoSuchFieldException) {
            return // Older frameworks do not use this process-wide cache.
        }
        field.isAccessible = true
        val cache = field.get(null)
        cache.javaClass.getMethod("clear").invoke(cache)
    }

    private fun systemInfo(): ApplicationInfo {
        val host = InstrumentationRegistry.getInstrumentation().targetContext.applicationInfo
        return ApplicationInfo(host).apply {
            packageName = game
            className = "fixture.DeclaredApplication"
            splitNames = arrayOf("UnityDataAssetPack", "config.arm64_v8a")
            splitSourceDirs = arrayOf("/installed/game/asset.apk", "/installed/game/arm64.apk")
            splitPublicSourceDirs = splitSourceDirs?.clone()
        }
    }

    private fun apply(system: ApplicationInfo, cached: ApplicationInfo, target: ApplicationInfo, loaded: ApplicationInfo) {
        val manager = InvocationStubManager.getInstance()
        val mapField = InvocationStubManager::class.java.getDeclaredField("mInjectors").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val injectors = mapField.get(manager) as MutableMap<Class<*>, IInjector>
        val previous = injectors[PackageManagerStub::class.java]
        val pm = VirtualCore.get().unHookPackageManager
        val pmField = pm.javaClass.getDeclaredField("mPM").apply { isAccessible = true }
        val original = pmField.get(pm)
        val service = Class.forName("android.content.pm.IPackageManager")
        fun proxy(info: ApplicationInfo): IInterface = Proxy.newProxyInstance(service.classLoader, arrayOf(service)) { _, method, args ->
            if (args?.firstOrNull() == game && method.name == "getApplicationInfo") ApplicationInfo(info)
            else if (args?.firstOrNull() == game && method.name == "getPackageInfo") PackageInfo().apply {
                packageName = game
                applicationInfo = ApplicationInfo(info)
            } else try {
                method.invoke(original, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        } as IInterface
        val global = ActivityThread.sPackageManager.get()
        val instrumentationField = AppInstrumentation::class.java.getDeclaredField("gDefault").apply {
            isAccessible = true
        }
        val previousInstrumentation = instrumentationField.get(null)
        try {
            // Construct the unused guest wrapper without replacing Android's test runner.
            // VClient owns a static instance normally initialized only in a guest process.
            val constructor = AppInstrumentation::class.java.getDeclaredConstructor(Instrumentation::class.java).apply {
                isAccessible = true
            }
            instrumentationField.set(null, constructor.newInstance(InstrumentationRegistry.getInstrumentation()))
            ActivityThread.sPackageManager.set(proxy(system))
            injectors[PackageManagerStub::class.java] = PackageManagerStub()
            val visible = proxy(cached)
            ActivityThread.sPackageManager.set(visible)
            pmField.set(pm, visible)
            clearProcessApplicationInfoCache()
            @Suppress("DEPRECATION")
            assertEquals(cached.sourceDir, pm.getApplicationInfo(game, 0).sourceDir)
            val method = VClient::class.java.getDeclaredMethod("applyLimbusVisibleApplicationInfo",
                ApplicationInfo::class.java, ApplicationInfo::class.java).apply { isAccessible = true }
            method.invoke(null, target, loaded)
        } finally {
            pmField.set(pm, original)
            ActivityThread.sPackageManager.set(global)
            clearProcessApplicationInfoCache()
            instrumentationField.set(null, previousInstrumentation)
            if (previous == null) injectors.remove(PackageManagerStub::class.java)
            else injectors[PackageManagerStub::class.java] = previous
        }
    }

    @Test fun usesInstalledMetadataWhenCachedManagerRoutesToVirtualPackage() {
        val installed = systemInfo()
        val virtual = ApplicationInfo(installed).apply {
            sourceDir = "/virtual/game/stale-base.apk"
            nativeLibraryDir = "/virtual/game/stale-libs"
            splitNames = arrayOf("removed.split")
            splitSourceDirs = arrayOf("/virtual/game/stale-split.apk")
        }
        val target = ApplicationInfo(virtual)
        val loaded = ApplicationInfo(virtual)
        apply(installed, virtual, target, loaded)
        for (info in listOf(target, loaded)) {
            assertEquals(installed.sourceDir, info.sourceDir)
            assertEquals(installed.nativeLibraryDir, info.nativeLibraryDir)
            assertArrayEquals(installed.splitNames, info.splitNames)
            assertArrayEquals(installed.splitSourceDirs, info.splitSourceDirs)
            assertEquals("fixture.DeclaredApplication", info.className)
        }
    }

    @Test fun preservesSystemScanPathsAlongsideBaseApkPaths() {
        val installed = systemInfo()
        val scan = ApplicationInfoL.scanSourceDir.get(installed)
        val publicScan = ApplicationInfoL.scanPublicSourceDir.get(installed)
        assertNotNull(scan)
        assertNotEquals(installed.sourceDir, scan)
        val target = ApplicationInfo(installed)
        val loaded = ApplicationInfo(installed)
        apply(installed, installed, target, loaded)
        for (info in listOf(target, loaded)) {
            assertEquals(scan, ApplicationInfoL.scanSourceDir.get(info))
            assertEquals(publicScan, ApplicationInfoL.scanPublicSourceDir.get(info))
            assertEquals(installed.sourceDir, info.sourceDir)
        }
    }

    @Test fun clearsRemovedSplitsFromReusedApplicationMetadata() {
        val installed = systemInfo().apply {
            splitNames = null
            splitSourceDirs = null
            splitPublicSourceDirs = null
        }
        val target = systemInfo()
        val loaded = systemInfo()
        apply(installed, installed, target, loaded)
        for (info in listOf(target, loaded)) {
            assertNull(info.splitNames)
            assertNull(info.splitSourceDirs)
            assertNull(info.splitPublicSourceDirs)
        }
    }
}
