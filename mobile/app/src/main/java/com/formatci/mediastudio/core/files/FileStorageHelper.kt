package com.formatci.mediastudio.core.files

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.formatci.mediastudio.core.error.StudioErrorCode
import com.formatci.mediastudio.core.error.StudioException
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.Locale
import java.util.UUID

data class SavedOutput(
    val uriString: String,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

class FileStorageHelper(private val context: Context) {

    val tempWorkDir: File
        get() = File(context.cacheDir, "studio_work").apply { mkdirs() }

    val thumbnailsDir: File
        get() = File(context.cacheDir, "studio_thumbs").apply { mkdirs() }

    val diagnosticsDir: File
        get() = File(context.cacheDir, "studio_logs").apply { mkdirs() }

    val fallbackOutputDir: File
        get() {
            val base = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: context.filesDir
            return File(base, "AvenlyxStudio").apply { mkdirs() }
        }

    fun takePersistablePermissionSafely(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        try {
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
            context.contentResolver.takePersistableUriPermission(uri, flags)
        } catch (_: SecurityException) {
            // Photo Picker veya tek seferlik SAF URI'lerinde kalıcı izin gerekmeyebilir.
        } catch (_: IllegalArgumentException) {
            // Sağlayıcı persistable izin bayrağını desteklemiyorsa yok say.
        }
    }

    fun canReadUri(uri: Uri): Boolean {
        return when (uri.scheme) {
            ContentResolver.SCHEME_FILE -> {
                val path = uri.path ?: return false
                File(path).let { it.exists() && it.canRead() }
            }
            ContentResolver.SCHEME_CONTENT -> {
                try {
                    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { true } ?: false
                } catch (_: Exception) {
                    false
                }
            }
            else -> false
        }
    }

    fun openInputStream(uri: Uri): InputStream? {
        return try {
            when (uri.scheme) {
                ContentResolver.SCHEME_FILE -> uri.path?.let { FileInputStream(File(it)) }
                else -> context.contentResolver.openInputStream(uri)
            }
        } catch (se: SecurityException) {
            throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, se.message, se)
        } catch (fnf: java.io.FileNotFoundException) {
            throw StudioException(StudioErrorCode.FILE_NOT_FOUND, fnf.message, fnf)
        }
    }

    fun resolveMediaInput(uri: Uri): MediaInput {
        var displayName = ""
        var sizeBytes = 0L

        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            displayName = cursor.getString(nameIdx).orEmpty()
                        }
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            sizeBytes = cursor.getLong(sizeIdx).coerceAtLeast(0L)
                        }
                    }
                }
            } catch (_: SecurityException) {
                // Erişim kontrolünü aşağıda okuma aşamasında doğrularız.
            }
        } else if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = uri.path?.let { File(it) }
            if (file != null) {
                displayName = file.name
                sizeBytes = if (file.exists()) file.length() else 0L
            }
        }

        if (displayName.isBlank()) {
            displayName = uri.lastPathSegment
                ?.substringAfterLast('/')
                ?.substringAfterLast(':')
                ?.takeIf { it.isNotBlank() }
                ?: "medya_${System.currentTimeMillis()}"
        }

        val mimeType = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
        var extension = MediaFormatUtils.extensionFromFileName(displayName)
        if (extension.isBlank() && mimeType.isNotBlank()) {
            extension = extensionFromMime(mimeType)
            if (extension.isNotBlank() && '.' !in displayName) {
                displayName = "$displayName.$extension"
            }
        }

        val mediaType = MediaFormatUtils.inferMediaType(extension, mimeType)

        return MediaInput(
            id = UUID.randomUUID().toString(),
            uriString = uri.toString(),
            displayName = displayName,
            extension = extension,
            mimeType = mimeType.ifBlank { MediaFormatUtils.mimeTypeForExtension(extension) },
            sizeBytes = sizeBytes,
            mediaType = mediaType
        )
    }

    fun getAvailableStorageBytes(): Long {
        return try {
            val target = context.getExternalFilesDir(null) ?: context.cacheDir
            val stat = StatFs(target.absolutePath)
            stat.availableBytes
        } catch (_: Exception) {
            try {
                StatFs(context.filesDir.absolutePath).availableBytes
            } catch (_: Exception) {
                Long.MAX_VALUE
            }
        }
    }

    fun hasEnoughStorage(requiredBytes: Long, safetyMarginBytes: Long = 32L * 1024L * 1024L): Boolean {
        val available = getAvailableStorageBytes()
        return available >= (requiredBytes.coerceAtLeast(0L) + safetyMarginBytes)
    }

    fun ensureEnoughStorage(requiredBytes: Long) {
        if (!hasEnoughStorage(requiredBytes)) {
            val availText = MediaFormatUtils.formatBytes(getAvailableStorageBytes())
            val reqText = MediaFormatUtils.formatBytes(requiredBytes)
            throw StudioException(
                StudioErrorCode.LOW_STORAGE,
                "Gereken: $reqText, Kullanılabilir: $availText"
            )
        }
    }

    fun createTempOutputFile(prefix: String, extension: String): File {
        val safePrefix = MediaFormatUtils.sanitizeBaseName(prefix, "temp")
        val ext = extension.lowercase(Locale.ROOT).trimStart('.').ifBlank { "tmp" }
        return File(tempWorkDir, "${safePrefix}_${UUID.randomUUID().toString().take(8)}.$ext")
    }

    fun createThumbnailFile(prefix: String = "thumb", extension: String = "jpg"): File {
        val safePrefix = MediaFormatUtils.sanitizeBaseName(prefix, "thumb")
        val ext = extension.lowercase(Locale.ROOT).trimStart('.').ifBlank { "jpg" }
        return File(thumbnailsDir, "${safePrefix}_${UUID.randomUUID().toString().take(8)}.$ext")
    }

    suspend fun copyUriToTempFile(uri: Uri, prefix: String = "src"): File = withContext(Dispatchers.IO) {
        val inputMeta = resolveMediaInput(uri)
        if (inputMeta.sizeBytes > 0L) {
            ensureEnoughStorage(inputMeta.sizeBytes)
        }
        val ext = inputMeta.extension.ifBlank { "bin" }
        val destFile = createTempOutputFile(prefix, ext)
        try {
            val stream = openInputStream(uri)
                ?: throw StudioException(StudioErrorCode.FILE_NOT_FOUND, uri.toString())
            stream.use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
            if (!destFile.exists() || destFile.length() == 0L) {
                deleteFileQuietly(destFile)
                throw StudioException(StudioErrorCode.IO_READ_ERROR, "Kopyalanan dosya boş")
            }
            destFile
        } catch (se: StudioException) {
            deleteFileQuietly(destFile)
            throw se
        } catch (sec: SecurityException) {
            deleteFileQuietly(destFile)
            throw StudioException(StudioErrorCode.URI_PERMISSION_LOST, sec.message, sec)
        } catch (t: Throwable) {
            deleteFileQuietly(destFile)
            throw StudioException(StudioErrorCode.IO_READ_ERROR, t.message, t)
        }
    }

    suspend fun saveTempFileToPublicStorage(
        tempFile: File,
        desiredBaseName: String,
        extension: String,
        mediaType: MediaType
    ): SavedOutput = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "Geçici çıktı dosyası bulunamadı veya boş")
        }
        val ext = extension.lowercase(Locale.ROOT).trimStart('.')
        val mimeType = MediaFormatUtils.mimeTypeForExtension(ext)
        val safeBase = MediaFormatUtils.sanitizeBaseName(desiredBaseName)
        val fileLength = tempFile.length()
        ensureEnoughStorage(fileLength)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val primarySaved = trySaveViaMediaStoreQ(tempFile, safeBase, ext, mimeType, mediaType)
            if (primarySaved != null) {
                return@withContext primarySaved
            }
            val downloadsSaved = trySaveViaMediaStoreDownloadsQ(tempFile, safeBase, ext, mimeType)
            if (downloadsSaved != null) {
                return@withContext downloadsSaved
            }
        }

        saveToFallbackAppDirectory(tempFile, safeBase, ext, mimeType)
    }

    suspend fun saveTempFileToUri(
        tempFile: File,
        targetUri: Uri,
        displayNameFallback: String,
        mimeType: String
    ): SavedOutput = withContext(Dispatchers.IO) {
        if (!tempFile.exists() || tempFile.length() == 0L) {
            throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "Geçici çıktı dosyası boş")
        }
        try {
            val out = context.contentResolver.openOutputStream(targetUri, "wt")
                ?: throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "Hedef URI açılamadı")
            out.use { dst ->
                FileInputStream(tempFile).use { src ->
                    src.copyTo(dst, 64 * 1024)
                    dst.flush()
                }
            }
            val meta = resolveMediaInput(targetUri)
            SavedOutput(
                uriString = targetUri.toString(),
                displayName = meta.displayName.ifBlank { displayNameFallback },
                mimeType = mimeType,
                sizeBytes = tempFile.length()
            )
        } catch (se: StudioException) {
            throw se
        } catch (t: Throwable) {
            throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, t.message, t)
        }
    }

    private fun trySaveViaMediaStoreQ(
        tempFile: File,
        safeBase: String,
        ext: String,
        mimeType: String,
        mediaType: MediaType
    ): SavedOutput? {
        val (collection, relativePath) = when (mediaType) {
            MediaType.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MOVIES}/AvenlyxStudio"
            MediaType.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_MUSIC}/AvenlyxStudio"
            MediaType.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_PICTURES}/AvenlyxStudio"
            MediaType.UNKNOWN -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to
                "${Environment.DIRECTORY_DOWNLOADS}/AvenlyxStudio"
        }
        return insertIntoMediaStoreCollection(collection, relativePath, tempFile, safeBase, ext, mimeType)
    }

    private fun trySaveViaMediaStoreDownloadsQ(
        tempFile: File,
        safeBase: String,
        ext: String,
        mimeType: String
    ): SavedOutput? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/AvenlyxStudio"
        return insertIntoMediaStoreCollection(collection, relativePath, tempFile, safeBase, ext, mimeType)
    }

    private fun insertIntoMediaStoreCollection(
        collection: Uri,
        relativePath: String,
        tempFile: File,
        safeBase: String,
        ext: String,
        mimeType: String
    ): SavedOutput? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val existingNames = queryExistingNames(resolver, collection, safeBase)
        val uniqueName = MediaFormatUtils.buildUniqueDisplayName(safeBase, ext, existingNames)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, uniqueName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val itemUri = try {
            resolver.insert(collection, values)
        } catch (_: Exception) {
            null
        } ?: return null

        return try {
            val out = resolver.openOutputStream(itemUri)
                ?: throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "MediaStore çıktı akışı açılamadı")
            out.use { dst ->
                FileInputStream(tempFile).use { src ->
                    src.copyTo(dst, 64 * 1024)
                    dst.flush()
                }
            }
            val finishValues = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            resolver.update(itemUri, finishValues, null, null)
            SavedOutput(
                uriString = itemUri.toString(),
                displayName = uniqueName,
                mimeType = mimeType,
                sizeBytes = tempFile.length()
            )
        } catch (_: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            null
        }
    }

    private fun saveToFallbackAppDirectory(
        tempFile: File,
        safeBase: String,
        ext: String,
        mimeType: String
    ): SavedOutput {
        val dir = fallbackOutputDir
        val existingNames = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val uniqueName = MediaFormatUtils.buildUniqueDisplayName(safeBase, ext, existingNames)
        val stagedFile = File(dir, ".tmp_${UUID.randomUUID().toString().take(8)}_$uniqueName")
        val finalFile = File(dir, uniqueName)

        try {
            FileInputStream(tempFile).use { src ->
                FileOutputStream(stagedFile).use { dst ->
                    src.copyTo(dst, 64 * 1024)
                    dst.flush()
                }
            }
            if (!stagedFile.renameTo(finalFile)) {
                stagedFile.copyTo(finalFile, overwrite = true)
                stagedFile.delete()
            }
            val providerUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                finalFile
            )
            return SavedOutput(
                uriString = providerUri.toString(),
                displayName = uniqueName,
                mimeType = mimeType,
                sizeBytes = finalFile.length()
            )
        } catch (t: Throwable) {
            deleteFileQuietly(stagedFile)
            throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, t.message, t)
        }
    }

    private fun queryExistingNames(
        resolver: ContentResolver,
        collection: Uri,
        safeBase: String
    ): Set<String> {
        val names = mutableSetOf<String>()
        try {
            resolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                arrayOf("$safeBase%"),
                null
            )?.use { cursor ->
                val idx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (idx >= 0 && !cursor.isNull(idx)) {
                        names.add(cursor.getString(idx))
                    }
                }
            }
        } catch (_: Exception) {
            // Sorgu desteklenmiyorsa benzersizleştirme yine atomik insert tarafından korunur.
        }
        return names
    }

    suspend fun deleteOutputByUri(uriString: String): Boolean = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext false
        try {
            when {
                uri.scheme == ContentResolver.SCHEME_FILE -> {
                    uri.path?.let { File(it).delete() } ?: false
                }
                isAppFileProviderUri(uri) -> {
                    resolveLocalFileFromProviderUri(uri)?.delete() ?: false
                }
                uri.scheme == ContentResolver.SCHEME_CONTENT -> {
                    context.contentResolver.delete(uri, null, null) > 0
                }
                else -> false
            }
        } catch (_: Exception) {
            false
        }
    }

    suspend fun renameOutput(uriString: String, newNameRaw: String): Result<SavedOutput> =
        withContext(Dispatchers.IO) {
            runCatching {
                val uri = Uri.parse(uriString)
                val currentMeta = resolveMediaInput(uri)
                val currentExt = currentMeta.extension.ifBlank {
                    MediaFormatUtils.extensionFromFileName(newNameRaw)
                }
                val safeBase = MediaFormatUtils.sanitizeBaseName(newNameRaw)

                if (isAppFileProviderUri(uri)) {
                    val file = resolveLocalFileFromProviderUri(uri)
                        ?: throw StudioException(StudioErrorCode.FILE_NOT_FOUND)
                    val parent = file.parentFile ?: fallbackOutputDir
                    val existing = parent.listFiles()?.map { it.name }?.toSet().orEmpty() - file.name
                    val uniqueName = MediaFormatUtils.buildUniqueDisplayName(safeBase, currentExt, existing)
                    val target = File(parent, uniqueName)
                    if (!file.renameTo(target)) {
                        throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "Dosya adı değiştirilemedi")
                    }
                    val newUri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        target
                    )
                    return@runCatching SavedOutput(
                        uriString = newUri.toString(),
                        displayName = uniqueName,
                        mimeType = currentMeta.mimeType,
                        sizeBytes = target.length()
                    )
                }

                if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
                    val targetFileName = if (currentExt.isNotBlank()) "$safeBase.$currentExt" else safeBase
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, targetFileName)
                    }
                    val updatedRows = context.contentResolver.update(uri, values, null, null)
                    if (updatedRows <= 0) {
                        throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "MediaStore dosya adı güncellenemedi")
                    }
                    val refreshed = resolveMediaInput(uri)
                    return@runCatching SavedOutput(
                        uriString = uri.toString(),
                        displayName = refreshed.displayName.ifBlank { targetFileName },
                        mimeType = refreshed.mimeType,
                        sizeBytes = refreshed.sizeBytes
                    )
                }

                throw StudioException(StudioErrorCode.OUTPUT_CREATE_FAILED, "Desteklenmeyen URI şeması")
            }
        }

    fun getShareableUri(uriString: String): Uri {
        val parsed = Uri.parse(uriString)
        if (parsed.scheme == ContentResolver.SCHEME_FILE) {
            val file = parsed.path?.let { File(it) }
            if (file != null && file.exists()) {
                return runCatching {
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                }.getOrDefault(parsed)
            }
        }
        return parsed
    }

    fun getCacheSizeBytes(): Long {
        return folderSize(tempWorkDir) + folderSize(thumbnailsDir) + folderSize(diagnosticsDir)
    }

    fun clearTempCache(keepThumbnails: Boolean = false): Long {
        val before = getCacheSizeBytes()
        deleteContents(tempWorkDir)
        deleteContents(diagnosticsDir)
        if (!keepThumbnails) {
            deleteContents(thumbnailsDir)
        }
        val after = getCacheSizeBytes()
        return (before - after).coerceAtLeast(0L)
    }

    fun writeDiagnosticReportFile(reportText: String): Uri {
        val file = File(diagnosticsDir, "avenlyx_hata_raporu_${System.currentTimeMillis()}.txt")
        file.writeText(reportText, Charsets.UTF_8)
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    fun deleteFileQuietly(file: File?) {
        if (file != null && file.exists()) {
            runCatching { file.delete() }
        }
    }

    private fun isAppFileProviderUri(uri: Uri): Boolean {
        return uri.scheme == ContentResolver.SCHEME_CONTENT &&
            uri.authority == "${context.packageName}.fileprovider"
    }

    private fun resolveLocalFileFromProviderUri(uri: Uri): File? {
        val segments = uri.pathSegments
        if (segments.size < 2) return null
        val rootTag = segments.first()
        val relPath = segments.drop(1).joinToString(File.separator)
        val rootDir = when (rootTag) {
            "studio_cache" -> context.cacheDir
            "studio_files" -> context.filesDir
            "studio_external_files" -> context.getExternalFilesDir(null)
            "studio_external_cache" -> context.externalCacheDir
            else -> null
        } ?: return null
        return File(rootDir, relPath)
    }

    private fun folderSize(dir: File): Long {
        if (!dir.exists()) return 0L
        var total = 0L
        dir.listFiles()?.forEach { child ->
            total += if (child.isDirectory) folderSize(child) else child.length()
        }
        return total
    }

    private fun deleteContents(dir: File) {
        if (!dir.exists()) return
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                deleteContents(child)
            }
            runCatching { child.delete() }
        }
    }

    private fun extensionFromMime(mime: String): String {
        return when (mime.lowercase(Locale.ROOT)) {
            "video/mp4" -> "mp4"
            "video/webm" -> "webm"
            "video/x-matroska" -> "mkv"
            "video/quicktime" -> "mov"
            "audio/mp4", "audio/mp4a-latm" -> "m4a"
            "audio/wav", "audio/x-wav" -> "wav"
            "audio/mpeg" -> "mp3"
            "audio/ogg", "audio/opus" -> "opus"
            "audio/flac" -> "flac"
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/bmp" -> "bmp"
            else -> ""
        }
    }
}
