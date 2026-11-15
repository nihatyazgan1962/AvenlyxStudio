package com.formatci.mediastudio

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.formatci.mediastudio.core.designsystem.FormatciTheme
import com.formatci.mediastudio.feature.audio.AudioStudioScreen
import com.formatci.mediastudio.feature.home.HomeScreen
import com.formatci.mediastudio.feature.image.ImageAndSlideshowScreen
import com.formatci.mediastudio.feature.picker.ExtractedFrameDialog
import com.formatci.mediastudio.feature.picker.PickerAndDetailScreen
import com.formatci.mediastudio.feature.queue.HistoryScreen
import com.formatci.mediastudio.feature.queue.QueueScreen
import com.formatci.mediastudio.feature.queue.SettingsScreen
import com.formatci.mediastudio.feature.video.VideoStudioScreen
import com.formatci.mediastudio.ui.PickerTargetRole
import com.formatci.mediastudio.ui.StudioDestination
import com.formatci.mediastudio.ui.StudioUiEvent
import com.formatci.mediastudio.ui.StudioViewModel
import com.formatci.mediastudio.ui.StudioWorkspaceTab
import kotlinx.coroutines.launch

private class DynamicCreateDocumentContract : ActivityResultContract<Pair<String, String>, Uri?>() {
    override fun createIntent(context: Context, input: Pair<String, String>): Intent {
        val (mimeType, suggestedName) = input
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType.ifBlank { "*/*" }
            putExtra(Intent.EXTRA_TITLE, suggestedName)
        }
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? {
        return if (resultCode == android.app.Activity.RESULT_OK) intent?.data else null
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val appContainer = (application as MediaStudioApp).container
        val vmFactory = StudioViewModel.Factory(
            appContext = applicationContext,
            repository = appContainer.repository,
            fileHelper = appContainer.fileHelper,
            prefs = appContainer.prefs
        )

        setContent {
            val viewModel: StudioViewModel = viewModel(factory = vmFactory)
            val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()

            FormatciTheme(themePreference = settings.themePreference) {
                FormatciStudioRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun FormatciStudioRoot(viewModel: StudioViewModel) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val jobs by viewModel.jobsFlow.collectAsStateWithLifecycle()
    val history by viewModel.historyFlow.collectAsStateWithLifecycle()
    val recentFiles by viewModel.recentFilesFlow.collectAsStateWithLifecycle()
    val timeline by viewModel.activeTimelineFlow.collectAsStateWithLifecycle()
    val canUndo by viewModel.canUndoFlow.collectAsStateWithLifecycle()
    val canRedo by viewModel.canRedoFlow.collectAsStateWithLifecycle()
    val settings by viewModel.settingsFlow.collectAsStateWithLifecycle()

    var pendingRole by remember { mutableStateOf(PickerTargetRole.GENERAL_IMPORT) }

    val multiPhotoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(25)
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris, pendingRole)
        }
    }

