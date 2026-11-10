package com.example.limbuszhcn.selfupdate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.limbuszhcn.PersistentDiagnosticLog
import com.example.limbuszhcn.SystemStartupDiagnostics
import java.io.File

/** The foreground handoff shared by the UI and the disposable cloud fixture. */
internal class AppUpdateInstaller(private val context: Context) {
    private val journal = AppInstallJournal(File(context.noBackupFilesDir, "diagnostics/app-install.properties").toPath())

    fun prepare(file: File, release: AppRelease) {
        AppUpdatePackage.verify(context, file, release)
        record { journal.prepare(AppUpdatePackage.installedCode(context), release.versionCode, System.currentTimeMillis()) }
    }

    fun intent(file: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.app-updates", file)
        val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .apply { clipData = ClipData.newRawUri("BYBUS", uri) }
        record { journal.phase(AppInstallJournal.Phase.DISPATCHED, System.currentTimeMillis()) }
        SystemStartupDiagnostics.checkpoint(context, SystemStartupDiagnostics.Phase.INSTALLER_HANDOFF)
        return intent
    }

    fun returned(): Long {
        val installed = AppUpdatePackage.installedCode(context)
        record {
            journal.phase(AppInstallJournal.Phase.RETURNED, System.currentTimeMillis())
            journal.observe(installed, System.currentTimeMillis())
        }
        return installed
    }

    fun failed() = record { journal.phase(AppInstallJournal.Phase.DISPATCH_FAILED, System.currentTimeMillis()) }

    fun observe(homeFrame: Boolean = false) = record {
        journal.observe(AppUpdatePackage.installedCode(context), System.currentTimeMillis(), homeFrame)
    }

    fun summary(): String = journal.summary()

    private inline fun record(action: () -> Unit) {
        runCatching(action).onFailure {
            PersistentDiagnosticLog.info("AppInstall", "receipt unavailable=${it.javaClass.simpleName}")
        }
    }
}
