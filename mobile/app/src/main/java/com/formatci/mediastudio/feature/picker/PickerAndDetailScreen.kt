package com.formatci.mediastudio.feature.picker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.formatci.mediastudio.core.designsystem.LocalStudioColors
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.StudioTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToLong

@Composable
fun PickerAndDetailScreen(
    importedMedia: List<MediaInfo>,
    selectedMediaIds: Set<String>,
    focusedMedia: MediaInfo?,
    recentFiles: List<MediaInput>,
    isImporting: Boolean,
    onOpenPhotoPicker: () -> Unit,
    onOpenDocumentPicker: () -> Unit,
    onFocusMedia: (String) -> Unit,
    onToggleSelectMedia: (String) -> Unit,
    onRemoveMedia: (String) -> Unit,
    onOpenWithTool: (MediaInfo, StudioTool) -> Unit,
    onEnqueueBatch: () -> Unit,
    onExtractFrame: (uriString: String, positionMs: Long, sourceName: String) -> Unit,
    onImportRecent: (MediaInput) -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Üst seçici aksiyon çubuğu
            Surface(
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
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
                            Text("Fotoğraf / Video", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        FilledTonalButton(
                            onClick = onOpenDocumentPicker,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.FolderOpen,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tüm Dosyalar (SAF)", maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (isImporting) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }

            if (importedMedia.isEmpty()) {
                EmptyPickerState(
                    recentFiles = recentFiles,
                    onOpenPhotoPicker = onOpenPhotoPicker,
                    onOpenDocumentPicker = onOpenDocumentPicker,
                    onImportRecent = onImportRecent,
                    modifier = Modifier.weight(1f)
                )
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
                            .weight(0.95f)
                            .fillMaxHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(importedMedia, key = { it.input.id }) { info ->
                            ImportedMediaRowCard(
                                info = info,
                                isFocused = focusedMedia?.input?.id == info.input.id,
                                isSelected = info.input.id in selectedMediaIds,
                                onClick = { onFocusMedia(info.input.id) },
                                onToggleSelect = { onToggleSelectMedia(info.input.id) },
                                onRemove = { onRemoveMedia(info.input.id) }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1.25f)
                            .fillMaxHeight()
                    ) {
                        if (focusedMedia != null) {
                            MediaDetailCard(
                                info = focusedMedia,
                                onOpenWithTool = { tool -> onOpenWithTool(focusedMedia, tool) },
                                onExtractFrame = { posMs ->
                                    onExtractFrame(
                                        focusedMedia.input.uriString,
                                        posMs,
                                        focusedMedia.input.displayName
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxSize()
                                    .verticalScroll(rememberScrollState())
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
                    items(importedMedia, key = { it.input.id }) { info ->
                        ImportedMediaRowCard(
                            info = info,
                            isFocused = focusedMedia?.input?.id == info.input.id,
                            isSelected = info.input.id in selectedMediaIds,
                            onClick = { onFocusMedia(info.input.id) },
                            onToggleSelect = { onToggleSelectMedia(info.input.id) },
                            onRemove = { onRemoveMedia(info.input.id) }
                        )
                    }

                    if (focusedMedia != null) {
                        item {
                            MediaDetailCard(
                                info = focusedMedia,
                                onOpenWithTool = { tool -> onOpenWithTool(focusedMedia, tool) },
                                onExtractFrame = { posMs ->
                                    onExtractFrame(
                                        focusedMedia.input.uriString,
                                        posMs,
                                        focusedMedia.input.displayName
                                    )
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }

            // Tek elle ulaşılabilir alt toplu işlem çubuğu
            if (importedMedia.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val batchCount = if (selectedMediaIds.isNotEmpty()) selectedMediaIds.size else importedMedia.size
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "$batchCount dosya seçili",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "Her dosya türü için uygun varsayılan biçime dönüştürülür",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = onEnqueueBatch) {
                            Icon(
                                imageVector = Icons.Rounded.BurstMode,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Toplu Dönüştür")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyPickerState(
    recentFiles: List<MediaInput>,
    onOpenPhotoPicker: () -> Unit,
    onOpenDocumentPicker: () -> Unit,
    onImportRecent: (MediaInput) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item {
            ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                    Text(
                        text = "Henüz Medya İçe Aktarılmadı",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = "Galeri seçicisiyle fotoğraf/video alabilir ya da Dosya Seçici (SAF) ile MP3, WAV, M4A, MKV, WebM, BMP ve tüm medya dosyalarını seçebilirsiniz.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(onClick = onOpenPhotoPicker, modifier = Modifier.weight(1f)) {
                            Text("Galeri Seç")
                        }
                        OutlinedButton(onClick = onOpenDocumentPicker, modifier = Modifier.weight(1f)) {
                            Text("Dosya Seç")
                        }
                    }
                }
            }
        }

        if (recentFiles.isNotEmpty()) {
            item {
                Text(
                    text = "Önceki Dosyalarından Yükle",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(recentFiles, key = { it.id }) { recent ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onImportRecent(recent) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Icon(
                            imageVector = when (recent.mediaType) {
                                MediaType.VIDEO -> Icons.Rounded.Movie
                                MediaType.AUDIO -> Icons.Rounded.AudioFile
                                MediaType.IMAGE -> Icons.Rounded.Image
                                MediaType.UNKNOWN -> Icons.AutoMirrored.Rounded.InsertDriveFile
                            },
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = recent.displayName,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${recent.mediaType.labelTr} • ${MediaFormatUtils.formatBytes(recent.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "Yükle",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportedMediaRowCard(
    info: MediaInfo,
    isFocused: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onToggleSelect: () -> Unit,
    onRemove: () -> Unit
) {
    val studioColors = LocalStudioColors.current
    val borderColor = if (isFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = if (isFocused) 2.dp else 1.dp,
                color = borderColor,
                shape = MaterialTheme.shapes.medium
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isFocused) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            } else {
                MaterialTheme.colorScheme.surfaceContainer
            }
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Checkbox(checked = isSelected, onCheckedChange = { onToggleSelect() })

                MediaThumbnailBox(
                    info = info,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(10.dp))
                )

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = info.input.displayName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(info.mediaType.labelTr)
                            append(" • ")
                            append(info.formattedSize)
                            if (info.durationMs > 0L) {
                                append(" • ")
                                append(info.formattedDuration)
                            }
                            if (info.width > 0 && info.height > 0) {
                                append(" • ")
                                append(info.displayResolution)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Rounded.DeleteOutline,
                        contentDescription = "Listeden çıkar",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (info.isExtensionMismatch) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = studioColors.warning.copy(alpha = 0.16f)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = studioColors.warning,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = info.mismatchWarning
                                ?: "Uzantı ve gerçek medya biçimi arasında uyuşmazlık tespit edildi.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MediaDetailCard(
    info: MediaInfo,
    onOpenWithTool: (StudioTool) -> Unit,
    onExtractFrame: (positionMs: Long) -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Dosya Ayrıntıları",
                style = MaterialTheme.typography.titleLarge
            )

            // Büyük önizleme
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(LocalStudioColors.current.canvasBackground),
                contentAlignment = Alignment.Center
            ) {
                MediaThumbnailBox(
                    info = info,
                    modifier = Modifier.fillMaxSize(),
                    largePreview = true
                )
            }

            Text(
                text = info.input.displayName,
                style = MaterialTheme.typography.titleMedium
            )

            // Teknik özellikler tablosu
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                TechSpecRow("Tür / Biçim", "${info.mediaType.labelTr} (${info.detectedFormat.uppercase().ifBlank { "-" }})")
                TechSpecRow("MIME Türü", info.detectedMimeType.ifBlank { "-" })
                TechSpecRow("Dosya Boyutu", info.formattedSize)
                if (info.width > 0 && info.height > 0) {
                    TechSpecRow("Çözünürlük", "${info.displayResolution} (${info.rotationDegrees}°)")
                }
                if (info.durationMs > 0L) {
                    TechSpecRow("Süre", info.formattedDuration)
                }
                if (info.fps > 0f) {
                    TechSpecRow("Kare Hızı (FPS)", String.format("%.2f fps", info.fps))
                }
                if (!info.videoCodec.isNullOrBlank()) {
                    TechSpecRow("Video Kodek", info.videoCodec)
                }
                if (info.videoBitrateBps > 0L) {
                    TechSpecRow("Video Bitrate", "${info.videoBitrateBps / 1000} kbps")
                }
                if (!info.audioCodec.isNullOrBlank()) {
                    TechSpecRow("Ses Kodek", info.audioCodec)
                }
                if (info.sampleRateHz > 0) {
                    TechSpecRow("Örnekleme / Kanal", "${info.sampleRateHz} Hz • ${info.channelCount} kanal")
                }
                if (info.videoTrackCount > 0 || info.audioTrackCount > 0) {
                    TechSpecRow("Akış Sayısı", "${info.videoTrackCount} video, ${info.audioTrackCount} ses")
                }
            }

            // Video karesi çıkarıcı
            if (info.mediaType == MediaType.VIDEO && info.durationMs > 0L) {
                HorizontalDivider()
                var frameRatio by remember(info.input.id) { mutableFloatStateOf(0.25f) }
                val targetPosMs = (info.durationMs * frameRatio).roundToLong().coerceIn(0L, info.durationMs)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Videodan Kare Çıkar",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = MediaFormatUtils.formatDurationMs(targetPosMs),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    Slider(
                        value = frameRatio,
                        onValueChange = { frameRatio = it },
                        valueRange = 0f..1f
                    )
                    FilledTonalButton(
                        onClick = { onExtractFrame(targetPosMs) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CameraAlt,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Buradan Karesini Çıkar")
                    }
                }
            }

            // EXIF bilgileri
            if (info.exifSummary.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "EXIF Metadata Özeti",
                    style = MaterialTheme.typography.titleSmall
                )
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    info.exifSummary.entries.take(10).forEach { (k, v) ->
                        TechSpecRow(k, v)
                    }
                }
            }

            // Uygun Araç Kısayolları
            HorizontalDivider()
            Text(
                text = "Uygun Stüdyo Araçları",
                style = MaterialTheme.typography.titleSmall
            )
            val suggestedTools = when (info.mediaType) {
                MediaType.VIDEO -> listOf(
                    StudioTool.VIDEO_CONVERT,
                    StudioTool.VIDEO_EDIT,
                    StudioTool.VIDEO_TRIM_SPLIT,
                    StudioTool.VIDEO_EXTRACT_AUDIO,
                    StudioTool.VIDEO_ADD_AUDIO,
                    StudioTool.VIDEO_MERGE
                )
                MediaType.AUDIO -> listOf(
                    StudioTool.AUDIO_CONVERT,
                    StudioTool.AUDIO_EDIT_MERGE
                )
                MediaType.IMAGE -> listOf(
                    StudioTool.IMAGE_CONVERT,
                    StudioTool.IMAGE_COMPRESS,
                    StudioTool.SLIDESHOW_MAKER
                )
                MediaType.UNKNOWN -> listOf(StudioTool.BATCH_CONVERT)
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestedTools.forEach { tool ->
                    AssistChip(
                        onClick = { onOpenWithTool(tool) },
                        label = { Text(tool.titleTr) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TechSpecRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun MediaThumbnailBox(
    info: MediaInfo,
    modifier: Modifier = Modifier,
    largePreview: Boolean = false
) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = null,
        key1 = info.thumbnailPath,
        key2 = info.input.uriString,
        key3 = largePreview
    ) {
        value = withContext(Dispatchers.IO) {
            loadPreviewBitmap(
                thumbnailPath = info.thumbnailPath,
                uriString = info.input.uriString,
                isImage = info.mediaType == MediaType.IMAGE,
                maxDim = if (largePreview) 720 else 200,
                context = context
            )?.asImageBitmap()
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!,
            contentDescription = info.input.displayName,
            contentScale = if (largePreview) ContentScale.Fit else ContentScale.Crop,
            modifier = modifier
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = when (info.mediaType) {
                    MediaType.VIDEO -> Icons.Rounded.Movie
                    MediaType.AUDIO -> Icons.Rounded.AudioFile
                    MediaType.IMAGE -> Icons.Rounded.Image
                    MediaType.UNKNOWN -> Icons.AutoMirrored.Rounded.InsertDriveFile
                },
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (largePreview) 48.dp else 26.dp)
            )
        }
    }
}

private fun loadPreviewBitmap(
    thumbnailPath: String?,
    uriString: String,
    isImage: Boolean,
    maxDim: Int,
    context: android.content.Context
): Bitmap? {
    if (!thumbnailPath.isNullOrBlank()) {
        val file = File(thumbnailPath)
        if (file.exists()) {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.inSampleSize = computeSampleSize(opts.outWidth, opts.outHeight, maxDim)
            opts.inJustDecodeBounds = false
            return runCatching { BitmapFactory.decodeFile(file.absolutePath, opts) }.getOrNull()
        }
    }
    if (!isImage) return null
    val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
    return runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val decodeOpts = BitmapFactory.Options().apply {
            inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, maxDim)
        }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decodeOpts) }
    }.getOrNull()
}

private fun computeSampleSize(width: Int, height: Int, maxDim: Int): Int {
    if (width <= 0 || height <= 0) return 1
    var sample = 1
    while (width / sample > maxDim * 2 || height / sample > maxDim * 2) {
        sample *= 2
    }
    return sample
}

@Composable
fun ExtractedFrameDialog(
    framePath: String,
    sourceName: String,
    onDismiss: () -> Unit,
    onSaveToGallery: () -> Unit,
    onEditInImageStudio: () -> Unit
) {
    val preview by produceState<ImageBitmap?>(initialValue = null, key1 = framePath) {
        value = withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeFile(framePath)?.asImageBitmap() }.getOrNull()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Çıkarılan Video Karesi") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = sourceName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (preview != null) {
                    Image(
                        bitmap = preview!!,
                        contentDescription = "Çıkarılan kare",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(12.dp))
                            .background(LocalStudioColors.current.canvasBackground)
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSaveToGallery) {
                Icon(Icons.Rounded.SaveAlt, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Galeriye Kaydet")
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onEditInImageStudio) {
                    Text("Resim Stüdyosu")
                }
                TextButton(onClick = onDismiss) {
                    Text("Kapat")
                }
            }
        }
    )
}
