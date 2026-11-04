package com.example.limbuszhcn

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.limbuszhcn.tracking.ParatranzRepository
import com.example.limbuszhcn.tracking.ProjectTrackingState
import com.example.limbuszhcn.tracking.ProjectSource
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Screen facade only; the application owns work and durable state. */
internal class LauncherViewModel(application: Application) : AndroidViewModel(application) {
    val appUpdater = com.example.limbuszhcn.selfupdate.AppUpdateController(application)
    private val coordinator = LaunchCoordinator.get(application)
    private val tracker = ParatranzRepository(application.filesDir.toPath().resolve("project-tracker/6860.json"))
    private val github = coordinator.github
    private val preferences = application.getSharedPreferences("project-tracker", 0)
    private val trackerWorker = Executors.newSingleThreadExecutor { task -> Thread(task, "limbus-project-tracker") }
    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val closed = AtomicBoolean(false)
    var projectState by mutableStateOf(ProjectTrackingState(
        source = ProjectSource.fromPreference(preferences.getString("source", null))))
        private set
    private val stopObserving = github.observe { updated ->
        main.post { if (!closed.get()) projectState = projectState.copy(github = updated) }
    }
    val state: LauncherUiState get() = coordinator.state
    fun launch() = coordinator.launch()
    fun refresh() {
        coordinator.refresh()
        coordinator.checkGameUpdate()
        refreshProject()
    }
    fun refreshProject(force: Boolean = false) {
        if (projectState.loading || closed.get()) return
        val source = projectState.source
        projectState = when (source) {
            ProjectSource.GitHub -> projectState.copy(github = projectState.github.copy(loading = true))
            ProjectSource.ParaTranz -> projectState.copy(paratranz = projectState.paratranz.copy(loading = true))
        }
        trackerWorker.execute {
            when (source) {
                ProjectSource.GitHub -> {
                    // The shared repository publishes in request order, including cache/cooldown
                    // hits. A late worker post must not overwrite a newer launch-check result.
                    github.refresh(force)
                }
                ProjectSource.ParaTranz -> {
                    val cached = tracker.cached()
                    main.post { if (!closed.get()) projectState = projectState.copy(paratranz = cached.copy(loading = true)) }
                    val updated = tracker.refresh(force)
                    main.post { if (!closed.get()) projectState = projectState.copy(paratranz = updated) }
                }
            }
        }
    }
    fun selectProjectSource(source: ProjectSource) {
        if (closed.get() || projectState.source == source) return
        projectState = projectState.copy(source = source)
        preferences.edit().putString("source", source.key).apply()
        refreshProject()
    }
    override fun onCleared() {
        appUpdater.close()
        closed.set(true)
        stopObserving()
        trackerWorker.shutdownNow()
        super.onCleared()
    }
    fun updateTranslation() = coordinator.updateTranslation()
    fun checkGameUpdate() = coordinator.checkGameUpdate(force = true)
    fun requestGameUpdates() = coordinator.requestGameUpdates()
    fun preparePackageUpdate(uris: List<Uri>) = coordinator.preparePackageUpdate(uris)
    fun installPackageUpdate() = coordinator.installPackageUpdate()
    fun cancelPackageUpdate() = coordinator.cancelPackageUpdate()
    fun installConfirmationOpened() = coordinator.installConfirmationOpened()
    fun selectGameSource(source: com.example.limbuszhcn.update.GameUpdateSource) = coordinator.selectGameSource(source)
    fun syncGame() = coordinator.syncGame()
    fun openGoogleSettings() = coordinator.openGoogleSettings()
    fun restore() = coordinator.restore()
    fun exportDiagnostics() = coordinator.exportDiagnostics()
    fun exportPickerOpened() = coordinator.exportPickerOpened()
    fun exportPickerFailed(error: Throwable) = coordinator.exportPickerFailed(error)
    fun saveDiagnostics(destination: Uri?) = coordinator.saveDiagnostics(destination)
    fun showError(error: Throwable) = coordinator.showError(error)
}
