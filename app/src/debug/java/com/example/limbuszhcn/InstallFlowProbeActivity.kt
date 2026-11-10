package com.example.limbuszhcn

import android.app.Activity
import android.app.ActivityManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.limbuszhcn.selfupdate.AppRelease
import com.example.limbuszhcn.selfupdate.AppUpdateInstaller
import com.example.limbuszhcn.selfupdate.AppUpdatePackage
import com.example.limbuszhcn.selfupdate.AppUpdateRepository
import com.example.limbuszhcn.selfupdate.ReleaseAsset
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Disposable emulator fixture. No network, guest launch, account access or Release entry point. */
class InstallFlowProbeActivity : Activity() {
    private lateinit var installer: AppUpdateInstaller
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installer = AppUpdateInstaller(applicationContext)
        cacheDir.mkdirs()
        if (intent.getStringExtra("mode") == "diagnostics") {
            val exits = JSONArray()
            if (Build.VERSION.SDK_INT >= 30) getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 16).forEach {
                    exits.put(JSONObject().put("reason", it.reason)
                        .put("checkpoint", SystemStartupDiagnostics.exitCheckpoint(it.processStateSummary)))
                }
            File(filesDir, "startup-probe.json").writeText(JSONObject()
                .put("sdk", Build.VERSION.SDK_INT)
                .put("starts", SystemStartupDiagnostics.collect(this))
                .put("exits", exits).put("install", installer.summary()).toString())
            finish()
            return
        }
        status = TextView(this).apply { text = "BYBUS in-app installation fixture" }
        val install = Button(this).apply {
            text = "Install verified update"
            setOnClickListener {
                isEnabled = false
                status.text = "Verifying update"
                Thread({
                    runCatching {
                        val source = File(cacheDir, "update.apk")
                        val file = File(cacheDir, "app-update/probe-update.apk")
                        file.parentFile!!.mkdirs()
                        source.copyTo(file, overwrite = true)
                        val info = requireNotNull(packageManager.getPackageArchiveInfo(file.absolutePath, 0))
                        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else {
                            @Suppress("DEPRECATION") info.versionCode.toLong()
                        }
                        val release = AppRelease("fixture", info.versionName.orEmpty(), code, 26,
                            AppUpdateRepository.hash(file), ReleaseAsset(1, "BYBUS-Android.apk", file.length()), "")
                        installer.prepare(file, release)
                        file
                    }.fold(onSuccess = { file -> runOnUiThread {
                        runCatching { startActivity(installer.intent(file)) }.onFailure {
                            installer.failed(); status.text = "Failed: ${it.javaClass.simpleName}"
                        }
                        isEnabled = true
                    } }, onFailure = { error -> runOnUiThread {
                        status.text = "Failed: ${error.javaClass.simpleName}"
                        isEnabled = true
                    } })
                }, "install-fixture").start()
            }
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(install)
        })
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing) return
        installer.onHostResumed()?.let { installed ->
            status.text = "Installer returned; installed=$installed"
            File(filesDir, "install-returned.txt").writeText(installer.summary())
        }
    }
}
