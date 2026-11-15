package com.formatci.mediastudio.feature.home

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.ContentCut
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.MovieCreation
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Queue
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoFile
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.formatci.mediastudio.core.designsystem.LocalStudioColors
import com.formatci.mediastudio.domain.model.AppSettings
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.StudioTool

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    settings: AppSettings,
    capabilities: EngineCapabilities,
    jobs: List<MediaJob>,
    recentFiles: List<MediaInput>,
    availableStorageBytes: Long,
    onToggleSimpleMode: (Boolean) -> Unit,
    onToolClick: (StudioTool) -> Unit,
    onOpenPicker: () -> Unit,
    onOpenPhotoPicker: () -> Unit,
    onOpenQueue: () -> Unit,
    onRecentFileClick: (MediaInput) -> Unit,
    modifier: Modifier = Modifier
) {
    val studioColors = LocalStudioColors.current
    var showCapabilitiesDialog by remember { mutableStateOf(false) }

    val activeJobs = remember(jobs) { jobs.filter { it.status.isActive } }
    val runningJob = remember(activeJobs) { activeJobs.firstOrNull { !it.isIndeterminate || it.progress > 0f } ?: activeJobs.firstOrNull() }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp
        val toolColumns = when {
            maxWidth >= 1100.dp -> 4
            maxWidth >= 840.dp -> 3
            else -> 2
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Üst Başlık + Basit/Gelişmiş Mod Geçişi
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Avenlyx Studio",
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.onBackground
                            )
                            Text(
                                text = "Çevrimdışı cihaz içi video, ses ve resim stüdyosu",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "Çevrimdışı",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilterChip(
                            selected = settings.simpleMode,
                            onClick = { onToggleSimpleMode(true) },
                            label = { Text("Basit Mod") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        FilterChip(
                            selected = !settings.simpleMode,
                            onClick = { onToggleSimpleMode(false) },
                            label = { Text("Gelişmiş Ayarlar") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Rounded.Tune,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        if (availableStorageBytes > 0L) {
                            Text(
                                text = "Boş: ${MediaFormatUtils.formatBytes(availableStorageBytes)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // 2. Cihaz Kodek Özeti & Devam Eden İşler (Tablette yan yana 2 sütun!)
            item {
                if (isWide) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CapabilitySummaryCard(
                            capabilities = capabilities,
                            onShowDetails = { showCapabilitiesDialog = true },
                            modifier = Modifier.weight(1f)
                        )
                        ActiveQueueOrQuickStartCard(
                            activeJobsCount = activeJobs.size,
                            runningJob = runningJob,
                            onOpenQueue = onOpenQueue,
                            onOpenPicker = onOpenPicker,
                            onOpenPhotoPicker = onOpenPhotoPicker,
                            modifier = Modifier.weight(1f)
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActiveQueueOrQuickStartCard(
                            activeJobsCount = activeJobs.size,
                            runningJob = runningJob,
                            onOpenQueue = onOpenQueue,
                            onOpenPicker = onOpenPicker,
                            onOpenPhotoPicker = onOpenPhotoPicker,
                            modifier = Modifier.fillMaxWidth()
                        )
                        CapabilitySummaryCard(
                            capabilities = capabilities,
                            onShowDetails = { showCapabilitiesDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // 3. Son İçe Aktarılan Dosyalar
            if (recentFiles.isNotEmpty()) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Son İçe Aktarılanlar",
                                style = MaterialTheme.typography.titleMedium
                            )
                            TextButton(onClick = onOpenPicker) {
                                Text("Tümünü Aç")
                            }
                        }
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(recentFiles, key = { it.id }) { item ->
                                RecentMediaChipCard(
                                    input = item,
                                    onClick = { onRecentFileClick(item) }
                                )
                            }
                        }
                    }
                }
            }

            // 4. 12 Stüdyo Aracı Başlığı
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "Stüdyo Araçları (12 Araç)",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Yapmak istediğiniz işlemi seçin; dosya seçip ayarları tek ekranda tamamlayın.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 5. 12 Araç Kartları Gridi
            val rows = StudioTool.entries.chunked(toolColumns)
            items(rows) { rowTools ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    rowTools.forEach { tool ->
                        val accent = when (tool) {
                            StudioTool.BATCH_CONVERT -> studioColors.batchAccent
                            else -> when (tool.mediaType) {
                                MediaType.VIDEO -> studioColors.videoAccent
                                MediaType.AUDIO -> studioColors.audioAccent
                                MediaType.IMAGE -> studioColors.imageAccent
                                MediaType.UNKNOWN -> studioColors.batchAccent
                            }
                        }
                        StudioToolCard(
                            tool = tool,
                            accentColor = accent,
                            capabilities = capabilities,
                            onClick = { onToolClick(tool) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    repeat(toolColumns - rowTools.size) {
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }

            // 6. Dürüst Motor Sınırlamaları Notu
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                    ),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Motor Desteği ve Şeffaf Sınırlar",
                                style = MaterialTheme.typography.titleSmall
                            )
                            val crossfadeText = if (capabilities.supportsCrossfade) {
                                "Klip geçişi (Crossfade) destekleniyor."
                            } else {
                                "Klip geçiş efekti (Crossfade) ve ters oynatma bu Media3 motor sürümünde henüz desteklenmiyor — arayüzde devre dışı gösterilir."
                            }
                            Text(
                                text = crossfadeText,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCapabilitiesDialog) {
        CapabilitiesDetailDialog(
            capabilities = capabilities,
            onDismiss = { showCapabilitiesDialog = false }
        )
    }
}

@Composable
private fun ActiveQueueOrQuickStartCard(
    activeJobsCount: Int,
    runningJob: MediaJob?,
    onOpenQueue: () -> Unit,
    onOpenPicker: () -> Unit,
    onOpenPhotoPicker: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (activeJobsCount > 0 && runningJob != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Queue,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Devam Eden İşler ($activeJobsCount)",
                            style = MaterialTheme.typography.titleMedium
                        )
                    }
                    Text(
                        text = if (runningJob.isIndeterminate) "Hazırlanıyor" else "${(runningJob.progress * 100).toInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Text(
                    text = runningJob.title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (runningJob.isIndeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { runningJob.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                Text(
                    text = runningJob.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(onClick = onOpenQueue, modifier = Modifier.weight(1f)) {
                        Text("Kuyruğu Aç")
                    }
                    OutlinedButton(onClick = onOpenPicker, modifier = Modifier.weight(1f)) {
                        Text("Dosya Ekle")
                    }
                }
            } else {
                Text(
                    text = "Hızlı Medya İçe Aktar",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Galeri veya dosya yöneticisinden video, ses ve fotoğraf seçerek anında düzenlemeye başlayın.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onOpenPhotoPicker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AddPhotoAlternate,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Galeri", maxLines = 1)
                    }
                    FilledTonalButton(
                        onClick = onOpenPicker,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Dosya Seç", maxLines = 1)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CapabilitySummaryCard(
    capabilities: EngineCapabilities,
    onShowDetails: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = "Cihaz Kodek Özeti",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
                TextButton(onClick = onShowDetails) {
                    Text("Detay")
                }
            }

            if (capabilities.deviceSummaryTr.isNotBlank()) {
                Text(
                    text = capabilities.deviceSummaryTr,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CodecBadge("H.264", true)
                CodecBadge("HEVC", capabilities.supportsHevcEncode)
                CodecBadge("VP9", capabilities.supportsVp9Encode)
                CodecBadge("AAC", true)
                CodecBadge("WAV", true)
                CodecBadge("OPUS", capabilities.supportsOpusEncode)
                CodecBadge("FLAC", capabilities.supportsFlacEncode)
            }
        }
    }
}

@Composable
private fun CodecBadge(label: String, supported: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (supported) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        }
    ) {
        Text(
            text = if (supported) "$label ✓" else "$label ✕",
            style = MaterialTheme.typography.labelMedium,
            color = if (supported) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun RecentMediaChipCard(
    input: MediaInput,
    onClick: () -> Unit
) {
    val colors = LocalStudioColors.current
    val (icon, tint) = when (input.mediaType) {
        MediaType.VIDEO -> Icons.Rounded.VideoFile to colors.videoAccent
        MediaType.AUDIO -> Icons.Rounded.AudioFile to colors.audioAccent
        MediaType.IMAGE -> Icons.Rounded.PhotoLibrary to colors.imageAccent
        MediaType.UNKNOWN -> Icons.Rounded.FolderOpen to MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .width(190.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(tint.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = input.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = "${input.mediaType.labelTr} • ${MediaFormatUtils.formatBytes(input.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StudioToolCard(
    tool: StudioTool,
    accentColor: Color,
    capabilities: EngineCapabilities,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = toolIcon(tool)
    val note = when {
        tool == StudioTool.VIDEO_EDIT && !capabilities.supportsCrossfade -> "Crossfade/Ters oynatma yok"
        tool == StudioTool.AUDIO_CONVERT && !capabilities.supportsOpusEncode && !capabilities.supportsFlacEncode -> "M4A & WAV garantili"
        else -> null
    }

    Card(
        modifier = modifier.clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
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
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(accentColor.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = tool.titleTr,
                        tint = accentColor,
                        modifier = Modifier.size(22.dp)
                    )
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = accentColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = if (tool == StudioTool.BATCH_CONVERT) "Toplu" else tool.mediaType.labelTr,
                        style = MaterialTheme.typography.labelSmall,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
            }

            Text(
                text = tool.titleTr,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Text(
                text = tool.subtitleTr,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            if (note != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(13.dp)
                    )
                    Text(
                        text = note,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

private fun toolIcon(tool: StudioTool): ImageVector = when (tool) {
    StudioTool.VIDEO_CONVERT -> Icons.Rounded.SwapHoriz
    StudioTool.VIDEO_EDIT -> Icons.Rounded.Tune
    StudioTool.VIDEO_MERGE -> Icons.Rounded.MovieCreation
    StudioTool.VIDEO_TRIM_SPLIT -> Icons.Rounded.ContentCut
    StudioTool.VIDEO_EXTRACT_AUDIO -> Icons.Rounded.MusicNote
    StudioTool.VIDEO_ADD_AUDIO -> Icons.Rounded.LibraryMusic
    StudioTool.AUDIO_CONVERT -> Icons.Rounded.GraphicEq
    StudioTool.AUDIO_EDIT_MERGE -> Icons.Rounded.AudioFile
    StudioTool.IMAGE_CONVERT -> Icons.Rounded.PhotoLibrary
    StudioTool.IMAGE_COMPRESS -> Icons.Rounded.Compress
    StudioTool.SLIDESHOW_MAKER -> Icons.Rounded.Slideshow
    StudioTool.BATCH_CONVERT -> Icons.Rounded.BurstMode
}

@Composable
fun CapabilitiesDetailDialog(
    capabilities: EngineCapabilities,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Cihaz Kodek ve Motor Yetenekleri") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    Text(
                        text = capabilities.deviceSummaryTr.ifBlank {
                            "Cihazın yerel MediaCodec kodlayıcıları tarandı."
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Desteklenen Çıktılar:\n• Video: ${capabilities.supportedVideoOutputFormats.joinToString(", ").uppercase()}\n" +
                            "• Ses: ${capabilities.supportedAudioOutputFormats.joinToString(", ").uppercase()}\n" +
                            "• Resim: ${capabilities.supportedImageOutputFormats.joinToString(", ").uppercase()}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                }
                items(capabilities.codecDetails) { codec ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = codec.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = buildString {
                                    append(if (codec.isEncoderAvailable) "Kodlayıcı ✓" else "Kodlayıcı ✕")
                                    if (codec.isHardwareAccelerated) append(" • Donanım")
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (codec.isEncoderAvailable) {
                                    MaterialTheme.colorScheme.secondary
                                } else {
                                    MaterialTheme.colorScheme.error
                                }
                            )
                        }
                        if (codec.noteTr.isNotBlank()) {
                            Text(
                                text = codec.noteTr,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(top = 6.dp))
                    }
                }
                item {
                    Text(
                        text = "Bilinen Motor Sınırları:\n" +
                            "• Crossfade (Klip geçiş efekti): ${if (capabilities.supportsCrossfade) "Destekleniyor" else "Desteklenmiyor"}\n" +
                            "• Ters Oynatma: ${if (capabilities.supportsReversePlayback) "Destekleniyor" else "Desteklenmiyor"}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}
