@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.formatci.mediastudio.feature.video

import android.net.Uri
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Redo
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.formatci.mediastudio.core.designsystem.LocalStudioColors
import com.formatci.mediastudio.domain.model.AspectRatioOption
import com.formatci.mediastudio.domain.model.AudioFitMode
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaJobSettings
import com.formatci.mediastudio.domain.model.QualityProfile
import com.formatci.mediastudio.domain.model.StudioTool
import com.formatci.mediastudio.domain.model.TimelineClipItem
import com.formatci.mediastudio.domain.model.TimelineProject
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

private enum class VideoInspectorTab(val labelTr: String) {
    TIMELINE("Zaman & Ses"),
    FILTERS("Görüntü & Filtre"),
    EXPORT("Dönüştürme & Codec")
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun VideoStudioScreen(
    project: TimelineProject,
    selectedClipId: String?,
    activeTool: StudioTool,
    simpleMode: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    capabilities: EngineCapabilities,
    estimate: JobEstimate?,
    onSelectTool: (StudioTool) -> Unit,
    onAddVideos: () -> Unit,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, Boolean) -> Unit,
    onRemoveClip: (String) -> Unit,
    onUpdateClip: (String, (TimelineClipItem) -> TimelineClipItem) -> Unit,
    onSplitClipAt: (String, Long) -> Unit,
    onPickExternalAudio: () -> Unit,
    onClearExternalAudio: () -> Unit,
    onUpdateExternalAudioConfig: (volume: Float, fitMode: AudioFitMode) -> Unit,
    onUpdateExportSettings: ((MediaJobSettings) -> MediaJobSettings) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onClearTimeline: () -> Unit,
    onExtractFrameAt: (uriString: String, positionMs: Long, sourceName: String) -> Unit,
    onStartVideoJob: (StudioTool) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val selectedClip = remember(project.clips, selectedClipId) {
        project.clips.firstOrNull { it.clipId == selectedClipId } ?: project.clips.firstOrNull()
    }

