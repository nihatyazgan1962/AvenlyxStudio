package com.formatci.mediastudio.feature.audio

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forward5
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay5
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.formatci.mediastudio.core.designsystem.LocalStudioColors
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaJobSettings
import com.formatci.mediastudio.domain.model.StudioTool
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@Composable
fun AudioStudioScreen(
    audioInputs: List<MediaInfo>,
    focusedAudio: MediaInfo?,
    settings: MediaJobSettings,
    simpleMode: Boolean,
    capabilities: EngineCapabilities,
    estimate: JobEstimate?,
    onAddAudios: () -> Unit,
    onSelectFocusedAudio: (String) -> Unit,
    onMoveAudio: (String, Boolean) -> Unit,
    onRemoveAudio: (String) -> Unit,
    onUpdateSettings: ((MediaJobSettings) -> MediaJobSettings) -> Unit,
    onStartAudioJob: (StudioTool) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build()
    }
    var isPlaying by remember { mutableStateOf(false) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var playerDurationMs by remember { mutableLongStateOf(0L) }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (exoPlayer.duration > 0) {
                    playerDurationMs = exoPlayer.duration
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
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

    LaunchedEffect(focusedAudio?.input?.uriString) {
        val uriStr = focusedAudio?.input?.uriString
        if (uriStr.isNullOrBlank()) {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
            currentPosMs = 0L
            playerDurationMs = 0L
        } else {
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(uriStr)))
            exoPlayer.prepare()
            currentPosMs = 0L
            playerDurationMs = focusedAudio.durationMs
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        while (isPlaying) {
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            if (exoPlayer.duration > 0L) {
                playerDurationMs = exoPlayer.duration
            }
            delay(80L)
        }
    }

    val effectiveDurationMs = max(focusedAudio?.durationMs ?: 0L, playerDurationMs).coerceAtLeast(1_000L)
    val trimStartMs = settings.trimStartMs.coerceIn(0L, effectiveDurationMs)
    val trimEndMs = (settings.trimEndMs ?: effectiveDurationMs).coerceIn(trimStartMs, effectiveDurationMs)

    val effectiveTool = if (audioInputs.size > 1 || trimStartMs > 0L || trimEndMs < effectiveDurationMs ||
        settings.fadeInMs > 0L || settings.fadeOutMs > 0L || settings.audioVolume != 1.0f
    ) {
        StudioTool.AUDIO_EDIT_MERGE
    } else {
        StudioTool.AUDIO_CONVERT
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp

        Column(modifier = Modifier.fillMaxSize()) {
            if (audioInputs.isEmpty()) {
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
                                imageVector = Icons.Rounded.GraphicEq,
                                contentDescription = null,
                                tint = LocalStudioColors.current.audioAccent,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = "Ses Stüdyosu Boş",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "M4A/AAC, MP3, WAV, OGG, FLAC veya OPUS ses dosyalarını seçerek dalga biçimi üzerinde kesebilir, dönüştürebilir, birleştirebilir veya miksleyebilirsiniz.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onAddAudios) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Ses Dosyası Seç")
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
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            AudioPlayerAndWaveformCard(
                                focusedAudio = focusedAudio,
                                isPlaying = isPlaying,
                                currentPosMs = currentPosMs,
                                durationMs = effectiveDurationMs,
                                trimStartMs = trimStartMs,
                                trimEndMs = trimEndMs,
                                onTogglePlay = {
                                    if (exoPlayer.isPlaying) {
                                        exoPlayer.pause()
                                    } else {
                                        if (exoPlayer.currentPosition >= effectiveDurationMs - 150L) {
                                            exoPlayer.seekTo(trimStartMs)
                                        }
                                        exoPlayer.play()
                                    }
                                },
                                onSeekTo = { target ->
                                    val clamped = target.coerceIn(0L, effectiveDurationMs)
                                    exoPlayer.seekTo(clamped)
                                    currentPosMs = clamped
                                },
                                onTrimChange = { start, end ->
                                    onUpdateSettings { it.copy(trimStartMs = start, trimEndMs = end) }
                                }
                            )
                        }
                        item {
                            AudioTracksListCard(
                                audioInputs = audioInputs,
                                focusedId = focusedAudio?.input?.id,
                                mixInsteadOfConcat = settings.mixAudioInsteadOfConcat,
                                onAddAudios = onAddAudios,
                                onSelectFocused = onSelectFocusedAudio,
                                onMoveAudio = onMoveAudio,
                                onRemoveAudio = onRemoveAudio,
                                onToggleMixMode = { mix ->
                                    onUpdateSettings { it.copy(mixAudioInsteadOfConcat = mix) }
                                }
                            )
                        }
                    }

                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            AudioFormatAndEffectsCard(
                                settings = settings,
                                simpleMode = simpleMode,
                                capabilities = capabilities,
                                hasMultipleAudios = audioInputs.size >= 2,
                                onUpdateSettings = onUpdateSettings
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        AudioPlayerAndWaveformCard(
                            focusedAudio = focusedAudio,
                            isPlaying = isPlaying,
                            currentPosMs = currentPosMs,
                            durationMs = effectiveDurationMs,
                            trimStartMs = trimStartMs,
                            trimEndMs = trimEndMs,
                            onTogglePlay = {
                                if (exoPlayer.isPlaying) {
                                    exoPlayer.pause()
                                } else {
                                    if (exoPlayer.currentPosition >= effectiveDurationMs - 150L) {
                                        exoPlayer.seekTo(trimStartMs)
                                    }
                                    exoPlayer.play()
                                }
                            },
                            onSeekTo = { target ->
                                val clamped = target.coerceIn(0L, effectiveDurationMs)
                                exoPlayer.seekTo(clamped)
                                currentPosMs = clamped
                            },
                            onTrimChange = { start, end ->
                                onUpdateSettings { it.copy(trimStartMs = start, trimEndMs = end) }
                            }
                        )
                    }
                    item {
                        AudioTracksListCard(
                            audioInputs = audioInputs,
                            focusedId = focusedAudio?.input?.id,
                            mixInsteadOfConcat = settings.mixAudioInsteadOfConcat,
                            onAddAudios = onAddAudios,
                            onSelectFocused = onSelectFocusedAudio,
                            onMoveAudio = onMoveAudio,
                            onRemoveAudio = onRemoveAudio,
                            onToggleMixMode = { mix ->
                                onUpdateSettings { it.copy(mixAudioInsteadOfConcat = mix) }
                            }
                        )
                    }
                    item {
                        AudioFormatAndEffectsCard(
                            settings = settings,
                            simpleMode = simpleMode,
                            capabilities = capabilities,
                            hasMultipleAudios = audioInputs.size >= 2,
                            onUpdateSettings = onUpdateSettings
                        )
                    }
                }
            }

            // Tek elle ulaşılabilir alt aksiyon barı
            if (audioInputs.isNotEmpty()) {
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
                            Text(
                                text = "Tahmini Çıktı: $estSize (${settings.outputFormat.uppercase()})",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = estimate?.disclaimerTr ?: "Bu değer yaklaşık bir tahmindir.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = { onStartAudioJob(effectiveTool) }) {
                            Text(
                                if (audioInputs.size > 1) {
                                    if (settings.mixAudioInsteadOfConcat) "Miksle" else "Birleştir"
                                } else {
                                    "Dönüştür"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPlayerAndWaveformCard(
    focusedAudio: MediaInfo?,
    isPlaying: Boolean,
    currentPosMs: Long,
    durationMs: Long,
    trimStartMs: Long,
    trimEndMs: Long,
    onTogglePlay: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onTrimChange: (Long, Long) -> Unit
) {
    val studioColors = LocalStudioColors.current
    val peaks = focusedAudio?.waveformPeaks.orEmpty()
    val startFraction = (trimStartMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val endFraction = (trimEndMs.toFloat() / durationMs.toFloat()).coerceIn(startFraction, 1f)
    val playFraction = (currentPosMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    val primaryColor = MaterialTheme.colorScheme.primary

    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = focusedAudio?.input?.displayName ?: "Ses Dosyası",
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (focusedAudio != null) {
                        Text(
                            text = "${focusedAudio.audioCodec ?: focusedAudio.detectedFormat.uppercase()} • " +
                                "${focusedAudio.sampleRateHz} Hz • ${focusedAudio.channelCount} ch • ${focusedAudio.formattedSize}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Gerçek waveformPeaks Canvas dalga biçimi
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(104.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .pointerInput(durationMs) {
                        detectTapGestures { offset ->
                            val ratio = (offset.x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            onSeekTo((durationMs * ratio).roundToLong())
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                if (peaks.isEmpty()) {
                    Text(
                        text = "Dalga biçimi verisi bulunamadı (yine de zaman çizelgesinden kesilebilir)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                } else {
                    Canvas(modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp)) {
                        val count = peaks.size
                        val stepW = size.width / count.toFloat()
                        val barW = (stepW * 0.65f).coerceAtLeast(2f)
                        val midY = size.height / 2f

                        peaks.forEachIndexed { idx, amp ->
                            val normIdx = idx.toFloat() / max(1, count - 1)
                            val inSelection = normIdx in startFraction..endFraction
                            val barColor = if (inSelection) {
                                studioColors.waveformActive
                            } else {
                                studioColors.waveformInactive
                            }
                            val h = (amp.coerceIn(0.06f, 1f) * size.height * 0.9f)
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(idx * stepW, midY - h / 2f),
                                size = Size(barW, h),
                                cornerRadius = CornerRadius(3f, 3f)
                            )
                        }

                        // Playhead çizgisi
                        val playX = playFraction * size.width
                        drawLine(
                            color = primaryColor,
                            start = Offset(playX, 0f),
                            end = Offset(playX, size.height),
                            strokeWidth = 4f
                        )
                    }
                }
            }

            // Oynatma kontrolleri
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = MediaFormatUtils.formatDurationMs(currentPosMs),
                    style = MaterialTheme.typography.labelLarge
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { onSeekTo(currentPosMs - 5_000L) }) {
                        Icon(Icons.Rounded.Replay5, contentDescription = "5 saniye geri")
                    }
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .size(46.dp)
                            .clickable(onClick = onTogglePlay)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Duraklat" else "Oynat",
                                tint = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    IconButton(onClick = { onSeekTo(currentPosMs + 5_000L) }) {
                        Icon(Icons.Rounded.Forward5, contentDescription = "5 saniye ileri")
                    }
                }
                Text(
                    text = MediaFormatUtils.formatDurationMs(durationMs),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            HorizontalDivider()

            // Kesme RangeSlider
            Text(
                text = "Kesme Aralığı: ${MediaFormatUtils.formatDurationMs(trimStartMs)} — ${MediaFormatUtils.formatDurationMs(trimEndMs)}",
                style = MaterialTheme.typography.titleSmall
            )
            RangeSlider(
                value = startFraction..endFraction,
                onValueChange = { range ->
                    val newStart = (range.start * durationMs).roundToLong()
                    val newEnd = (range.endInclusive * durationMs).roundToLong().coerceAtLeast(newStart + 250L)
                    onTrimChange(newStart, newEnd.coerceAtMost(durationMs))
                }
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        onTrimChange((trimStartMs - 500L).coerceAtLeast(0L), trimEndMs)
                    }) { Text("Baş -0.5s") }
                    TextButton(onClick = {
                        onTrimChange((trimStartMs + 500L).coerceAtMost(trimEndMs - 250L), trimEndMs)
                    }) { Text("Baş +0.5s") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        onTrimChange(trimStartMs, (trimEndMs - 500L).coerceAtLeast(trimStartMs + 250L))
                    }) { Text("Bit -0.5s") }
                    TextButton(onClick = {
                        onTrimChange(trimStartMs, (trimEndMs + 500L).coerceAtMost(durationMs))
                    }) { Text("Bit +0.5s") }
                }
            }
        }
    }
}

@Composable
private fun AudioTracksListCard(
    audioInputs: List<MediaInfo>,
    focusedId: String?,
    mixInsteadOfConcat: Boolean,
    onAddAudios: () -> Unit,
    onSelectFocused: (String) -> Unit,
    onMoveAudio: (String, Boolean) -> Unit,
    onRemoveAudio: (String) -> Unit,
    onToggleMixMode: (Boolean) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Ses Parçaları (${audioInputs.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onAddAudios) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Ses Ekle")
                }
            }

            if (audioInputs.size >= 2) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = !mixInsteadOfConcat,
                        onClick = { onToggleMixMode(false) },
                        label = { Text("Art Arda Birleştir") }
                    )
                    FilterChip(
                        selected = mixInsteadOfConcat,
                        onClick = { onToggleMixMode(true) },
                        label = { Text("İki Sesi Miksle") }
                    )
                }
            }

            audioInputs.forEachIndexed { idx, info ->
                val isFocused = info.input.id == focusedId
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isFocused) {
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            width = if (isFocused) 1.5.dp else 0.5.dp,
                            color = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                            shape = RoundedCornerShape(10.dp)
                        )
                        .clickable { onSelectFocused(info.input.id) }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AudioFile,
                            contentDescription = null,
                            tint = LocalStudioColors.current.audioAccent
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${idx + 1}. ${info.input.displayName}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${info.formattedDuration} • ${info.formattedSize}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (idx > 0) {
                            IconButton(onClick = { onMoveAudio(info.input.id, true) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Yukarı taşı", modifier = Modifier.size(18.dp))
                            }
                        }
                        if (idx < audioInputs.lastIndex) {
                            IconButton(onClick = { onMoveAudio(info.input.id, false) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Aşağı taşı", modifier = Modifier.size(18.dp))
                            }
                        }
                        IconButton(onClick = { onRemoveAudio(info.input.id) }, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Kaldır", modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AudioFormatAndEffectsCard(
    settings: MediaJobSettings,
    simpleMode: Boolean,
    capabilities: EngineCapabilities,
    hasMultipleAudios: Boolean,
    onUpdateSettings: ((MediaJobSettings) -> MediaJobSettings) -> Unit
) {
    val allAudioFormats = listOf(
        "m4a" to "M4A (AAC): Tüm telefonlarda en uyumlu format",
        "wav" to "WAV (PCM): Kayıpsız stüdyo kalitesi",
        "opus" to "OPUS: Düşük boyutta yüksek ses netliği",
        "flac" to "FLAC: Kayıpsız sıkıştırılmış arşiv"
    )

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Çıktı Formatı ve Ses Ayarları",
                style = MaterialTheme.typography.titleMedium
            )

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                allAudioFormats.forEach { (fmt, _) ->
                    val supported = fmt in capabilities.supportedAudioOutputFormats
                    FilterChip(
                        selected = settings.outputFormat == fmt,
                        enabled = supported,
                        onClick = {
                            if (supported) {
                                onUpdateSettings { it.copy(outputFormat = fmt) }
                            }
                        },
                        label = {
                            Text(if (supported) fmt.uppercase() else "${fmt.uppercase()} (Cihazda yok)")
                        }
                    )
                }
            }

            val desc = allAudioFormats.firstOrNull { it.first == settings.outputFormat }?.second
            if (desc != null) {
                Text(
                    text = desc,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Ses Seviyesi
            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Ses Seviyesi", style = MaterialTheme.typography.titleSmall)
                    Text("${(settings.audioVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = settings.audioVolume,
                    onValueChange = { vol -> onUpdateSettings { it.copy(audioVolume = vol) } },
                    valueRange = 0f..2f
                )
            }

            if (hasMultipleAudios && settings.mixAudioInsteadOfConcat) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("2. Ses Seviyesi (Miks)", style = MaterialTheme.typography.titleSmall)
                        Text("${(settings.secondaryAudioVolume * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = settings.secondaryAudioVolume,
                        onValueChange = { vol -> onUpdateSettings { it.copy(secondaryAudioVolume = vol) } },
                        valueRange = 0f..2f
                    )
                }
            }

            // Fade In / Out
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Fade-In: ${settings.fadeInMs / 1000f} sn", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = settings.fadeInMs.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(fadeInMs = (v / 500f).roundToInt() * 500L) } },
                        valueRange = 0f..5000f
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Fade-Out: ${settings.fadeOutMs / 1000f} sn", style = MaterialTheme.typography.bodySmall)
                    Slider(
                        value = settings.fadeOutMs.toFloat(),
                        onValueChange = { v -> onUpdateSettings { it.copy(fadeOutMs = (v / 500f).roundToInt() * 500L) } },
                        valueRange = 0f..5000f
                    )
                }
            }

            if (!simpleMode) {
                HorizontalDivider()

                if (settings.outputFormat != "wav") {
                    Text("Bit Hızı (Bitrate)", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(96_000, 128_000, 192_000, 256_000, 320_000).forEach { bps ->
                            FilterChip(
                                selected = settings.audioBitrateBps == bps,
                                onClick = { onUpdateSettings { it.copy(audioBitrateBps = bps) } },
                                label = { Text("${bps / 1000} kbps") }
                            )
                        }
                    }
                }

                Text("Örnekleme Hızı", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(22_050, 44_100, 48_000).forEach { hz ->
                        FilterChip(
                            selected = settings.audioSampleRateHz == hz,
                            onClick = { onUpdateSettings { it.copy(audioSampleRateHz = hz) } },
                            label = { Text("$hz Hz") }
                        )
                    }
                }

                Text("Kanal Yapısı", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = settings.audioChannels == 2,
                        onClick = { onUpdateSettings { it.copy(audioChannels = 2) } },
                        label = { Text("Stereo (2 Kanal)") }
                    )
                    FilterChip(
                        selected = settings.audioChannels == 1,
                        onClick = { onUpdateSettings { it.copy(audioChannels = 1) } },
                        label = { Text("Mono (1 Kanal)") }
                    )
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
}