    val multiDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            viewModel.importUris(uris, pendingRole)
        }
    }

    val singleDocLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importUris(listOf(uri), pendingRole)
        }
    }

    val saveAsLauncher = rememberLauncherForActivityResult(
        contract = DynamicCreateDocumentContract()
    ) { targetUri ->
        viewModel.completeSaveAs(targetUri)
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { _ -> }

    fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && settings.notificationsEnabled) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    fun launchPhotoPicker(role: PickerTargetRole, mediaType: ActivityResultContracts.PickVisualMedia.VisualMediaType) {
        pendingRole = role
        multiPhotoPickerLauncher.launch(PickVisualMediaRequest(mediaType))
    }

    fun launchMultiDocumentPicker(role: PickerTargetRole, mimeTypes: Array<String>) {
        pendingRole = role
        multiDocLauncher.launch(mimeTypes)
    }

    fun launchSingleDocumentPicker(role: PickerTargetRole, mimeTypes: Array<String>) {
        pendingRole = role
        singleDocLauncher.launch(mimeTypes)
    }

    fun navigateToDestination(dest: StudioDestination) {
        viewModel.navigateTo(dest)
        navController.navigate(dest.name) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    LaunchedEffect(uiState.activeDestination) {
        val currentRoute = navController.currentBackStackEntry?.destination?.route
        if (currentRoute != uiState.activeDestination.name) {
            navController.navigate(uiState.activeDestination.name) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is StudioUiEvent.ShowMessage -> {
                    coroutineScope.launch {
                        val res = snackbarHostState.showSnackbar(
                            message = event.message,
                            actionLabel = event.actionLabel,
                            duration = SnackbarDuration.Short
                        )
                        if (res == SnackbarResult.ActionPerformed && event.targetDestination != null) {
                            navigateToDestination(event.targetDestination)
                        }
                    }
                }

                is StudioUiEvent.OpenOutput -> {
                    val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(event.uri, event.mimeType)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(viewIntent, "Dosyayı Aç"))
                    }.onFailure {
                        viewModel.emitMessage("Bu dosya türünü açabilecek uygulama cihazda bulunamadı.")
                    }
                }

                is StudioUiEvent.ShareOutput -> {
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = event.mimeType
                        putExtra(Intent.EXTRA_STREAM, event.uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(shareIntent, event.title))
                    }.onFailure {
                        viewModel.emitMessage("Paylaşım penceresi açılamadı.")
                    }
                }

                is StudioUiEvent.RequestSaveAs -> {
                    saveAsLauncher.launch(event.mimeType to event.suggestedName)
                }
            }
        }
    }

    val activeJobsCount = remember(jobs) { jobs.count { it.status.isActive } }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val isWideScreen = maxWidth >= 840.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!isWideScreen) {
                    NavigationBar {
                        StudioDestination.entries.forEach { dest ->
                            NavigationBarItem(
                                selected = uiState.activeDestination == dest,
                                onClick = { navigateToDestination(dest) },
                                icon = {
                                    DestinationIcon(dest = dest, activeJobsCount = activeJobsCount)
                                },
                                label = {
                                    Text(
                                        text = dest.labelTr,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (isWideScreen) {
                    NavigationRail(
                        modifier = Modifier.fillMaxHeight(),
                        header = {
                            Icon(
                                imageVector = Icons.Rounded.AutoFixHigh,
                                contentDescription = "Avenlyx Studio",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 12.dp, bottom = 8.dp).size(28.dp)
                            )
                        }
                    ) {
                        Spacer(modifier = Modifier.weight(0.2f))
                        StudioDestination.entries.forEach { dest ->
                            NavigationRailItem(
                                selected = uiState.activeDestination == dest,
                                onClick = { navigateToDestination(dest) },
                                icon = {
                                    DestinationIcon(dest = dest, activeJobsCount = activeJobsCount)
                                },
                                label = { Text(dest.labelTr) }
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = StudioDestination.HOME.name,
                    modifier = Modifier.weight(1f).fillMaxHeight()
                ) {
                    composable(StudioDestination.HOME.name) {
                        HomeScreen(
                            settings = settings,
                            capabilities = viewModel.capabilities,
                            jobs = jobs,
                            recentFiles = recentFiles,
                            availableStorageBytes = uiState.availableStorageBytes,
                            onToggleSimpleMode = { viewModel.setSimpleMode(it) },
                            onToolClick = { tool ->
                                viewModel.openTool(tool)
                                navigateToDestination(StudioDestination.STUDIO)
                            },
                            onOpenPicker = {
                                viewModel.selectStudioTab(StudioWorkspaceTab.PICKER)
                                navigateToDestination(StudioDestination.STUDIO)
                                launchMultiDocumentPicker(
                                    PickerTargetRole.GENERAL_IMPORT,
                                    arrayOf("video/*", "audio/*", "image/*", "*/*")
                                )
                            },
                            onOpenPhotoPicker = {
                                viewModel.selectStudioTab(StudioWorkspaceTab.PICKER)
                                navigateToDestination(StudioDestination.STUDIO)
                                launchPhotoPicker(
                                    PickerTargetRole.GENERAL_IMPORT,
                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                )
                            },
                            onOpenQueue = { navigateToDestination(StudioDestination.QUEUE) },
                            onRecentFileClick = { recent ->
                                viewModel.importFromRecent(recent, PickerTargetRole.GENERAL_IMPORT)
                                viewModel.selectStudioTab(StudioWorkspaceTab.PICKER)
                                navigateToDestination(StudioDestination.STUDIO)
                            }
                        )
                    }

                    composable(StudioDestination.STUDIO.name) {
                        Column(modifier = Modifier.fillMaxSize()) {
                            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                                LazyRow(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
                                ) {
                                    items(StudioWorkspaceTab.entries) { tab ->
                                        FilterChip(
                                            selected = uiState.activeStudioTab == tab,
                                            onClick = { viewModel.selectStudioTab(tab) },
                                            leadingIcon = {
                                                Icon(
                                                    imageVector = when (tab) {
                                                        StudioWorkspaceTab.PICKER -> Icons.Rounded.FolderCopy
                                                        StudioWorkspaceTab.VIDEO -> Icons.Rounded.Movie
                                                        StudioWorkspaceTab.AUDIO -> Icons.Rounded.GraphicEq
                                                        StudioWorkspaceTab.IMAGE -> Icons.Rounded.PhotoLibrary
                                                    },
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            label = { Text(tab.labelTr) }
                                        )
                                    }
                                }
                            }

                            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                                when (uiState.activeStudioTab) {
                                    StudioWorkspaceTab.PICKER -> {
                                        PickerAndDetailScreen(
                                            importedMedia = uiState.importedMedia,
                                            selectedMediaIds = uiState.selectedMediaIds,
                                            focusedMedia = uiState.focusedMedia,
                                            recentFiles = recentFiles,
                                            isImporting = uiState.isImporting,
                                            onOpenPhotoPicker = {
                                                launchPhotoPicker(
                                                    PickerTargetRole.GENERAL_IMPORT,
                                                    ActivityResultContracts.PickVisualMedia.ImageAndVideo
                                                )
                                            },
                                            onOpenDocumentPicker = {
                                                launchMultiDocumentPicker(
                                                    PickerTargetRole.GENERAL_IMPORT,
                                                    arrayOf("video/*", "audio/*", "image/*", "*/*")
                                                )
                                            },
                                            onFocusMedia = { viewModel.focusMedia(it) },
                                            onToggleSelectMedia = { viewModel.toggleMediaSelection(it) },
                                            onRemoveMedia = { viewModel.removeImportedMedia(it) },
                                            onOpenWithTool = { info, tool -> viewModel.openMediaWithTool(info, tool) },
                                            onEnqueueBatch = {
                                                requestNotificationPermissionIfNeeded()
                                                viewModel.enqueueBatchForSelected()
                                            },
                                            onExtractFrame = { uri, pos, name ->
                                                viewModel.extractVideoFrame(uri, pos, name)
                                            },
                                            onImportRecent = {
                                                viewModel.importFromRecent(it, PickerTargetRole.GENERAL_IMPORT)
                                            }
                                        )
                                    }

                                    StudioWorkspaceTab.VIDEO -> {
                                        VideoStudioScreen(
                                            project = timeline,
                                            selectedClipId = uiState.selectedTimelineClipId,
                                            activeTool = uiState.activeTool,
                                            simpleMode = settings.simpleMode,
                                            canUndo = canUndo,
                                            canRedo = canRedo,
                                            capabilities = viewModel.capabilities,
                                            estimate = uiState.videoEstimate,
                                            onSelectTool = { viewModel.openTool(it) },
                                            onAddVideos = {
                                                launchMultiDocumentPicker(
                                                    PickerTargetRole.VIDEO_CLIPS,
                                                    arrayOf("video/*")
                                                )
                                            },
                                            onSelectClip = { viewModel.selectTimelineClip(it) },
                                            onMoveClip = { id, up -> viewModel.moveTimelineClip(id, up) },
                                            onRemoveClip = { viewModel.removeTimelineClip(it) },
                                            onUpdateClip = { id, transform -> viewModel.updateTimelineClip(id, transform) },
                                            onSplitClipAt = { id, pos -> viewModel.splitTimelineClipAt(id, pos) },
                                            onPickExternalAudio = {
                                                launchSingleDocumentPicker(
                                                    PickerTargetRole.VIDEO_EXTERNAL_AUDIO,
                                                    arrayOf("audio/*")
                                                )
                                            },
                                            onClearExternalAudio = { viewModel.setExternalAudioForTimeline(null) },
                                            onUpdateExternalAudioConfig = { vol, fit ->
                                                viewModel.updateExternalAudioConfig(vol, fit)
                                            },
                                            onUpdateExportSettings = { viewModel.updateTimelineExportSettings(it) },
                                            onUndo = { viewModel.undoTimeline() },
                                            onRedo = { viewModel.redoTimeline() },
                                            onClearTimeline = { viewModel.clearTimeline() },
                                            onExtractFrameAt = { uri, pos, name ->
                                                viewModel.extractVideoFrame(uri, pos, name)
                                            },
                                            onStartVideoJob = { tool ->
                                                requestNotificationPermissionIfNeeded()
                                                viewModel.enqueueVideoJob(tool)
                                            }
                                        )
                                    }

                                    StudioWorkspaceTab.AUDIO -> {
                                        AudioStudioScreen(
                                            audioInputs = uiState.audioInputs,
                                            focusedAudio = uiState.focusedAudio,
                                            settings = uiState.audioSettings,
                                            simpleMode = settings.simpleMode,
                                            capabilities = viewModel.capabilities,
                                            estimate = uiState.audioEstimate,
                                            onAddAudios = {
                                                launchMultiDocumentPicker(
                                                    PickerTargetRole.AUDIO_TRACKS,
                                                    arrayOf("audio/*")
                                                )
                                            },
                                            onSelectFocusedAudio = { viewModel.selectFocusedAudio(it) },
                                            onMoveAudio = { id, up -> viewModel.moveAudioItem(id, up) },
                                            onRemoveAudio = { viewModel.removeAudioItem(it) },
                                            onUpdateSettings = { viewModel.updateAudioSettings(it) },
                                            onStartAudioJob = { tool ->
                                                requestNotificationPermissionIfNeeded()
                                                viewModel.enqueueAudioJob(tool)
                                            }
                                        )
                                    }

                                    StudioWorkspaceTab.IMAGE -> {
                                        ImageAndSlideshowScreen(
                                            imageInputs = uiState.imageInputs,
                                            focusedImage = uiState.focusedImage,
                                            settings = uiState.imageSettings,
                                            slideshowMode = uiState.slideshowMode,
                                            slideshowAudio = uiState.slideshowBackgroundAudio,
                                            simpleMode = settings.simpleMode,
                                            capabilities = viewModel.capabilities,
                                            estimate = uiState.imageEstimate,
                                            onSetSlideshowMode = { viewModel.setSlideshowMode(it) },
                                            onAddImages = {
                                                launchPhotoPicker(
                                                    PickerTargetRole.IMAGE_FILES,
                                                    ActivityResultContracts.PickVisualMedia.ImageOnly
                                                )
                                            },
                                            onSelectFocusedImage = { viewModel.selectFocusedImage(it) },
                                            onMoveImage = { id, up -> viewModel.moveImageItem(id, up) },
                                            onRemoveImage = { viewModel.removeImageItem(it) },
                                            onPickSlideshowAudio = {
                                                launchSingleDocumentPicker(
                                                    PickerTargetRole.SLIDESHOW_AUDIO,
                                                    arrayOf("audio/*")
                                                )
                                            },
                                            onClearSlideshowAudio = { viewModel.setSlideshowAudio(null) },
                                            onUpdateSettings = { viewModel.updateImageSettings(it) },
                                            onConvertImage = { compress, batch ->
                                                requestNotificationPermissionIfNeeded()
                                                viewModel.enqueueImageConvert(compress, batch)
                                            },
                                            onCreateSlideshow = {
                                                requestNotificationPermissionIfNeeded()
                                                viewModel.enqueueSlideshowJob()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    composable(StudioDestination.QUEUE.name) {
                        QueueScreen(
                            jobs = jobs,
                            onMoveJob = { id, up -> viewModel.moveJobOrder(id, up) },
                            onCancelJob = { viewModel.cancelJob(it) },
                            onRetryJob = {
                                requestNotificationPermissionIfNeeded()
                                viewModel.retryJob(it)
                            },
                            onClearFinished = { viewModel.clearFinishedJobs() },
                            onOpenOutput = { uri, mime -> viewModel.openUri(uri, mime) },
                            onShareOutput = { uri, mime, title -> viewModel.shareUri(uri, mime, title) },
                            onSaveAsOutput = { uri, name, mime -> viewModel.requestSaveAs(uri, name, mime) }
                        )
                    }

                    composable(StudioDestination.HISTORY.name) {
                        HistoryScreen(
                            records = history,
                            filter = uiState.historyFilter,
                            sortOrder = uiState.historySortOrder,
                            searchQuery = uiState.historySearchQuery,
                            onFilterChange = { viewModel.setHistoryFilter(it) },
                            onSortOrderChange = { viewModel.setHistorySortOrder(it) },
                            onSearchQueryChange = { viewModel.setHistorySearchQuery(it) },
                            onOpenRecord = { rec -> viewModel.openUri(rec.uriString, rec.mimeType) },
                            onShareRecord = { rec ->
                                viewModel.shareUri(rec.uriString, rec.mimeType, rec.displayName)
                            },
                            onSaveAsRecord = { rec ->
                                viewModel.requestSaveAs(rec.uriString, rec.displayName, rec.mimeType)
                            },
                            onRenameRecord = { id, newName -> viewModel.renameHistoryRecord(id, newName) },
                            onDeleteRecord = { id, deleteFile -> viewModel.deleteHistoryRecord(id, deleteFile) },
                            onOpenSoundSettings = {
                                runCatching {
                                    context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS))
                                }.onSuccess {
                                    viewModel.emitMessage("Ses dosyası Music/AvenlyxStudio klasöründe; buradan zil sesi olarak seçebilirsiniz.")
                                }.onFailure {
                                    viewModel.emitMessage("Sistem ses ayarları açılamadı.")
                                }
                            }
                        )
                    }

                    composable(StudioDestination.SETTINGS.name) {
                        SettingsScreen(
                            settings = settings,
                            capabilities = viewModel.capabilities,
                            cacheSizeBytes = uiState.cacheSizeBytes,
                            availableStorageBytes = uiState.availableStorageBytes,
                            onSetTheme = { viewModel.setThemePreference(it) },
                            onSetSimpleMode = { viewModel.setSimpleMode(it) },
                            onSetDefaultQuality = { viewModel.setDefaultQuality(it) },
                            onSetDefaultVideoFormat = { viewModel.setDefaultVideoFormat(it) },
                            onSetDefaultAudioFormat = { viewModel.setDefaultAudioFormat(it) },
                            onSetDefaultImageFormat = { viewModel.setDefaultImageFormat(it) },
                            onSetNotificationsEnabled = { enabled ->
                                viewModel.setNotificationsEnabled(enabled)
                                if (enabled) requestNotificationPermissionIfNeeded()
                            },
                            onSetAutoCleanCache = { viewModel.setAutoCleanCache(it) },
                            onClearCache = { viewModel.clearTempCache() },
                            onExportDiagnosticReport = { viewModel.exportDiagnosticReport() }
                        )
                    }
                }
            }
        }
    }

    val extractedPath = uiState.extractedFramePath
    if (!extractedPath.isNullOrBlank()) {
        ExtractedFrameDialog(
            framePath = extractedPath,
            sourceName = uiState.extractedFrameSourceName ?: "video",
            onDismiss = { viewModel.dismissExtractedFrame() },
            onSaveToGallery = { viewModel.saveExtractedFrameToGallery() },
            onEditInImageStudio = { viewModel.importExtractedFrameToImageStudio() }
        )
    }
}

@Composable
private fun DestinationIcon(dest: StudioDestination, activeJobsCount: Int) {
    val icon: ImageVector = when (dest) {
        StudioDestination.HOME -> Icons.Rounded.Home
        StudioDestination.STUDIO -> Icons.Rounded.Tune
        StudioDestination.QUEUE -> Icons.Rounded.Queue
        StudioDestination.HISTORY -> Icons.Rounded.FolderCopy
        StudioDestination.SETTINGS -> Icons.Rounded.Settings
    }
    if (dest == StudioDestination.QUEUE && activeJobsCount > 0) {
        BadgedBox(
            badge = {
                Badge { Text(activeJobsCount.toString()) }
            }
        ) {
            Icon(imageVector = icon, contentDescription = dest.labelTr)
        }
    } else {
        Icon(imageVector = icon, contentDescription = dest.labelTr)
    }
}
