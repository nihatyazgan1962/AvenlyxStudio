package com.formatci.mediastudio

import android.app.Application
import android.content.Context
import com.formatci.mediastudio.core.files.FileStorageHelper
import com.formatci.mediastudio.data.database.StudioDatabase
import com.formatci.mediastudio.data.preferences.UserPreferencesRepository
import com.formatci.mediastudio.data.repository.StudioRepository
import com.formatci.mediastudio.media.engine.CompositeMediaEngine
import com.formatci.mediastudio.service.MediaProcessingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val database: StudioDatabase = StudioDatabase(appContext)
    val fileHelper: FileStorageHelper = FileStorageHelper(appContext)
    val prefs: UserPreferencesRepository = UserPreferencesRepository(appContext)
    val mediaEngine: CompositeMediaEngine = CompositeMediaEngine(
        context = appContext,
        fileHelper = fileHelper
    )
    val repository: StudioRepository = StudioRepository(
        context = appContext,
        engine = mediaEngine,
        db = database,
        fileHelper = fileHelper,
        prefs = prefs
    )
}

class MediaStudioApp : Application() {

    lateinit var container: AppContainer
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        MediaProcessingService.createNotificationChannel(this)

        container = AppContainer(this)

        container.repository.onProcessingStateChanged = { activeJob, activeOrQueuedCount ->
            MediaProcessingService.syncProcessingState(
                context = this,
                activeJob = activeJob,
                activeOrQueuedCount = activeOrQueuedCount
            )
        }

        MediaProcessingService.onCancelRequested = { jobId ->
            appScope.launch {
                val targetId = jobId ?: container.repository.jobsFlow.value
                    .firstOrNull { it.status.isActive }
                    ?.id
                if (!targetId.isNullOrBlank()) {
                    container.repository.cancelJob(targetId)
                }
            }
        }

        MediaProcessingService.onTimeoutCallback = { jobId ->
            appScope.launch {
                if (!jobId.isNullOrBlank()) {
                    container.repository.cancelJob(jobId)
                }
            }
        }
    }
}
