package com.example.limbuszhcn

import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import com.example.limbuszhcn.container.AndroidContainerRuntimeFactory
import com.example.limbuszhcn.container.ContainerWorkspaceStorage
import com.example.limbuszhcn.gamefs.GameResourceTransfer
import com.example.limbuszhcn.migration.MigrationAccess
import com.example.limbuszhcn.migration.MigrationActivity
import com.example.limbuszhcn.migration.MigrationModel
import java.io.File

/** Synthetic emulator-only resource archive; never included in the release manifest. */
class ResourceImportProbeActivity : MigrationActivity() {
    override fun prepareTarget() = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val model = ViewModelProvider(this)[MigrationModel::class.java]
        val root = File(cacheDir, "resource-probe").apply { mkdirs() }
        val assets = File(root, "assets").apply { mkdirs() }
        File(assets, "bybus-new-resource.bin").writeText("portable fixture")
        File(assets, "bybus-existing-resource.bin").writeText("older fixture")
        val archive = File(root, "resources.zip")
        archive.outputStream().use { GameResourceTransfer(
            ContainerWorkspaceStorage(File(assets, "Resources_moved/Localize").toPath()),
            File(root, "staging").toPath(), 1L).exportResources(it) }
        val target = AndroidContainerRuntimeFactory.create(this, filesDir.toPath().resolve("container-workspace"))
            .storage().resourceMigrationRoots().getValue("assets").toFile().apply { mkdirs() }
        File(target, "bybus-existing-resource.bin").writeText("keep existing fixture")
        val handler = Handler(Looper.getMainLooper())
        val deadline = SystemClock.elapsedRealtime() + 60_000
        var started = false
        fun check() {
            try {
                check(SystemClock.elapsedRealtime() < deadline) { "Resource import timed out: ${model.state.message}" }
                if (!started && !model.state.busy) {
                    started = true
                    model.importResources(Uri.fromFile(archive), false)
                } else if (started && !model.state.busy) {
                    check(model.state.done) { model.state.message }
                    check(!MigrationAccess.preferences(this).getBoolean("pending", false))
                    check(!MigrationAccess.preferences(this).getBoolean("resource_pending", false))
                    check(File(target, "bybus-new-resource.bin").readText() == "portable fixture")
                    check(File(target, "bybus-existing-resource.bin").readText() == "keep existing fixture")
                    check(archive.isFile)
                    setContentView(TextView(this).apply { text = "RESOURCE_IMPORT_PASS" })
                    return
                }
                handler.postDelayed(::check, 200)
            } catch (error: Throwable) {
                setContentView(TextView(this).apply { text = "RESOURCE_IMPORT_FAIL ${error.message}" })
            }
        }
        handler.post(::check)
    }
}
