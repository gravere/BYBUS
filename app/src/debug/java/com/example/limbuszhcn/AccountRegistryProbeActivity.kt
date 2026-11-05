package com.example.limbuszhcn

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import com.example.limbuszhcn.container.MicrogContainerConfig
import com.example.limbuszhcn.storage.OperationLock
import com.lody.virtual.client.core.VirtualCore
import com.lody.virtual.client.ipc.VAccountManager
import com.lody.virtual.client.ipc.VActivityManager
import com.lody.virtual.remote.InstallOptions

/** Fresh isolated emulator only. Does not add/read accounts, launch Google UI, or contact billing. */
class AccountRegistryProbeActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val output = TextView(this).apply { text = "ACCOUNT_REGISTRY_PENDING" }
        setContentView(output)
        Thread {
            val result = runCatching {
                OperationLock.run(filesDir.toPath()) {
                    val manager = VAccountManager.get()
                    check(manager.authenticatorTypes.none { it.type == "com.google" }) { "fresh emulator required" }
                    val serverPid = VActivityManager.get().systemPid
                    val paths = MicrogContainerConfig(this).microgApks()
                    check(paths.size == 2)
                    paths.forEach { apk ->
                        val options = InstallOptions.makeOptions(false, false, InstallOptions.UpdateStrategy.FORCE_UPDATE)
                        check(VirtualCore.get().installPackageSync(apk.path, options).isSuccess) { "silent install failed" }
                    }
                    check(manager.authenticatorTypes.any { it.type == "com.google" }) { "first-install registry missing Google" }
                    check(serverPid == VActivityManager.get().systemPid) { "server restarted during probe" }
                }
                "ACCOUNT_REGISTRY_PASS"
            }.getOrElse { "ACCOUNT_REGISTRY_FAIL: ${it.javaClass.simpleName}: ${it.message}" }
            runOnUiThread { output.text = result }
        }.start()
    }
}
