package com.formatci.mediastudio.feature.queue

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AudioFile
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PrivacyTip
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.formatci.mediastudio.core.designsystem.LocalStudioColors
import com.formatci.mediastudio.domain.model.AppSettings
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobStatus
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.OutputRecord
import com.formatci.mediastudio.domain.model.QualityProfile
import com.formatci.mediastudio.domain.model.ThemePreference
import com.formatci.mediastudio.feature.home.CapabilitiesDetailDialog
import com.formatci.mediastudio.ui.HistorySortOrder
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun QueueScreen(
    jobs: List<MediaJob>,
    onMoveJob: (String, Boolean) -> Unit,
    onCancelJob: (String) -> Unit,
    onRetryJob: (String) -> Unit,
    onClearFinished: () -> Unit,
    onOpenOutput: (uriString: String, mimeType: String) -> Unit,
    onShareOutput: (uriString: String, mimeType: String, title: String) -> Unit,
    onSaveAsOutput: (uriString: String, suggestedName: String, mimeType: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val hasRunning = remember(jobs) {
        jobs.any { it.status == JobStatus.PROCESSING || it.status == JobStatus.PREPARING }
    }
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(hasRunning) {
        while (hasRunning) {
            nowMs = System.currentTimeMillis()
            delay(1_000L)
        }
    }

    val queuedIds = remember(jobs) {
        jobs.filter { it.status == JobStatus.QUEUED }.sortedBy { it.queueOrder }.map { it.id }
    }
    val hasTerminal = remember(jobs) { jobs.any { it.status.isTerminal } }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("İşlem Kuyruğu", style = MaterialTheme.typography.titleLarge)
                    Text(
                        text = "${jobs.count { it.status.isActive }} aktif • ${jobs.count { it.status.isTerminal }} bitmiş",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (hasTerminal) {
                    TextButton(onClick = onClearFinished) {
                        Text("Bitenleri Temizle")
                    }
                }
            }
        }

        if (jobs.isEmpty()) {
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Kuyrukta İş Yok", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Ana Sayfa veya Stüdyo sekmelerinden video, ses veya resim işlemi başlattığınızda ilerleme burada canlı görünür.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(jobs, key = { it.id }) { job ->
                    val queuedIndex = queuedIds.indexOf(job.id)
                    JobCard(
                        job = job,
                        nowMs = nowMs,
                        canMoveUp = queuedIndex > 0,
                        canMoveDown = queuedIndex >= 0 && queuedIndex < queuedIds.lastIndex,
                        onMoveUp = { onMoveJob(job.id, true) },
                        onMoveDown = { onMoveJob(job.id, false) },
                        onCancel = { onCancelJob(job.id) },
                        onRetry = { onRetryJob(job.id) },
                        onOpen = {
                            val uri = job.outputUriString ?: return@JobCard
                            val mime = MediaFormatUtils.mimeTypeForExtension(job.settings.outputFormat)
                            onOpenOutput(uri, mime)
                        },
                        onShare = {
                            val uri = job.outputUriString ?: return@JobCard
                            val mime = MediaFormatUtils.mimeTypeForExtension(job.settings.outputFormat)
                            onShareOutput(uri, mime, job.outputDisplayName ?: job.title)
                        },
                        onSaveAs = {
                            val uri = job.outputUriString ?: return@JobCard
                            val mime = MediaFormatUtils.mimeTypeForExtension(job.settings.outputFormat)
                            onSaveAsOutput(uri, job.outputDisplayName ?: "avenlyx_cikti.${job.settings.outputFormat}", mime)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun JobCard(
    job: MediaJob,
    nowMs: Long,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSaveAs: () -> Unit
) {
    val colors = LocalStudioColors.current
    val badgeBg = when (job.status) {
        JobStatus.COMPLETED -> colors.success.copy(alpha = 0.16f)
        JobStatus.PROCESSING, JobStatus.PREPARING -> MaterialTheme.colorScheme.primaryContainer
        JobStatus.QUEUED -> MaterialTheme.colorScheme.surfaceVariant
        JobStatus.CANCELLED -> colors.warning.copy(alpha = 0.16f)
        JobStatus.FAILED -> MaterialTheme.colorScheme.errorContainer
    }
    val badgeFg = when (job.status) {
        JobStatus.COMPLETED -> colors.success
        JobStatus.PROCESSING, JobStatus.PREPARING -> MaterialTheme.colorScheme.onPrimaryContainer
        JobStatus.QUEUED -> MaterialTheme.colorScheme.onSurfaceVariant
        JobStatus.CANCELLED -> colors.warning
        JobStatus.FAILED -> MaterialTheme.colorScheme.onErrorContainer
    }

    val elapsedMs = when {
        job.startedAtMs != null && job.finishedAtMs != null -> (job.finishedAtMs - job.startedAtMs).coerceAtLeast(0L)
        job.startedAtMs != null && job.status.isActive -> (nowMs - job.startedAtMs).coerceAtLeast(0L)
        else -> 0L
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier.fillMaxWidth()
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
                    text = job.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Surface(shape = RoundedCornerShape(8.dp), color = badgeBg) {
                    Text(
                        text = job.status.labelTr,
                        style = MaterialTheme.typography.labelMedium,
                        color = badgeFg,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            if (job.status == JobStatus.PREPARING || job.status == JobStatus.PROCESSING) {
                if (job.isIndeterminate) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(
                        progress = { job.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = job.statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val rightInfo = buildString {
                    if (job.status == JobStatus.PROCESSING && !job.isIndeterminate) {
                        append("${(job.progress * 100).toInt()}%")
                    }
                    if (elapsedMs > 0L) {
                        if (isNotEmpty()) append(" • ")
                        append(MediaFormatUtils.formatDurationMs(elapsedMs))
                    }
                }
                if (rightInfo.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = rightInfo,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (job.status == JobStatus.FAILED && (!job.errorMessage.isNullOrBlank() || !job.errorSuggestion.isNullOrBlank())) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        if (!job.errorMessage.isNullOrBlank()) {
                            Text(
                                text = job.errorMessage,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        if (!job.errorSuggestion.isNullOrBlank()) {
                            Text(
                                text = "Öneri: ${job.errorSuggestion}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }

            if (job.status == JobStatus.COMPLETED && !job.outputDisplayName.isNullOrBlank()) {
                val sizeStr = job.outputSizeBytes?.let { MediaFormatUtils.formatBytes(it) }.orEmpty()
                Text(
                    text = "Çıktı: ${job.outputDisplayName}${if (sizeStr.isNotBlank()) " ($sizeStr)" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.success
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (job.status == JobStatus.QUEUED) {
                    if (canMoveUp) {
                        IconButton(onClick = onMoveUp) {
                            Icon(Icons.Rounded.ArrowUpward, contentDescription = "Öne al")
                        }
                    }
                    if (canMoveDown) {
                        IconButton(onClick = onMoveDown) {
                            Icon(Icons.Rounded.ArrowDownward, contentDescription = "Arkaya al")
                        }
                    }
                }
                if (job.status.isActive) {
                    OutlinedButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Cancel, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("İptal Et")
                    }
                }
                if (job.status == JobStatus.FAILED || job.status == JobStatus.CANCELLED) {
                    FilledTonalButton(onClick = onRetry) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Tekrar Dene")
                    }
                }
                if (job.status == JobStatus.COMPLETED && !job.outputUriString.isNullOrBlank()) {
                    Button(onClick = onOpen) {
                        Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Aç")
                    }
                    FilledTonalButton(onClick = onShare) {
                        Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Paylaş")
                    }
                    IconButton(onClick = onSaveAs) {
                        Icon(Icons.Rounded.SaveAlt, contentDescription = "Farklı kaydet")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(
    records: List<OutputRecord>,
    filter: MediaType?,
    sortOrder: HistorySortOrder,
    searchQuery: String,
    onFilterChange: (MediaType?) -> Unit,
    onSortOrderChange: (HistorySortOrder) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onOpenRecord: (OutputRecord) -> Unit,
    onShareRecord: (OutputRecord) -> Unit,
    onSaveAsRecord: (OutputRecord) -> Unit,
    onRenameRecord: (String, String) -> Unit,
    onDeleteRecord: (String, Boolean) -> Unit,
    onOpenSoundSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    var renameTarget by remember { mutableStateOf<OutputRecord?>(null) }
    var deleteTarget by remember { mutableStateOf<OutputRecord?>(null) }

    val filteredRecords = remember(records, filter, sortOrder, searchQuery) {
        val q = searchQuery.trim().lowercase(Locale.getDefault())
        val base = records.filter { rec ->
            val typeOk = filter == null || rec.mediaType == filter
            val searchOk = q.isBlank() || rec.displayName.lowercase(Locale.getDefault()).contains(q) ||
                rec.format.lowercase(Locale.getDefault()).contains(q)
            typeOk && searchOk
        }
        when (sortOrder) {
            HistorySortOrder.NEWEST -> base.sortedByDescending { it.createdAtMs }
            HistorySortOrder.OLDEST -> base.sortedBy { it.createdAtMs }
            HistorySortOrder.LARGEST -> base.sortedByDescending { it.sizeBytes }
            HistorySortOrder.NAME_ASC -> base.sortedBy { it.displayName.lowercase(Locale.getDefault()) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Çıktılarım ve Geçmiş (${filteredRecords.size})", style = MaterialTheme.typography.titleLarge)

                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = onSearchQueryChange,
                    placeholder = { Text("Dosya adı veya format ara...") },
                    leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    FilterChip(
                        selected = filter == null,
                        onClick = { onFilterChange(null) },
                        label = { Text("Tümü") }
                    )
                    FilterChip(
                        selected = filter == MediaType.VIDEO,
                        onClick = { onFilterChange(MediaType.VIDEO) },
                        label = { Text("Video") }
                    )
                    FilterChip(
                        selected = filter == MediaType.AUDIO,
                        onClick = { onFilterChange(MediaType.AUDIO) },
                        label = { Text("Ses") }
                    )
                    FilterChip(
                        selected = filter == MediaType.IMAGE,
                        onClick = { onFilterChange(MediaType.IMAGE) },
                        label = { Text("Resim") }
                    )
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HistorySortOrder.entries.forEach { order ->
                        FilterChip(
                            selected = sortOrder == order,
                            onClick = { onSortOrderChange(order) },
                            label = { Text(order.labelTr, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }

        if (filteredRecords.isEmpty()) {
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
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Henüz Çıktı Yok", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "Dönüştürdüğünüz veya düzenlediğiniz dosyalar cihazınızın Movies/Music/Pictures/FormatciStudio klasörlerine kaydedilir ve burada listelenir.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredRecords, key = { it.id }) { rec ->
                    HistoryRecordCard(
                        record = rec,
                        onOpen = { onOpenRecord(rec) },
                        onShare = { onShareRecord(rec) },
                        onSaveAs = { onSaveAsRecord(rec) },
                        onRename = { renameTarget = rec },
                        onDelete = { deleteTarget = rec },
                        onOpenSoundSettings = onOpenSoundSettings
                    )
                }
            }
        }
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.displayName.substringBeforeLast('.')) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Dosyayı Yeniden Adlandır") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("Yeni dosya adı (uzantı otomatik korunur)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(onClick = {
                    onRenameRecord(target.id, newName)
                    renameTarget = null
                }) { Text("Kaydet") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("İptal") }
            }
        )
    }

    deleteTarget?.let { target ->
        var deleteFileToo by remember(target.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Çıktıyı Sil") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("\"${target.displayName}\" geçmişten silinsin mi?")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = deleteFileToo, onCheckedChange = { deleteFileToo = it })
                        Text(
                            text = "Depolamadaki dosyayı da kalıcı olarak sil",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    onDeleteRecord(target.id, deleteFileToo)
                    deleteTarget = null
                }) { Text("Sil") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Vazgeç") }
            }
        )
    }
}

@Composable
private fun HistoryRecordCard(
    record: OutputRecord,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onSaveAs: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onOpenSoundSettings: () -> Unit
) {
    val colors = LocalStudioColors.current
    val (icon, tint) = when (record.mediaType) {
        MediaType.VIDEO -> Icons.Rounded.Movie to colors.videoAccent
        MediaType.AUDIO -> Icons.Rounded.AudioFile to colors.audioAccent
        MediaType.IMAGE -> Icons.Rounded.Image to colors.imageAccent
        MediaType.UNKNOWN -> Icons.Rounded.Description to MaterialTheme.colorScheme.primary
    }
    val dateStr = remember(record.createdAtMs) {
        SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(record.createdAtMs))
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(30.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = record.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = buildString {
                            append(record.format.uppercase())
                            append(" • ")
                            append(MediaFormatUtils.formatBytes(record.sizeBytes))
                            if (record.durationMs > 0L) {
                                append(" • ")
                                append(MediaFormatUtils.formatDurationMs(record.durationMs))
                            }
                            if (record.width > 0 && record.height > 0) {
                                append(" • ${record.width}x${record.height}")
                            }
                            append(" • $dateStr")
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onOpen) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Aç")
                }
                FilledTonalButton(onClick = onShare) {
                    Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Paylaş")
                }
                IconButton(onClick = onSaveAs) {
                    Icon(Icons.Rounded.SaveAlt, contentDescription = "Farklı kaydet")
                }
                IconButton(onClick = onRename) {
                    Icon(Icons.Rounded.DriveFileRenameOutline, contentDescription = "Yeniden adlandır")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Sil")
                }
            }

            if (record.mediaType == MediaType.AUDIO) {
                TextButton(onClick = onOpenSoundSettings) {
                    Icon(Icons.Rounded.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Zil Sesi / Sistem Ses Ayarlarını Aç")
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    capabilities: EngineCapabilities,
    cacheSizeBytes: Long,
    availableStorageBytes: Long,
    onSetTheme: (ThemePreference) -> Unit,
    onSetSimpleMode: (Boolean) -> Unit,
    onSetDefaultQuality: (QualityProfile) -> Unit,
    onSetDefaultVideoFormat: (String) -> Unit,
    onSetDefaultAudioFormat: (String) -> Unit,
    onSetDefaultImageFormat: (String) -> Unit,
    onSetNotificationsEnabled: (Boolean) -> Unit,
    onSetAutoCleanCache: (Boolean) -> Unit,
    onClearCache: () -> Unit,
    onExportDiagnosticReport: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showPrivacyDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showCapabilitiesDialog by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("Ayarlar ve Sistem", style = MaterialTheme.typography.headlineSmall)
            }

            // Görünüm ve Mod
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Görünüm ve Kullanıcı Deneyimi", style = MaterialTheme.typography.titleMedium)

                        Text("Tema Seçimi", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ThemePreference.entries.forEach { pref ->
                                FilterChip(
                                    selected = settings.themePreference == pref,
                                    onClick = { onSetTheme(pref) },
                                    label = { Text(pref.labelTr) }
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Basit Mod Varsayılanı", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Karmaşık teknik seçenekleri gizleyip hızlı ayarları öne çıkarır.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = settings.simpleMode, onCheckedChange = onSetSimpleMode)
                        }
                    }
                }
            }

            // Varsayılan Formatlar ve Kalite
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text("Varsayılan Dönüştürme Ayarları", style = MaterialTheme.typography.titleMedium)

                        Text("Varsayılan Kalite Profili", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QualityProfile.entries.forEach { profile ->
                                FilterChip(
                                    selected = settings.defaultQuality == profile,
                                    onClick = { onSetDefaultQuality(profile) },
                                    label = { Text(profile.labelTr) }
                                )
                            }
                        }

                        Text("Varsayılan Video Formatı", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            capabilities.supportedVideoOutputFormats.forEach { fmt ->
                                FilterChip(
                                    selected = settings.defaultVideoFormat == fmt,
                                    onClick = { onSetDefaultVideoFormat(fmt) },
                                    label = { Text(fmt.uppercase()) }
                                )
                            }
                        }

                        Text("Varsayılan Ses Formatı", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            capabilities.supportedAudioOutputFormats.forEach { fmt ->
                                FilterChip(
                                    selected = settings.defaultAudioFormat == fmt,
                                    onClick = { onSetDefaultAudioFormat(fmt) },
                                    label = { Text(fmt.uppercase()) }
                                )
                            }
                        }

                        Text("Varsayılan Resim Formatı", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            capabilities.supportedImageOutputFormats.forEach { fmt ->
                                FilterChip(
                                    selected = settings.defaultImageFormat == fmt,
                                    onClick = { onSetDefaultImageFormat(fmt) },
                                    label = { Text(fmt.uppercase()) }
                                )
                            }
                        }
                    }
                }
            }

            // Bildirim ve Önbellek
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text("Arka Plan İşleme ve Depolama", style = MaterialTheme.typography.titleMedium)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("İşlem Bildirimleri", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Dönüştürme ilerlemesini ve tamamlanma uyarısını bildirim çubuğunda gösterir.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.notificationsEnabled,
                                onCheckedChange = onSetNotificationsEnabled
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("İşlem Sonrası Otomatik Temizlik", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = "Başarılı her iş sonrası geçici çalışma dosyalarını otomatik siler.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = settings.autoCleanCache,
                                onCheckedChange = onSetAutoCleanCache
                            )
                        }

                        HorizontalDivider()

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column {
                                Text(
                                    text = "Geçici Önbellek: ${MediaFormatUtils.formatBytes(cacheSizeBytes)}",
                                    style = MaterialTheme.typography.titleSmall
                                )
                                Text(
                                    text = "Boş depolama: ${MediaFormatUtils.formatBytes(availableStorageBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(onClick = onClearCache) {
                                Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Temizle")
                            }
                        }
                    }
                }
            }

            // Tanı, Gizlilik ve Lisanslar
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Tanı, Gizlilik ve Hakkında", style = MaterialTheme.typography.titleMedium)

                        OutlinedButton(
                            onClick = { showCapabilitiesDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Memory, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Cihaz Kodek & Motor Yeteneklerini Gör")
                        }

                        OutlinedButton(
                            onClick = onExportDiagnosticReport,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.BugReport, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Çevrimdışı Hata & Tanı Raporunu Dışa Aktar")
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(
                                onClick = { showPrivacyDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.PrivacyTip, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Gizlilik Politikası")
                            }
                            TextButton(
                                onClick = { showLicensesDialog = true },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Açık Kaynak Lisansları")
                            }
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

    if (showPrivacyDialog) {
        AlertDialog(
            onDismissRequest = { showPrivacyDialog = false },
            title = { Text("Gizlilik Politikası") },
            text = {
                Text(
                    "AVENLYX Studio %100 çevrimdışı çalışan bir cihaz içi medya işleme uygulamasıdır.\n\n" +
                        "• Uygulamanın INTERNET izni yoktur; hiçbir medya dosyası, meta veri, EXIF bilgisi veya kullanım verisi sunucuya gönderilmez.\n" +
                        "• Seçtiğiniz dosyalar yalnızca Android Photo Picker veya SAF üzerinden verilen okuma izniyle yerel olarak işlenir.\n" +
                        "• Çıktılar cihazınızın Movies/Music/Pictures/FormatciStudio klasörüne kaydedilir ve geçici önbellek Ayarlar'dan tek dokunuşla silinebilir."
                )
            },
            confirmButton = {
                TextButton(onClick = { showPrivacyDialog = false }) { Text("Tamam") }
            }
        )
    }

    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text("Açık Kaynak Lisansları") },
            text = {
                Text(
                    "Bu uygulama aşağıdaki açık kaynak kütüphaneleri kullanır:\n\n" +
                        "• AndroidX Core, Lifecycle, Activity, Navigation, WorkManager, DataStore, ExifInterface (Apache License 2.0)\n" +
                        "• Jetpack Compose UI & Material 3 (Apache License 2.0)\n" +
                        "• AndroidX Media3 ExoPlayer, Transformer, Effect & UI (Apache License 2.0)\n" +
                        "• Kotlin Standard Library & Kotlinx Coroutines (Apache License 2.0)"
                )
            },
            confirmButton = {
                TextButton(onClick = { showLicensesDialog = false }) { Text("Kapat") }
            }
        )
    }
}
