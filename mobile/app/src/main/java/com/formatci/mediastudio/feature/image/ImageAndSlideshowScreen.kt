package com.formatci.mediastudio.feature.image

import android.graphics.Color as AndroidColor
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
import androidx.compose.material.icons.automirrored.rounded.RotateRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.BurstMode
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Flip
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.formatci.mediastudio.core.designsystem.LocalStudioColors
import com.formatci.mediastudio.domain.model.AspectRatioOption
import com.formatci.mediastudio.domain.model.AudioFitMode
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaJobSettings
import com.formatci.mediastudio.domain.model.QualityProfile
import com.formatci.mediastudio.feature.picker.MediaThumbnailBox
import kotlin.math.roundToInt

@Composable
fun ImageAndSlideshowScreen(
    imageInputs: List<MediaInfo>,
    focusedImage: MediaInfo?,
    settings: MediaJobSettings,
    slideshowMode: Boolean,
    slideshowAudio: MediaInfo?,
    simpleMode: Boolean,
    capabilities: EngineCapabilities,
    estimate: JobEstimate?,
    onSetSlideshowMode: (Boolean) -> Unit,
    onAddImages: () -> Unit,
    onSelectFocusedImage: (String) -> Unit,
    onMoveImage: (String, Boolean) -> Unit,
    onRemoveImage: (String) -> Unit,
    onPickSlideshowAudio: () -> Unit,
    onClearSlideshowAudio: () -> Unit,
    onUpdateSettings: ((MediaJobSettings) -> MediaJobSettings) -> Unit,
    onConvertImage: (compressMode: Boolean, batchAll: Boolean) -> Unit,
    onCreateSlideshow: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val isWide = maxWidth >= 840.dp

        Column(modifier = Modifier.fillMaxSize()) {
            // Üst Mod Seçici
            Surface(tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = !slideshowMode,
                        onClick = { onSetSlideshowMode(false) },
                        label = { Text("Resim Dönüştür / Sıkıştır") },
                        leadingIcon = {
                            Icon(Icons.Rounded.PhotoLibrary, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                    FilterChip(
                        selected = slideshowMode,
                        onClick = { onSetSlideshowMode(true) },
                        label = { Text("Slayt Videosu") },
                        leadingIcon = {
                            Icon(Icons.Rounded.Slideshow, contentDescription = null, modifier = Modifier.size(16.dp))
                        }
                    )
                }
            }

            if (imageInputs.isEmpty()) {
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
                                imageVector = if (slideshowMode) Icons.Rounded.Slideshow else Icons.Rounded.PhotoLibrary,
                                contentDescription = null,
                                tint = LocalStudioColors.current.imageAccent,
                                modifier = Modifier.size(48.dp)
                            )
                            Text(
                                text = if (slideshowMode) "Slayt Videosu İçin Fotoğraf Seçin" else "Resim Stüdyosu Boş",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = if (slideshowMode) {
                                    "Birden fazla fotoğraf seçin, sırasını ve süresini ayarlayın, isterseniz fon müziği ekleyerek MP4 slayt videosu oluşturun."
                                } else {
                                    "JPG, PNG, WebP, BMP, HEIC veya GIF görsellerini seçip dönüştürün, sıkıştırın, ölçekleyin veya EXIF verilerini temizleyin."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(onClick = onAddImages) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Fotoğraf Seç")
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
                        if (!slideshowMode && focusedImage != null) {
                            item {
                                ImagePreviewAndExifCard(
                                    info = focusedImage,
                                    settings = settings,
                                    estimate = estimate
                                )
                            }
                        }
                        item {
                            ImageListCard(
                                imageInputs = imageInputs,
                                focusedId = focusedImage?.input?.id,
                                slideshowMode = slideshowMode,
                                onAddImages = onAddImages,
                                onSelectFocused = onSelectFocusedImage,
                                onMoveImage = onMoveImage,
                                onRemoveImage = onRemoveImage
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
                            if (slideshowMode) {
                                SlideshowSettingsCard(
                                    imageCount = imageInputs.size,
                                    settings = settings,
                                    slideshowAudio = slideshowAudio,
                                    simpleMode = simpleMode,
                                    onPickAudio = onPickSlideshowAudio,
                                    onClearAudio = onClearSlideshowAudio,
                                    onUpdateSettings = onUpdateSettings
                                )
                            } else {
                                ImageEditSettingsCard(
                                    settings = settings,
                                    simpleMode = simpleMode,
                                    capabilities = capabilities,
                                    onUpdateSettings = onUpdateSettings
                                )
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (!slideshowMode && focusedImage != null) {
                        item {
                            ImagePreviewAndExifCard(
                                info = focusedImage,
                                settings = settings,
                                estimate = estimate
                            )
                        }
                    }
                    item {
                        ImageListCard(
                            imageInputs = imageInputs,
                            focusedId = focusedImage?.input?.id,
                            slideshowMode = slideshowMode,
                            onAddImages = onAddImages,
                            onSelectFocused = onSelectFocusedImage,
                            onMoveImage = onMoveImage,
                            onRemoveImage = onRemoveImage
                        )
                    }
                    item {
                        if (slideshowMode) {
                            SlideshowSettingsCard(
                                imageCount = imageInputs.size,
                                settings = settings,
                                slideshowAudio = slideshowAudio,
                                simpleMode = simpleMode,
                                onPickAudio = onPickSlideshowAudio,
                                onClearAudio = onClearSlideshowAudio,
                                onUpdateSettings = onUpdateSettings
                            )
                        } else {
                            ImageEditSettingsCard(
                                settings = settings,
                                simpleMode = simpleMode,
                                capabilities = capabilities,
                                onUpdateSettings = onUpdateSettings
                            )
                        }
                    }
                }
            }

            // Alt aksiyon çubuğu
            if (imageInputs.isNotEmpty()) {
                Surface(tonalElevation = 4.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val estBytes = estimate?.estimatedOutputBytes ?: 0L
                        val targetFmtLabel = if (slideshowMode) "MP4" else settings.outputFormat.uppercase()
                        Text(
                            text = if (estBytes > 0L) {
                                "Tahmini Çıktı: ${MediaFormatUtils.formatBytes(estBytes)} ($targetFmtLabel) • Bu bir tahmindir"
                            } else {
                                "Hedef Biçim: $targetFmtLabel"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (slideshowMode) {
                            Button(
                                onClick = onCreateSlideshow,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Rounded.Slideshow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("MP4 Slayt Videosu Oluştur (${imageInputs.size} Kare)")
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { onConvertImage(false, false) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Dönüştür")
                                }
                                FilledTonalButton(
                                    onClick = { onConvertImage(true, false) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text("Sıkıştır")
                                }
                                if (imageInputs.size > 1) {
                                    OutlinedButton(
                                        onClick = { onConvertImage(false, true) },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Icon(Icons.Rounded.BurstMode, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Toplu (${imageInputs.size})", maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImagePreviewAndExifCard(
    info: MediaInfo,
    settings: MediaJobSettings,
    estimate: JobEstimate?
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(settings.jpegBackgroundColorArgb)),
                contentAlignment = Alignment.Center
            ) {
                MediaThumbnailBox(
                    info = info,
                    largePreview = true,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            rotationZ = settings.rotateDegrees.toFloat()
                            scaleX = if (settings.flipHorizontal) -1f else 1f
                            scaleY = if (settings.flipVertical) -1f else 1f
                        }
                )
                if (settings.watermarkText.isNotBlank()) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = settings.watermarkText,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = settings.watermarkOpacity),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            // Öncesi / Sonrası Boyut Karşılaştırması
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Orijinal", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${info.displayResolution} • ${info.formattedSize}",
                        style = MaterialTheme.typography.titleSmall
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Tahmini Sonrası", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    val targetW = settings.targetWidth ?: (info.width * settings.imageScalePercent / 100)
                    val targetH = settings.targetHeight ?: (info.height * settings.imageScalePercent / 100)
                    val estText = estimate?.let { MediaFormatUtils.formatBytes(it.estimatedOutputBytes) } ?: "-"
                    Text(
                        text = "${targetW}x${targetH} • $estText",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            if (info.exifSummary.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "EXIF Bilgisi (${info.exifSummary.size} alan): " +
                        info.exifSummary.entries.take(3).joinToString(" • ") { "${it.key}: ${it.value}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun ImageListCard(
    imageInputs: List<MediaInfo>,
    focusedId: String?,
    slideshowMode: Boolean,
    onAddImages: () -> Unit,
    onSelectFocused: (String) -> Unit,
    onMoveImage: (String, Boolean) -> Unit,
    onRemoveImage: (String) -> Unit
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
                Text(
                    text = if (slideshowMode) "Slayt Sırası (${imageInputs.size} Kare)" else "Seçilen Resimler (${imageInputs.size})",
                    style = MaterialTheme.typography.titleMedium
                )
                TextButton(onClick = onAddImages) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Resim Ekle")
                }
            }

            imageInputs.forEachIndexed { idx, info ->
                val isFocused = info.input.id == focusedId
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = if (isFocused) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surface,
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
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MediaThumbnailBox(
                            info = info,
                            modifier = Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${idx + 1}. ${info.input.displayName}",
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${info.displayResolution} • ${info.formattedSize}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (idx > 0) {
                            IconButton(onClick = { onMoveImage(info.input.id, true) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Yukarı", modifier = Modifier.size(16.dp))
                            }
                        }
                        if (idx < imageInputs.lastIndex) {
                            IconButton(onClick = { onMoveImage(info.input.id, false) }, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Aşağı", modifier = Modifier.size(16.dp))
                            }
                        }
                        IconButton(onClick = { onRemoveImage(info.input.id) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Kaldır", modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImageEditSettingsCard(
    settings: MediaJobSettings,
    simpleMode: Boolean,
    capabilities: EngineCapabilities,
    onUpdateSettings: ((MediaJobSettings) -> MediaJobSettings) -> Unit
) {
    val formats = listOf(
        "jpg" to "JPG: Fotoğraflar için en küçük ve uyumlu format",
        "png" to "PNG: Şeffaflık destekli kayıpsız görüntü",
        "webp" to "WebP: Modern, şeffaflık destekli yüksek sıkıştırma",
        "bmp" to "BMP: Sıkıştırmasız ham piksel formatı"
    )

    val bgColorOptions = listOf(
        "Beyaz" to AndroidColor.WHITE,
        "Siyah" to AndroidColor.BLACK,
        "Gri" to 0xFFE2E8F0.toInt(),
        "Lacivert" to 0xFF0F172A.toInt(),
        "Krem" to 0xFFFEF3C7.toInt()
    )

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("Resim Dönüştürme & Sıkıştırma Ayarları", style = MaterialTheme.typography.titleMedium)

            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                formats.forEach { (fmt, _) ->
                    val enabled = fmt in capabilities.supportedImageOutputFormats
                    FilterChip(
                        selected = settings.outputFormat == fmt,
                        enabled = enabled,
                        onClick = { onUpdateSettings { it.copy(outputFormat = fmt) } },
                        label = { Text(fmt.uppercase()) }
                    )
                }
            }
            formats.firstOrNull { it.first == settings.outputFormat }?.second?.let { desc ->
                Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            if (settings.outputFormat in listOf("jpg", "jpeg", "webp")) {
                Column {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Sıkıştırma Kalitesi", style = MaterialTheme.typography.titleSmall)
                        Text("%${settings.imageQuality}", style = MaterialTheme.typography.labelLarge)
                    }
                    Slider(
                        value = settings.imageQuality.toFloat(),
                        onValueChange = { q -> onUpdateSettings { it.copy(imageQuality = q.roundToInt()) } },
                        valueRange = 10f..100f
                    )
                }
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Boyut Ölçeği", style = MaterialTheme.typography.titleSmall)
                    Text("%${settings.imageScalePercent}", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = settings.imageScalePercent.toFloat(),
                    onValueChange = { s ->
                        onUpdateSettings { it.copy(imageScalePercent = s.roundToInt(), targetWidth = null, targetHeight = null) }
                    },
                    valueRange = 10f..100f
                )
            }

            // Döndür & Çevir
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        onUpdateSettings { it.copy(rotateDegrees = (it.rotateDegrees + 90) % 360) }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.RotateRight, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("${settings.rotateDegrees}°")
                }
                FilterChip(
                    selected = settings.flipHorizontal,
                    onClick = { onUpdateSettings { it.copy(flipHorizontal = !it.flipHorizontal) } },
                    label = { Text("Yatay Çevir") },
                    leadingIcon = { Icon(Icons.Rounded.Flip, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
                FilterChip(
                    selected = settings.flipVertical,
                    onClick = { onUpdateSettings { it.copy(flipVertical = !it.flipVertical) } },
                    label = { Text("Dikey") }
                )
            }

            // Renk Filtreleri
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = settings.grayscale,
                    onClick = { onUpdateSettings { it.copy(grayscale = !it.grayscale, sepia = false) } },
                    label = { Text("Siyah-Beyaz") }
                )
                FilterChip(
                    selected = settings.sepia,
                    onClick = { onUpdateSettings { it.copy(sepia = !it.sepia, grayscale = false) } },
                    label = { Text("Sepya") }
                )
            }

            // Şeffaf PNG -> JPG Arka Plan Rengi
            if (settings.outputFormat in listOf("jpg", "jpeg", "bmp")) {
                Text(
                    text = "Şeffaf Alan Arka Plan Rengi (PNG → ${settings.outputFormat.uppercase()})",
                    style = MaterialTheme.typography.titleSmall
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    bgColorOptions.forEach { (label, argb) ->
                        FilterChip(
                            selected = settings.jpegBackgroundColorArgb == argb,
                            onClick = { onUpdateSettings { it.copy(jpegBackgroundColorArgb = argb) } },
                            leadingIcon = {
                                Box(
                                    modifier = Modifier
                                        .size(14.dp)
                                        .clip(CircleShape)
                                        .background(Color(argb))
                                        .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                                )
                            },
                            label = { Text(label) }
                        )
                    }
                }
            }

            // EXIF Temizleme
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("EXIF Bilgilerini Temizle", style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = "GPS konumu, kamera modeli ve çekim tarihini gizlilik için siler.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = settings.stripExif,
                    onCheckedChange = { strip -> onUpdateSettings { it.copy(stripExif = strip) } }
                )
            }

            // Filigran
            OutlinedTextField(
                value = settings.watermarkText,
                onValueChange = { txt -> onUpdateSettings { it.copy(watermarkText = txt) } },
                label = { Text("Filigran / Yazı Ekle (isteğe bağlı)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            if (!simpleMode) {
                HorizontalDivider()
                Text("Özel Piksel Boyutu (İsteğe Bağlı)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = settings.targetWidth?.toString().orEmpty(),
                        onValueChange = { v ->
                            onUpdateSettings { it.copy(targetWidth = v.toIntOrNull()?.coerceIn(16, 8192)) }
                        },
                        label = { Text("Genişlik (px)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = settings.targetHeight?.toString().orEmpty(),
                        onValueChange = { v ->
                            onUpdateSettings { it.copy(targetHeight = v.toIntOrNull()?.coerceIn(16, 8192)) }
                        },
                        label = { Text("Yükseklik (px)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SlideshowSettingsCard(
    imageCount: Int,
    settings: MediaJobSettings,
    slideshowAudio: MediaInfo?,
    simpleMode: Boolean,
    onPickAudio: () -> Unit,
    onClearAudio: () -> Unit,
    onUpdateSettings: ((MediaJobSettings) -> MediaJobSettings) -> Unit
) {
    val totalDurationMs = imageCount * settings.slideDurationMs

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Slayt Videosu Ayarları", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Toplam: ${MediaFormatUtils.formatDurationMs(totalDurationMs)}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Column {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Kare Başına Süre", style = MaterialTheme.typography.titleSmall)
                    Text("${settings.slideDurationMs / 1000f} sn", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = (settings.slideDurationMs / 1000f).coerceIn(1f, 10f),
                    onValueChange = { sec ->
                        val ms = (sec * 2f).roundToInt() * 500L
                        onUpdateSettings { it.copy(slideDurationMs = ms.coerceIn(1000L, 10_000L)) }
                    },
                    valueRange = 1f..10f
                )
            }

            Text("En-Boy Oranı", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    AspectRatioOption.RATIO_16_9,
                    AspectRatioOption.RATIO_9_16,
                    AspectRatioOption.RATIO_1_1,
                    AspectRatioOption.RATIO_4_3
                ).forEach { ratio ->
                    FilterChip(
                        selected = settings.aspectRatio == ratio,
                        onClick = { onUpdateSettings { it.copy(aspectRatio = ratio) } },
                        label = { Text(ratio.labelTr) }
                    )
                }
            }

            // Fon müziği seçimi
            HorizontalDivider()
            Text("Fon Müziği (İsteğe Bağlı)", style = MaterialTheme.typography.titleSmall)
            if (slideshowAudio == null) {
                OutlinedButton(onClick = onPickAudio, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.MusicNote, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Fon Müziği Seç")
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
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = slideshowAudio.input.displayName,
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = slideshowAudio.formattedDuration,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        TextButton(onClick = onClearAudio) {
                            Text("Kaldır")
                        }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AudioFitMode.entries.forEach { mode ->
                        FilterChip(
                            selected = settings.audioFitMode == mode,
                            onClick = { onUpdateSettings { it.copy(audioFitMode = mode) } },
                            label = { Text(mode.labelTr) }
                        )
                    }
                }
            }

            // Kalite Profili
            Text("Video Kalite Profili", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QualityProfile.entries.forEach { profile ->
                    FilterChip(
                        selected = settings.qualityProfile == profile,
                        onClick = { onUpdateSettings { it.copy(qualityProfile = profile) } },
                        label = { Text(profile.labelTr) }
                    )
                }
            }

            if (!simpleMode) {
                Text("Kare Hızı (FPS)", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(24, 30).forEach { fps ->
                        FilterChip(
                            selected = settings.slideshowFps == fps,
                            onClick = { onUpdateSettings { it.copy(slideshowFps = fps) } },
                            label = { Text("$fps FPS") }
                        )
                    }
                }
                OutlinedTextField(
                    value = settings.outputFileName,
                    onValueChange = { name -> onUpdateSettings { it.copy(outputFileName = name) } },
                    label = { Text("Video Dosya Adı (isteğe bağlı)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}
