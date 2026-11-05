package com.example.limbuszhcn

import android.os.Bundle
import android.widget.TextView
import com.example.limbuszhcn.migration.MigrationAccess
import com.example.limbuszhcn.migration.MigrationActivity
import java.io.File
import java.security.MessageDigest

/** Synthetic emulator data only. Never present in the release manifest. */
class MigrationProbeActivity : MigrationActivity() {
    override fun prepareTarget() = false // No official game/account is installed in the fixture.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!intent.getBooleanExtra("verify_fixture", false)) return
        val result = runCatching {
            check(MigrationAccess.preferences(this).getBoolean("complete", false))
            check(!MigrationAccess.preferences(this).getBoolean("pending", false))
            val root = File(applicationInfo.dataDir)
            val relative = "virtual/data/user/0/com.ProjectMoon.LimbusCompany/files/bybus-migration-fixture.bin"
            val digest = MessageDigest.getInstance("SHA-256").digest(File(root, relative).readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            check(!File(root, "virtual/data/app/system/bybus-migration-fixture.secret").exists())
            check(!File(root, "files/translation-cache/redirect-gate.pid").exists())
            check(File(root, "virtual/storage/emulated/0/Android/data/com.ProjectMoon.LimbusCompany/cache/UnityCache/Shared/fixture/__data")
                .readText() == "portable-game-cache")
            "MIGRATION_PASS $digest"
        }.getOrElse { "MIGRATION_FAIL ${it.javaClass.simpleName}: ${it.message}" }
        setContentView(TextView(this).apply { text = result })
    }
}