    val initialTab = remember(activeTool) {
        when (activeTool) {
            StudioTool.VIDEO_EDIT -> VideoInspectorTab.FILTERS
            StudioTool.VIDEO_CONVERT, StudioTool.VIDEO_EXTRACT_AUDIO -> VideoInspectorTab.EXPORT
            else -> VideoInspectorTab.TIMELINE
        }
    }
    var activeTabIdx by remember(activeTool) { mutableIntStateOf(initialTab.ordinal) }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }
    var currentPlaybackPosMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(exoPlayer) {
        onDispose { exoPlayer.release() }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                exoPlayer.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(selectedClip?.input?.uriString) {
        val uriStr = selectedClip?.input?.uriString
        if (uriStr.isNullOrBlank()) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            currentPlaybackPosMs = 0L
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(uriStr)))
            exoPlayer.prepare()
            exoPlayer.seekTo(selectedClip.trimStartMs.coerceAtLeast(0L))
            currentPlaybackPosMs = selectedClip.trimStartMs
        }
    }

    LaunchedEffect(selectedClip?.speed) {
        val spd = (selectedClip?.speed ?: 1f).coerceIn(0.25f, 2.0f)
        exoPlayer.playbackParameters = PlaybackParameters(spd)
    }

    LaunchedEffect(selectedClip?.muted, selectedClip?.volume) {
        val vol = if (selectedClip?.muted == true) 0f else (selectedClip?.volume ?: 1f).coerceIn(0f, 1f)
        exoPlayer.volume = vol
    }

    val videoTools = remember {
        listOf(
            StudioTool.VIDEO_CONVERT,
            StudioTool.VIDEO_EDIT,
            StudioTool.VIDEO_MERGE,
            StudioTool.VIDEO_TRIM_SPLIT,
            StudioTool.VIDEO_EXTRACT_AUDIO,
            StudioTool.VIDEO_ADD_AUDIO
        )
    }
    val effectiveTool = if (activeTool in videoTools) activeTool else StudioTool.VIDEO_CONVERT

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Üst araç çubuğu + Undo/Redo
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = effectiveTool.titleTr,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onUndo, enabled = canUndo) {
                                Icon(Icons.AutoMirrored.Rounded.Undo, contentDescription = "Geri al")
                            }
                            IconButton(onClick = onRedo, enabled = canRedo) {
                                Icon(Icons.AutoMirrored.Rounded.Redo, contentDescription = "Yinele")
                            }
                            if (project.clips.isNotEmpty()) {
                                IconButton(onClick = onClearTimeline) {
                                    Icon(Icons.Rounded.DeleteSweep, contentDescription = "Zaman çizelgesini temizle")
                                }
                            }
                        }
                    }

                    androidx.compose.foundation.lazy.LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        items(videoTools.size) { index ->
                            val tool = videoTools[index]
                            FilterChip(
                                selected = effectiveTool == tool,
                                onClick = {
                                    onSelectTool(tool)
                                    activeTabIdx = when (tool) {
                                        StudioTool.VIDEO_EDIT -> VideoInspectorTab.FILTERS.ordinal
                                        StudioTool.VIDEO_CONVERT, StudioTool.VIDEO_EXTRACT_AUDIO -> VideoInspectorTab.EXPORT.ordinal
                                        else -> VideoInspectorTab.TIMELINE.ordinal
                                    }
                                },
                                label = { Text(tool.titleTr) }
                            )
                        }
                    }
                }
            }

            if (project.clips.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = LocalStudioColors.current.videoAccent,
                                modifier = Modifier.size(48.dp)
                            )
                            Text("Video Zaman Çizelgesi Boş", style = MaterialTheme.typography.titleLarge)
                            Text(
                                text = "Bir veya birden fazla video seçerek canlı ExoPlayer önizlemesiyle kesebilir, birleştirebilir, döndürebilir, filtreleyebilir, ses ekleyip dönüştürebilirsiniz.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onAddVideos) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Video Seç")
                            }
                        }
                    }
                }
            } else if (isWide) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1.05f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        VideoPreviewPlayerCard(
                            exoPlayer = exoPlayer,
                            selectedClip = selectedClip,
                            onPlayTrimStart = {
                                val start = selectedClip?.trimStartMs ?: 0L
                                exoPlayer.seekTo(start)
                                exoPlayer.play()
                            },
                            onSplitAtPlayhead = {
                                val clip = selectedClip ?: return@VideoPreviewPlayerCard
                                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                                currentPlaybackPosMs = pos
                                onSplitClipAt(clip.clipId, pos)
                            },
                            onExtractFrameHere = {
                                val clip = selectedClip ?: return@VideoPreviewPlayerCard
                                val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                                onExtractFrameAt(clip.input.uriString, pos, clip.input.displayName)
                            }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        VideoInspectorTabs(
                            activeTabIdx = activeTabIdx,
                            onTabSelected = { activeTabIdx = it }
                        )
                        LazyColumn(
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(top = 10.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            item {
                                VideoTabBody(
                                    tab = VideoInspectorTab.entries[activeTabIdx],
                                    project = project,
                                    selectedClip = selectedClip,
                                    effectiveTool = effectiveTool,
                                    simpleMode = simpleMode,
                                    capabilities = capabilities,
                                    onAddVideos = onAddVideos,
                                    onSelectClip = onSelectClip,
                                    onMoveClip = onMoveClip,
                                    onRemoveClip = onRemoveClip,
                                    onUpdateClip = onUpdateClip,
                                    onSeekPreview = { pos -> exoPlayer.seekTo(pos) },
                                    onPickExternalAudio = onPickExternalAudio,
                                    onClearExternalAudio = onClearExternalAudio,
                                    onUpdateExternalAudioConfig = onUpdateExternalAudioConfig,
                                    onUpdateExportSettings = onUpdateExportSettings
                                )
                            }
                        }
                    }
                }
            } else {
                Column(modifier = Modifier.weight(1f)) {
                    VideoPreviewPlayerCard(
                        exoPlayer = exoPlayer,
                        selectedClip = selectedClip,
                        onPlayTrimStart = {
                            val start = selectedClip?.trimStartMs ?: 0L
                            exoPlayer.seekTo(start)
                            exoPlayer.play()
                        },
                        onSplitAtPlayhead = {
                            val clip = selectedClip ?: return@VideoPreviewPlayerCard
                            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                            currentPlaybackPosMs = pos
                            onSplitClipAt(clip.clipId, pos)
                        },
                        onExtractFrameHere = {
                            val clip = selectedClip ?: return@VideoPreviewPlayerCard
                            val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                            onExtractFrameAt(clip.input.uriString, pos, clip.input.displayName)
                        },
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                    )

                    VideoInspectorTabs(
                        activeTabIdx = activeTabIdx,
                        onTabSelected = { activeTabIdx = it }
                    )

                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            VideoTabBody(
                                tab = VideoInspectorTab.entries[activeTabIdx],
                                project = project,
                                selectedClip = selectedClip,
                                effectiveTool = effectiveTool,
                                simpleMode = simpleMode,
                                capabilities = capabilities,
                                onAddVideos = onAddVideos,
                                onSelectClip = onSelectClip,
                                onMoveClip = onMoveClip,
                                onRemoveClip = onRemoveClip,
                                onUpdateClip = onUpdateClip,
                                onSeekPreview = { pos -> exoPlayer.seekTo(pos) },
                                onPickExternalAudio = onPickExternalAudio,
                                onClearExternalAudio = onClearExternalAudio,
                                onUpdateExternalAudioConfig = onUpdateExternalAudioConfig,
                                onUpdateExportSettings = onUpdateExportSettings
                            )
                        }
                    }
                }
            }

            // Tek elle ulaşılabilir alt aksiyon çubuğu
            if (project.clips.isNotEmpty()) {
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            val estSize = estimate?.let { MediaFormatUtils.formatBytes(it.estimatedOutputBytes) } ?: "-"
                            val totalDur = MediaFormatUtils.formatDurationMs(project.totalDurationMs)
                            Text(
                                text = "$totalDur • Tahmini: $estSize",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = if (estimate?.hasEnoughStorage == false) {
                                    "Uyarı: Yetersiz depolama alanı!"
                                } else {
                                    estimate?.disclaimerTr ?: "Bu değer yaklaşık bir tahmindir."
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (estimate?.hasEnoughStorage == false) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { onStartVideoJob(effectiveTool) }) {
                            Text(
                                when (effectiveTool) {
                                    StudioTool.VIDEO_MERGE -> "Birleştir"
                                    StudioTool.VIDEO_EXTRACT_AUDIO -> "Ses Çıkar"
                                    StudioTool.VIDEO_ADD_AUDIO -> "Sesi Ekle"
                                    else -> "Dışa Aktar"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun VideoPreviewPlayerCard(
    exoPlayer: ExoPlayer,
    selectedClip: TimelineClipItem?,
    onPlayTrimStart: () -> Unit,
    onSplitAtPlayhead: () -> Unit,
    onExtractFrameHere: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .background(LocalStudioColors.current.canvasBackground)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            player = exoPlayer
                            useController = true
                            layoutParams = FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        }
                    },
                    update = { view ->
                        if (view.player !== exoPlayer) {
                            view.player = exoPlayer
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Aktif efekt/döndürme/filigran bilgi rozeti
                if (selectedClip != null) {
                    val badges = buildList {
                        if (selectedClip.rotateDegrees != 0) add("${selectedClip.rotateDegrees}°")
                        if (selectedClip.flipHorizontal) add("Yatay Çevrili")
                        if (selectedClip.flipVertical) add("Dikey Çevrili")
                        if (selectedClip.grayscale) add("Siyah-Beyaz")
                        if (selectedClip.sepia) add("Sepya")
                        if (selectedClip.speed != 1f) add("${selectedClip.speed}x")
                        if (selectedClip.watermarkText.isNotBlank()) add("Filigran")
                    }
                    if (badges.isNotEmpty()) {
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "Çıktıda uygulanır: ${badges.joinToString(" • ")}",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPlayTrimStart) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Kesimden Oynat", maxLines = 1)
                }
                TextButton(onClick = onSplitAtPlayhead) {
                    Icon(Icons.Rounded.ContentCut, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Konumdan Böl", maxLines = 1)
                }
                Spacer(modifier = Modifier.weight(1f))
                IconButton(onClick = onExtractFrameHere) {
                    Icon(Icons.Rounded.CameraAlt, contentDescription = "Buradan karesini çıkar")
                }
            }
        }
    }
}

@Composable
private fun VideoInspectorTabs(
    activeTabIdx: Int,
    onTabSelected: (Int) -> Unit
) {
    SecondaryTabRow(selectedTabIndex = activeTabIdx) {
        VideoInspectorTab.entries.forEachIndexed { idx, tab ->
            Tab(
                selected = activeTabIdx == idx,
                onClick = { onTabSelected(idx) },
                text = { Text(tab.labelTr, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            )
        }
    }
}

@Composable
private fun VideoTabBody(
    tab: VideoInspectorTab,
    project: TimelineProject,
    selectedClip: TimelineClipItem?,
    effectiveTool: StudioTool,
    simpleMode: Boolean,
    capabilities: EngineCapabilities,
    onAddVideos: () -> Unit,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, Boolean) -> Unit,
    onRemoveClip: (String) -> Unit,
    onUpdateClip: (String, (TimelineClipItem) -> TimelineClipItem) -> Unit,
    onSeekPreview: (Long) -> Unit,
    onPickExternalAudio: () -> Unit,
    onClearExternalAudio: () -> Unit,
    onUpdateExternalAudioConfig: (Float, AudioFitMode) -> Unit,
    onUpdateExportSettings: ((MediaJobSettings) -> MediaJobSettings) -> Unit
) {
    when (tab) {
        VideoInspectorTab.TIMELINE -> {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                TimelineClipsCard(
                    clips = project.clips,
                    selectedClipId = selectedClip?.clipId,
                    onAddVideos = onAddVideos,
                    onSelectClip = onSelectClip,
                    onMoveClip = onMoveClip,
                    onRemoveClip = onRemoveClip
                )
                if (selectedClip != null) {
                    ClipTrimAndAudioCard(
                        clip = selectedClip,
                        simpleMode = simpleMode,
                        onUpdateClip = { transform -> onUpdateClip(selectedClip.clipId, transform) },
                        onSeekPreview = onSeekPreview
                    )
                }
                ExternalAudioTrackCard(
                    project = project,
                    onPickExternalAudio = onPickExternalAudio,
                    onClearExternalAudio = onClearExternalAudio,
                    onUpdateExternalAudioConfig = onUpdateExternalAudioConfig
                )
            }
        }

        VideoInspectorTab.FILTERS -> {
            if (selectedClip != null) {
                VideoFiltersAndEffectsCard(
                    clip = selectedClip,
                    simpleMode = simpleMode,
                    capabilities = capabilities,
                    onUpdateClip = { transform -> onUpdateClip(selectedClip.clipId, transform) }
                )
            }
        }

        VideoInspectorTab.EXPORT -> {
            VideoExportSettingsCard(
                settings = project.exportSettings,
                isAudioExtract = effectiveTool == StudioTool.VIDEO_EXTRACT_AUDIO,
                simpleMode = simpleMode,
                capabilities = capabilities,
                onUpdateSettings = onUpdateExportSettings
            )
        }
    }
}

@Composable
private fun TimelineClipsCard(
    clips: List<TimelineClipItem>,
    selectedClipId: String?,
    onAddVideos: () -> Unit,
    onSelectClip: (String) -> Unit,
    onMoveClip: (String, Boolean) -> Unit,
    onRemoveClip: (String) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Zaman Çizelgesi Klipleri (${clips.size})", style = MaterialTheme.typography.titleMedium)
                TextButton(onClick = onAddVideos) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Klip Ekle")
                }
            }

            clips.forEachIndexed { idx, clip ->
                val isSelected = clip.clipId == selectedClipId
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) else MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (isSelected) 1.5.dp else 0.5.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectClip(clip.clipId) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${idx + 1}. ${clip.input.displayName}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${MediaFormatUtils.formatDurationMs(clip.trimStartMs)} – ${MediaFormatUtils.formatDurationMs(clip.trimEndMs)} " +
                                    "(Etkin: ${MediaFormatUtils.formatDurationMs(clip.effectiveDurationMs)})",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (idx > 0) {
                            IconButton(onClick = { onMoveClip(clip.clipId, true) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Yukarı", modifier = Modifier.size(16.dp))
                            }
                        }
                        if (idx < clips.lastIndex) {
                            IconButton(onClick = { onMoveClip(clip.clipId, false) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Aşağı", modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(onClick = { onRemoveClip(clip.clipId) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Kaldır", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ClipTrimAndAudioCard(
    clip: TimelineClipItem,
    simpleMode: Boolean,
    onUpdateClip: ((TimelineClipItem) -> TimelineClipItem) -> Unit,
    onSeekPreview: (Long) -> Unit
) {
    val totalMs = clip.durationMs.coerceAtLeast(1_000L)
    val startFrac = (clip.trimStartMs.toFloat() / totalMs.toFloat()).coerceIn(0f, 1f)
    val endFrac = (clip.trimEndMs.toFloat() / totalMs.toFloat()).coerceIn(startFrac, 1f)

    var startSecInput by remember(clip.clipId, clip.trimStartMs) {
        mutableStateOf(String.format(Locale.US, "%.1f", clip.trimStartMs / 1000.0))
    }
    var endSecInput by remember(clip.clipId, clip.trimEndMs) {
        mutableStateOf(String.format(Locale.US, "%.1f", clip.trimEndMs / 1000.0))
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Kesme ve Klip Sesi", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "${MediaFormatUtils.formatDurationMs(clip.trimStartMs)} – ${MediaFormatUtils.formatDurationMs(clip.trimEndMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            RangeSlider(
                value = startFrac..endFrac,
                onValueChange = { r ->
                    val ns = (r.start * totalMs).roundToLong()
                    val ne = (r.endInclusive * totalMs).roundToLong().coerceAtLeast(ns + 250L).coerceAtMost(totalMs)
                    onUpdateClip { it.copy(trimStartMs = ns, trimEndMs = ne) }
                    onSeekPreview(ns)
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val ns = (clip.trimStartMs - 500L).coerceAtLeast(0L)
                        onUpdateClip { it.copy(trimStartMs = ns) }
                        onSeekPreview(ns)
                    }) { Text("Baş -0.5s") }
                    TextButton(onClick = {
                        val ns = (clip.trimStartMs + 500L).coerceAtMost(clip.trimEndMs - 250L)
                        onUpdateClip { it.copy(trimStartMs = ns) }
                        onSeekPreview(ns)
                    }) { Text("Baş +0.5s") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        val ne = (clip.trimEndMs - 500L).coerceAtLeast(clip.trimStartMs + 250L)
                        onUpdateClip { it.copy(trimEndMs = ne) }
                        onSeekPreview(ne)
                    }) { Text("Bit -0.5s") }
                    TextButton(onClick = {
                        val ne = (clip.trimEndMs + 500L).coerceAtMost(totalMs)
                        onUpdateClip { it.copy(trimEndMs = ne) }
                        onSeekPreview(ne)
                    }) { Text("Bit +0.5s") }
                }
            }

            if (!simpleMode) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = startSecInput,
                        onValueChange = { startSecInput = it },
                        label = { Text("Başlangıç (sn)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = endSecInput,
                        onValueChange = { endSecInput = it },
                        label = { Text("Bitiş (sn)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    FilledTonalButton(onClick = {
                        val sMs = ((startSecInput.toDoubleOrNull() ?: 0.0) * 1000.0).roundToLong()
                            .coerceIn(0L, totalMs - 250L)
                        val eMs = ((endSecInput.toDoubleOrNull() ?: (totalMs / 1000.0)) * 1000.0).roundToLong()
                            .coerceIn(sMs + 250L, totalMs)
                        onUpdateClip { it.copy(trimStartMs = sMs, trimEndMs = eMs) }
                        onSeekPreview(sMs)
                    }) {
                        Text("Uygula")
                    }
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Klip Sesini Sustur", style = MaterialTheme.typography.titleSmall)
                Switch(
                    checked = clip.muted,
                    onCheckedChange = { m -> onUpdateClip { it.copy(muted = m) } }
                )
            }

            if (!clip.muted) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Klip Ses Seviyesi", style = MaterialTheme.typography.bodySmall)
                    Text("${(clip.volume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = clip.volume,
                    onValueChange = { v -> onUpdateClip { it.copy(volume = v) } },
                    valueRange = 0f..2f
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ExternalAudioTrackCard(
    project: TimelineProject,
    onPickExternalAudio: () -> Unit,
    onClearExternalAudio: () -> Unit,
    onUpdateExternalAudioConfig: (Float, AudioFitMode) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text("Harici Ses Kanalı (Fon Müziği / Dublaj)", style = MaterialTheme.typography.titleMedium)

            if (project.externalAudioInput == null) {
                OutlinedButton(onClick = onPickExternalAudio, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Videoya Harici Ses Seç")
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = project.externalAudioInput.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        TextButton(onClick = onClearExternalAudio) {
                            Text("Kaldır")
                        }
                    }
                }

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Harici Ses Seviyesi", style = MaterialTheme.typography.titleSmall)
                    Text("${(project.externalAudioVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = project.externalAudioVolume,
                    onValueChange = { vol -> onUpdateExternalAudioConfig(vol, project.audioFitMode) },
                    valueRange = 0f..2f
                )

                Text("Ses Süre Uyum Modu", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioFitMode.entries.forEach { mode ->
                        FilterChip(
                            selected = project.audioFitMode == mode,
                            onClick = { onUpdateExternalAudioConfig(project.externalAudioVolume, mode) },
                            label = { Text(mode.labelTr) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoFiltersAndEffectsCard(
    clip: TimelineClipItem,
    simpleMode: Boolean,
    capabilities: EngineCapabilities,
    onUpdateClip: ((TimelineClipItem) -> TimelineClipItem) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Döndürme, Hız ve Renk Efektleri", style = MaterialTheme.typography.titleMedium)

            // Döndür / Çevir
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = { onUpdateClip { it.copy(rotateDegrees = (it.rotateDegrees + 90) % 360) } },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${clip.rotateDegrees}°")
                }
                FilterChip(
                    selected = clip.flipHorizontal,
                    onClick = { onUpdateClip { it.copy(flipHorizontal = !it.flipHorizontal) } },
                    label = { Text("Yatay") },
                    leadingIcon = { Icon(Icons.Rounded.Flip, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = clip.flipVertical,
                    onClick = { onUpdateClip { it.copy(flipVertical = !it.flipVertical) } },
                    label = { Text("Dikey") }
                )
            }

            // Hız
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Oynatma Hızı", style = MaterialTheme.typography.titleSmall)
                    Text("${String.format(Locale.US, "%.2f", clip.speed)}x", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = clip.speed,
                    onValueChange = { s ->
                        val stepped = (s * 20f).roundToInt() / 20f
                        onUpdateClip { it.copy(speed = stepped.coerceIn(0.25f, 2.0f)) }
                    },
                    valueRange = 0.25f..2.0f
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0.5f, 1.0f, 1.5f, 2.0f).forEach { spd ->
                        FilterChip(
                            selected = kotlin.math.abs(clip.speed - spd) < 0.04f,
                            onClick = { onUpdateClip { it.copy(speed = spd) } },
                            label = { Text("${spd}x") }
                        )
                    }
                }
            }

            // Renk Filtreleri
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = clip.grayscale,
                    onClick = { onUpdateClip { it.copy(grayscale = !it.grayscale, sepia = false) } },
                    label = { Text("Siyah-Beyaz") }
                )
                FilterChip(
                    selected = clip.sepia,
                    onClick = { onUpdateClip { it.copy(sepia = !it.sepia, grayscale = false) } },
                    label = { Text("Sepya") }
                )
            }

            if (!simpleMode) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Parlaklık", style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "%.2f", clip.brightness), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = clip.brightness,
                        onValueChange = { b -> onUpdateClip { it.copy(brightness = b) } },
                        valueRange = -1f..1f
                    )
                }
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Kontrast", style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "%.2f", clip.contrast), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = clip.contrast,
                        onValueChange = { c -> onUpdateClip { it.copy(contrast = c) } },
                        valueRange = 0.2f..2.0f
                    )
                }
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Doygunluk", style = MaterialTheme.typography.bodySmall)
                        Text(String.format(Locale.US, "%.2f", clip.saturation), style = MaterialTheme.typography.labelMedium)
                    }
                    Slider(
                        value = clip.saturation,
                        onValueChange = { s -> onUpdateClip { it.copy(saturation = s) } },
                        valueRange = 0f..3.0f
                    )
                }
            }

            // Filigran
            OutlinedTextField(
                value = clip.watermarkText,
                onValueChange = { txt -> onUpdateClip { it.copy(watermarkText = txt) } },
                label = { Text("Yazı / Filigran Katmanı (isteğe bağlı)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            // Dürüst Motor Sınırlama Rozetleri (sahte çalışan buton YASAK!)
            HorizontalDivider()
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Motor Tarafından Desteklenmeyen Efektler", style = MaterialTheme.typography.labelLarge)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = false,
                            enabled = capabilities.supportsCrossfade,
                            onClick = {},
                            label = {
                                Text(
                                    if (capabilities.supportsCrossfade) "Crossfade Geçişi" else "Crossfade (Desteklenmiyor)"
                                )
                            }
                        )
                        FilterChip(
                            selected = false,
                            enabled = capabilities.supportsReversePlayback,
                            onClick = {},
                            label = {
                                Text(
                                    if (capabilities.supportsReversePlayback) "Ters Oynatma" else "Ters Oynatma (Desteklenmiyor)"
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoExportSettingsCard(
    settings: MediaJobSettings,
    isAudioExtract: Boolean,
    simpleMode: Boolean,
    capabilities: EngineCapabilities,
    onUpdateSettings: ((MediaJobSettings) -> MediaJobSettings) -> Unit
) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (isAudioExtract) {
                Text("Çıkarılacak Ses Formatı", style = MaterialTheme.typography.titleMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("m4a", "wav", "opus", "flac").forEach { fmt ->
                        val supported = fmt in capabilities.supportedAudioOutputFormats
                        FilterChip(
                            selected = settings.outputFormat == fmt,
                            enabled = supported,
                            onClick = { onUpdateSettings { it.copy(outputFormat = fmt) } },
                            label = { Text(if (supported) fmt.uppercase() else "${fmt.uppercase()} (Yok)") }
                        )
                    }
                }
                Text(
                    text = if (settings.outputFormat == "wav") {
                        "WAV: Sıkıştırılmamış kayıpsız ses çıkarır."
                    } else {
                        "M4A (AAC): Tüm telefon ve oynatıcılarla tam uyumludur."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text("Çıktı Formatı ve Video Kodek", style = MaterialTheme.typography.titleMedium)

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("mp4", "webm").forEach { fmt ->
                        val enabled = fmt in capabilities.supportedVideoOutputFormats
                        FilterChip(
                            selected = settings.outputFormat == fmt,
                            enabled = enabled,
                            onClick = {
                                onUpdateSettings {
                                    val nextCodec = if (fmt == "webm") "video/x-vnd.on2.vp9" else "video/avc"
                                    it.copy(outputFormat = fmt, videoCodec = nextCodec)
                                }
                            },
                            label = { Text(if (enabled) fmt.uppercase() else "${fmt.uppercase()} (Desteklenmiyor)") }
                        )
                    }
                }

                val codecOptions = listOf(
                    Triple("video/avc", "H.264 (AVC)", "H.264: Tüm telefon ve TV'lerle en uyumlu format"),
                    Triple("video/hevc", "H.265 (HEVC)", "HEVC: Aynı kalitede daha küçük dosya boyutu"),
                    Triple("video/x-vnd.on2.vp9", "VP9 (WebM)", "VP9: Açık kaynak web ve WebM video kodeği")
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    codecOptions.forEach { (mime, label, _) ->
                        val supported = when (mime) {
                            "video/hevc" -> capabilities.supportsHevcEncode
                            "video/x-vnd.on2.vp9" -> capabilities.supportsVp9Encode
                            else -> true
                        }
                        FilterChip(
                            selected = settings.videoCodec == mime,
                            enabled = supported,
                            onClick = {
                                onUpdateSettings {
                                    val nextFmt = if (mime == "video/x-vnd.on2.vp9") "webm" else "mp4"
                                    it.copy(videoCodec = mime, outputFormat = nextFmt)
                                }
                            },
                            label = { Text(if (supported) label else "$label (Yok)") }
                        )
                    }
                }
                codecOptions.firstOrNull { it.first == settings.videoCodec }?.third?.let { desc ->
                    Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Text("Kalite Profili", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QualityProfile.entries.forEach { profile ->
                        FilterChip(
                            selected = settings.qualityProfile == profile,
                            onClick = { onUpdateSettings { it.copy(qualityProfile = profile) } },
                            label = { Text(profile.labelTr) }
                        )
                    }
                }

                Text("Çözünürlük", style = MaterialTheme.typography.titleSmall)
                val resPresets = listOf(
                    "Orijinal" to (null to null),
                    "1080p" to (1920 to 1080),
                    "720p" to (1280 to 720),
                    "480p" to (854 to 480)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    resPresets.forEach { (label, dims) ->
                        val isSelected = settings.targetWidth == dims.first && settings.targetHeight == dims.second
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                onUpdateSettings { it.copy(targetWidth = dims.first, targetHeight = dims.second) }
                            },
                            label = { Text(label) }
                        )
                    }
                }

                if (!simpleMode) {
                    Text("En-Boy Oranı", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AspectRatioOption.entries.forEach { ratio ->
                            FilterChip(
                                selected = settings.aspectRatio == ratio,
                                onClick = { onUpdateSettings { it.copy(aspectRatio = ratio) } },
                                label = { Text(ratio.labelTr) }
                            )
                        }
                    }

                    Text("Kare Hızı (FPS)", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(null to "Orijinal", 24 to "24", 30 to "30", 60 to "60").forEach { (fps, label) ->
                            FilterChip(
                                selected = settings.targetFps == fps,
                                onClick = { onUpdateSettings { it.copy(targetFps = fps) } },
                                label = { Text(label) }
                            )
                        }
                    }

                    Text("Özel Video Bit Hızı", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            null to "Otomatik",
                            1_200_000 to "1.2 Mbps",
                            2_600_000 to "2.6 Mbps",
                            5_000_000 to "5 Mbps",
                            8_000_000 to "8 Mbps"
                        ).forEach { (bps, label) ->
                            FilterChip(
                                selected = settings.videoBitrateBps == bps,
                                onClick = { onUpdateSettings { it.copy(videoBitrateBps = bps) } },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            }

            OutlinedTextField(
                value = settings.outputFileName,
                onValueChange = { name -> onUpdateSettings { it.copy(outputFileName = name) } },
                label = { Text("Çıktı Dosya Adı (isteğe bağlı)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
