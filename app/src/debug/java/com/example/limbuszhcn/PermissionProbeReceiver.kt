package com.example.limbuszhcn

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.server.am.VActivityManagerService
import com.lody.virtual.server.pm.PackageSetting
import com.lody.virtual.server.pm.VPackageManagerService
import com.lody.virtual.server.pm.parser.VPackage
import org.json.JSONObject
import java.io.File

/** Disposable emulator only: actual production services, temporary in-memory package records. */
class PermissionProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        Thread {
            try {
                val result = runCatching { inspect(context) }.getOrElse {
                    android.util.Log.e("PermissionProbe", "Permission contract inspection failed", it)
                    JSONObject().put("passed", false).put("error", it.stackTraceToString().take(8000))
                }
                File(context.filesDir, "permission-result.json").writeText(result.toString())
            } finally {
                pending.finish()
            }
        }.start()
    }

    private fun inspect(context: Context): JSONObject {
        // Initialize the real services through their normal provider before touching the registry.
        check(VActivityManager.get().systemPid == Process.myPid())
        val manager = VPackageManagerService.get()
        val activity = VActivityManagerService.get()
        val field = VPackageManagerService::class.java.getDeclaredField("mPackages").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val packages = field.get(manager) as MutableMap<String, VPackage>
        val core = VirtualCore.get()
        // The service flag selects the companion engine package, not the CPU instruction set.
        val is64bit = core.isPluginEngine
        val granted = PackageManager.PERMISSION_GRANTED
        val denied = PackageManager.PERMISSION_DENIED
        val internet = Manifest.permission.INTERNET
        val account = Manifest.permission.ACCOUNT_MANAGER
        val install = Manifest.permission.REQUEST_INSTALL_PACKAGES
        val hostInternet = VirtualCore.getPM().checkPermission(internet, core.hostPkg)
        val hostAccount = VirtualCore.getPM().checkPermission(account, core.hostPkg)
        check(hostInternet == granted && hostAccount == denied) { "Ordinary app UID required" }
        val checks = JSONObject()
        fun expect(name: String, actual: Int, expected: Int) {
            check(actual == expected) { "$name returned $actual, expected $expected" }
            checks.put(name, true)
        }
        synchronized(packages) {
            val uid = (19001..19990).first { candidate ->
                candidate != core.myUid() && candidate + 1 != core.myUid() &&
                    packages.values.none { (it.mExtras as PackageSetting).appId in candidate..candidate + 1 }
            }
            val name = "io.github.gravere.permission.fixture"
            val siblingName = "$name.shared"
            val absent = "$name.absent"
            check(listOf(name, siblingName, absent).none { it in packages })
            fun fixture(packageName: String, vararg permissions: String) = VPackage().apply {
                this.packageName = packageName
                requestedPermissions = ArrayList(permissions.toList())
                mExtras = PackageSetting().apply {
                    this.packageName = packageName
                    appId = uid
                    setInstalled(0, true)
                }
            }
            val pkg = fixture(name, internet, account)
            packages[name] = pkg
            try {
                expect("absent_package", manager.checkPermission(is64bit, internet, absent, 0), denied)
                expect("absent_package_install", manager.checkPermission(is64bit, install, absent, 0), denied)
                expect("unknown_uid", manager.checkUidPermission(is64bit, internet, uid + 1), denied)
                expect("unknown_uid_account", activity.checkPermission(is64bit, account, -1, uid + 1), denied)
                expect("declared_network_package", manager.checkPermission(is64bit, internet, name, 0), hostInternet)
                expect("declared_network_uid", manager.checkUidPermission(is64bit, internet, uid), hostInternet)
                expect("account_package", manager.checkPermission(is64bit, account, name, 0), hostAccount)
                expect("account_activity", activity.checkPermission(is64bit, account, -1, uid), hostAccount)
                expect("unrequested_install", manager.checkPermission(is64bit, install, name, 0), denied)
                expect("host_account", activity.checkPermission(is64bit, account, Process.myPid(), core.myUid()), hostAccount)
                expect("host_network", manager.checkUidPermission(is64bit, internet, core.myUid()), hostInternet)
                expect("null_permission", manager.checkPermission(is64bit, null, name, 0), denied)
                expect("null_package", manager.checkPermission(is64bit, internet, null, 0), denied)
                expect("invalid_uid", manager.checkUidPermission(is64bit, internet, -1), denied)
                expect("absent_user", manager.checkPermission(is64bit, internet, name, 9999), denied)
                expect("cross_user", manager.checkUidPermission(is64bit, "android.permission.INTERACT_ACROSS_USERS", uid), denied)
                (pkg.mExtras as PackageSetting).setInstalled(0, false)
                expect("uninstalled_package", manager.checkPermission(is64bit, internet, name, 0), denied)
                expect("uninstalled_uid", manager.checkUidPermission(is64bit, internet, uid), denied)
                (pkg.mExtras as PackageSetting).setInstalled(0, true)
                pkg.requestedPermissions.clear()
                expect("unrequested_network", manager.checkPermission(is64bit, internet, name, 0), denied)
                packages[siblingName] = fixture(siblingName, internet)
                expect("shared_uid_package", manager.checkPermission(is64bit, internet, name, 0), hostInternet)
                expect("shared_uid", manager.checkUidPermission(is64bit, internet, uid), hostInternet)
                (packages.getValue(siblingName).mExtras as PackageSetting).setInstalled(0, false)
                expect("removed_shared_grant", manager.checkUidPermission(is64bit, internet, uid), denied)
            } finally {
                packages.remove(name)
                packages.remove(siblingName)
            }
            check(listOf(name, siblingName, absent).none { it in packages })
        }
        return JSONObject().put("passed", true).put("sdk", android.os.Build.VERSION.SDK_INT)
            .put("ordinary_app_uid", true).put("production_permission_services", true)
            .put("synthetic_package_registry", true).put("registry_restored", true)
            .put("checks", checks).put("check_count", checks.length())
            .put("authenticator_checks", AuthenticatorPermissionProbe.inspect(context))
    }
}
