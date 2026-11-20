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
    var homeProjectSourceId by mutableStateOf(preferences.getString("home-source-id", null))
        private set
    fun selectHomeProjectSource(id: String?) {
        if (closed.get() || id != null && state.sources.sources.none { it.id == id }) return
        homeProjectSourceId = id
        preferences.edit().putString("home-source-id", id).apply()
    }
    var projectState by mutableStateOf(ProjectTrackingState(
        source = ProjectSource.fromPreference(preferences.getString("source", null))))
        private set
    private val stopObserving = github.observe { updated ->
        main.post { if (!closed.get()) projectState = projectState.copy(github = updated) }
    }
    val state: LauncherUiState get() = coordinator.state.withMigrationTask(com.example.limbuszhcn.migration.MigrationModel.currentState())
    fun launch() = coordinator.launch()
    fun refresh() {
        refreshStatus(preserveFailure = false)
    }
    fun resumeRefresh() = refreshStatus(preserveFailure = true)
    private fun refreshStatus(preserveFailure: Boolean) {
        coordinator.refresh(preserveFailure)
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
    fun selectTranslation(id: String) = coordinator.selectTranslationSource(id)
    fun importTranslation(uri: Uri, replaceId: String?) = coordinator.importTranslation(uri, replaceId)
    fun renameTranslation(id: String, name: String) = coordinator.renameTranslation(id, name)
    fun translationIcon(id: String, uri: Uri?) = coordinator.setTranslationIcon(id, uri)
    fun removeTranslation(id: String) = coordinator.removeTranslation(id)
    fun removeAiTranslation(id: String) = coordinator.removeAiTranslation(id)
    fun chooseAiUpdate(choice: com.example.limbuszhcn.translation.AiUpdateChoice?) = coordinator.chooseAiUpdate(choice)
    fun saveAiPrompt(prompt: String) = coordinator.saveAiPrompt(prompt)
    fun exportTranslation(id: String, destination: Uri) = coordinator.exportTranslation(id, destination)
    fun saveAi(config: com.example.limbuszhcn.translation.AiConfig, key: String, test: Boolean) = coordinator.saveAi(config, key, test)
    fun generateAi(config: com.example.limbuszhcn.translation.AiConfig, key: String) = coordinator.generateAi(config, key)
    fun generateCoverage(config: com.example.limbuszhcn.translation.AiConfig, key: String, request: com.example.limbuszhcn.translation.CoverageRequest) = coordinator.generateAi(config, key, request)
    fun coverageCount(ref: com.example.limbuszhcn.translation.CoverageIndexRef,
        filter: com.example.limbuszhcn.translation.CoverageFilter, numbers: Set<Int>?, cancel: android.os.CancellationSignal) = coordinator.coverageCount(ref, filter, numbers, cancel)
    fun pauseAi() = coordinator.pauseAi()
    fun resumeAi() = coordinator.resumeAi()
    fun cancelAi() = coordinator.cancelAi()
    fun loadAiModels(endpoint: String, key: String) = coordinator.loadAiModels(endpoint, key)
    fun clearAiModels() = coordinator.clearAiModels()
    fun inspectCoverage() = coordinator.inspectCoverage()
    fun coverageEntries(ref: com.example.limbuszhcn.translation.CoverageIndexRef,
        filter: com.example.limbuszhcn.translation.CoverageFilter, page: Int, cancel: android.os.CancellationSignal) =
        coordinator.coverageEntries(ref, filter, page, cancel)
    fun undoAi() = coordinator.undoAi()
    fun saveAiKey(endpoint: String, model: String, key: String, name: String) = coordinator.saveAiKey(endpoint, model, key, name)
    fun selectAiKey(id: String) = coordinator.selectAiKey(id)
    fun clearAiKey(id: String) = coordinator.clearAiKey(id)
    fun renameAiKey(id: String, name: String) = coordinator.renameAiKey(id, name)
    fun reorderAiKeys(ids: List<String>) = coordinator.reorderAiKeys(ids)
    fun inspectAiEntries(id: String?, force: Boolean) = coordinator.inspectAiEntries(id, force)
    fun downloadAiOriginals() = coordinator.launchForAiResources()
    fun aiEntries(report: com.example.limbuszhcn.translation.AiEntryReport, filter: com.example.limbuszhcn.translation.AiEntryFilter,
        page: Int, cancel: android.os.CancellationSignal) = coordinator.aiEntries(report, filter, page, cancel)
    fun generateAiEntries(config: com.example.limbuszhcn.translation.AiConfig, key: String, request: com.example.limbuszhcn.translation.AiEntryRequest) =
        coordinator.generateAiEntries(config, key, request)
    fun inspectAiBaseUpdate(id: String) = coordinator.inspectAiBaseUpdate(id)
    fun applyAiBaseUpdate(request: com.example.limbuszhcn.translation.AiBaseRequest) = coordinator.applyAiBaseUpdate(request)
    fun aiBaseChanges(report: com.example.limbuszhcn.translation.AiBaseReport, kind: com.example.limbuszhcn.translation.AiBaseChangeKind?, page: Int, signal: android.os.CancellationSignal) = coordinator.aiBaseChanges(report, kind, page, signal)
    fun loadAiPrice(endpoint: String, model: String, refresh: Boolean) = coordinator.loadAiPrice(endpoint, model, refresh)
    fun saveAiPrice(endpoint: String, model: String, price: com.example.limbuszhcn.translation.AiPrice?) = coordinator.saveAiPrice(endpoint, model, price)
    fun deleteAiEntries(request: com.example.limbuszhcn.translation.AiEntryRequest) = coordinator.deleteAiEntries(request)
    fun checkGameUpdate() = coordinator.checkGameUpdate(force = true)
    fun requestGameUpdates() = coordinator.requestGameUpdates()
    fun preparePackageUpdate(uris: List<Uri>) = coordinator.preparePackageUpdate(uris)
    fun installPackageUpdate() = coordinator.installPackageUpdate()
    fun cancelPackageUpdate() = coordinator.cancelPackageUpdate()
    fun installConfirmationOpened() = coordinator.installConfirmationOpened()
    fun selectGameSource(source: com.example.limbuszhcn.update.GameUpdateSource) = coordinator.selectGameSource(source)
    fun repair() = coordinator.repair()
    fun clearGameResources() = coordinator.clearGameResources()
    fun openGoogleSettings() = coordinator.openGoogleSettings()
    fun checkGooglePayment() = coordinator.checkGooglePayment()
    fun enableGooglePayment() = coordinator.checkGooglePayment(enable = true)
    fun exportDiagnostics() = coordinator.exportDiagnostics()
    fun exportPickerOpened() = coordinator.exportPickerOpened()
    fun exportPickerFailed(error: Throwable) = coordinator.exportPickerFailed(error)
    fun saveDiagnostics(destination: Uri?) = coordinator.saveDiagnostics(destination)
    fun showError(error: Throwable) = coordinator.showError(error)
}
