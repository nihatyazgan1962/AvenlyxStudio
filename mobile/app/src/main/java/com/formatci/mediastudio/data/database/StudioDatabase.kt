package com.formatci.mediastudio.data.database

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.formatci.mediastudio.core.error.StudioErrorCode
import com.formatci.mediastudio.core.error.StudioErrorMapper
import com.formatci.mediastudio.domain.model.AspectRatioOption
import com.formatci.mediastudio.domain.model.AudioFitMode
import com.formatci.mediastudio.domain.model.JobStatus
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaJobSettings
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.OutputRecord
import com.formatci.mediastudio.domain.model.QualityProfile
import com.formatci.mediastudio.domain.model.StudioTool
import com.formatci.mediastudio.domain.model.TimelineClipItem
import com.formatci.mediastudio.domain.model.TimelineProject
import org.json.JSONArray
import org.json.JSONObject

data class MediaFileEntity(
    val id: String,
    val uriString: String,
    val displayName: String,
    val extension: String,
    val mimeType: String,
    val sizeBytes: Long,
    val mediaType: String,
    val importedAtMs: Long
)

data class MediaJobEntity(
    val id: String,
    val tool: String,
    val title: String,
    val inputsJson: String,
    val settingsJson: String,
    val clipsJson: String,
    val status: String,
    val progress: Float,
    val isIndeterminate: Boolean,
    val statusMessage: String,
    val queueOrder: Int,
    val createdAtMs: Long,
    val startedAtMs: Long?,
    val finishedAtMs: Long?,
    val outputRecordId: String?,
    val outputUriString: String?,
    val outputDisplayName: String?,
    val outputSizeBytes: Long?,
    val errorCode: String?,
    val errorMessage: String?,
    val errorSuggestion: String?,
    val wasInterrupted: Boolean
)

data class ProjectEntity(
    val id: String,
    val schemaVersion: Int,
    val name: String,
    val payloadJson: String,
    val updatedAtMs: Long
)

data class OutputHistoryEntity(
    val id: String,
    val jobId: String,
    val tool: String,
    val mediaType: String,
    val displayName: String,
    val format: String,
    val mimeType: String,
    val uriString: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val createdAtMs: Long
)

object StudioJsonCodec {

    fun encodeMediaInput(input: MediaInput): JSONObject = JSONObject().apply {
        put("id", input.id)
        put("uriString", input.uriString)
        put("displayName", input.displayName)
        put("extension", input.extension)
        put("mimeType", input.mimeType)
        put("sizeBytes", input.sizeBytes)
        put("mediaType", input.mediaType.name)
        put("importedAtMs", input.importedAtMs)
    }

    fun decodeMediaInput(obj: JSONObject): MediaInput = MediaInput(
        id = obj.optString("id"),
        uriString = obj.optString("uriString"),
        displayName = obj.optString("displayName"),
        extension = obj.optString("extension"),
        mimeType = obj.optString("mimeType"),
        sizeBytes = obj.optLong("sizeBytes", 0L),
        mediaType = enumOrDefault(obj.optString("mediaType"), MediaType.UNKNOWN),
        importedAtMs = obj.optLong("importedAtMs", 0L)
    )

    fun encodeMediaInputs(inputs: List<MediaInput>): String {
        val arr = JSONArray()
        inputs.forEach { arr.put(encodeMediaInput(it)) }
        return arr.toString()
    }

    fun decodeMediaInputs(json: String): List<MediaInput> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { idx -> decodeMediaInput(arr.getJSONObject(idx)) }
    }

    fun encodeJobSettings(s: MediaJobSettings): JSONObject = JSONObject().apply {
        put("outputFormat", s.outputFormat)
        put("outputFileName", s.outputFileName)
        put("qualityProfile", s.qualityProfile.name)
        put("videoCodec", s.videoCodec)
        put("targetWidth", s.targetWidth ?: JSONObject.NULL)
        put("targetHeight", s.targetHeight ?: JSONObject.NULL)
        put("targetFps", s.targetFps ?: JSONObject.NULL)
        put("videoBitrateBps", s.videoBitrateBps ?: JSONObject.NULL)
        put("aspectRatio", s.aspectRatio.name)
        put("trimStartMs", s.trimStartMs)
        put("trimEndMs", s.trimEndMs ?: JSONObject.NULL)
        put("playbackSpeed", s.playbackSpeed.toDouble())
        put("rotateDegrees", s.rotateDegrees)
        put("flipHorizontal", s.flipHorizontal)
        put("flipVertical", s.flipVertical)
        put("brightness", s.brightness.toDouble())
        put("contrast", s.contrast.toDouble())
        put("saturation", s.saturation.toDouble())
        put("grayscale", s.grayscale)
        put("sepia", s.sepia)
        put("watermarkText", s.watermarkText)
        put("watermarkOpacity", s.watermarkOpacity.toDouble())
        put("removeAudio", s.removeAudio)
        put("audioVolume", s.audioVolume.toDouble())
        put("audioCodec", s.audioCodec)
        put("audioBitrateBps", s.audioBitrateBps)
        put("audioSampleRateHz", s.audioSampleRateHz)
        put("audioChannels", s.audioChannels)
        put("fadeInMs", s.fadeInMs)
        put("fadeOutMs", s.fadeOutMs)
        put("secondaryAudioUri", s.secondaryAudioUri ?: JSONObject.NULL)
        put("secondaryAudioVolume", s.secondaryAudioVolume.toDouble())
        put("audioFitMode", s.audioFitMode.name)
        put("mixAudioInsteadOfConcat", s.mixAudioInsteadOfConcat)
        put("imageQuality", s.imageQuality)
        put("imageScalePercent", s.imageScalePercent)
        put("stripExif", s.stripExif)
        put("jpegBackgroundColorArgb", s.jpegBackgroundColorArgb)
        put("slideDurationMs", s.slideDurationMs)
        put("slideshowFps", s.slideshowFps)
    }

    fun decodeJobSettings(obj: JSONObject): MediaJobSettings = MediaJobSettings(
        outputFormat = obj.optString("outputFormat", "mp4"),
        outputFileName = obj.optString("outputFileName", ""),
        qualityProfile = enumOrDefault(obj.optString("qualityProfile"), QualityProfile.BALANCED),
        videoCodec = obj.optString("videoCodec", "video/avc"),
        targetWidth = if (obj.isNull("targetWidth")) null else obj.optInt("targetWidth"),
        targetHeight = if (obj.isNull("targetHeight")) null else obj.optInt("targetHeight"),
        targetFps = if (obj.isNull("targetFps")) null else obj.optInt("targetFps"),
        videoBitrateBps = if (obj.isNull("videoBitrateBps")) null else obj.optInt("videoBitrateBps"),
        aspectRatio = enumOrDefault(obj.optString("aspectRatio"), AspectRatioOption.ORIGINAL),
        trimStartMs = obj.optLong("trimStartMs", 0L),
        trimEndMs = if (obj.isNull("trimEndMs")) null else obj.optLong("trimEndMs"),
        playbackSpeed = obj.optDouble("playbackSpeed", 1.0).toFloat(),
        rotateDegrees = obj.optInt("rotateDegrees", 0),
        flipHorizontal = obj.optBoolean("flipHorizontal", false),
        flipVertical = obj.optBoolean("flipVertical", false),
        brightness = obj.optDouble("brightness", 0.0).toFloat(),
        contrast = obj.optDouble("contrast", 1.0).toFloat(),
        saturation = obj.optDouble("saturation", 1.0).toFloat(),
        grayscale = obj.optBoolean("grayscale", false),
        sepia = obj.optBoolean("sepia", false),
        watermarkText = obj.optString("watermarkText", ""),
        watermarkOpacity = obj.optDouble("watermarkOpacity", 0.8).toFloat(),
        removeAudio = obj.optBoolean("removeAudio", false),
        audioVolume = obj.optDouble("audioVolume", 1.0).toFloat(),
        audioCodec = obj.optString("audioCodec", "audio/mp4a-latm"),
        audioBitrateBps = obj.optInt("audioBitrateBps", 192_000),
        audioSampleRateHz = obj.optInt("audioSampleRateHz", 44_100),
        audioChannels = obj.optInt("audioChannels", 2),
        fadeInMs = obj.optLong("fadeInMs", 0L),
        fadeOutMs = obj.optLong("fadeOutMs", 0L),
        secondaryAudioUri = if (obj.isNull("secondaryAudioUri")) null else obj.optString("secondaryAudioUri"),
        secondaryAudioVolume = obj.optDouble("secondaryAudioVolume", 0.7).toFloat(),
        audioFitMode = enumOrDefault(obj.optString("audioFitMode"), AudioFitMode.TRIM),
        mixAudioInsteadOfConcat = obj.optBoolean("mixAudioInsteadOfConcat", false),
        imageQuality = obj.optInt("imageQuality", 85),
        imageScalePercent = obj.optInt("imageScalePercent", 100),
        stripExif = obj.optBoolean("stripExif", false),
        jpegBackgroundColorArgb = obj.optInt("jpegBackgroundColorArgb", 0xFFFFFFFF.toInt()),
        slideDurationMs = obj.optLong("slideDurationMs", 3_000L),
        slideshowFps = obj.optInt("slideshowFps", 30)
    )

    fun encodeClip(clip: TimelineClipItem): JSONObject = JSONObject().apply {
        put("clipId", clip.clipId)
        put("input", encodeMediaInput(clip.input))
        put("durationMs", clip.durationMs)
        put("trimStartMs", clip.trimStartMs)
        put("trimEndMs", clip.trimEndMs)
        put("speed", clip.speed.toDouble())
        put("volume", clip.volume.toDouble())
        put("muted", clip.muted)
        put("rotateDegrees", clip.rotateDegrees)
        put("flipHorizontal", clip.flipHorizontal)
        put("flipVertical", clip.flipVertical)
        put("brightness", clip.brightness.toDouble())
        put("contrast", clip.contrast.toDouble())
        put("saturation", clip.saturation.toDouble())
        put("grayscale", clip.grayscale)
        put("sepia", clip.sepia)
        put("watermarkText", clip.watermarkText)
        put("thumbnailPath", clip.thumbnailPath ?: JSONObject.NULL)
    }

    fun decodeClip(obj: JSONObject): TimelineClipItem = TimelineClipItem(
        clipId = obj.optString("clipId"),
        input = decodeMediaInput(obj.getJSONObject("input")),
        durationMs = obj.optLong("durationMs", 0L),
        trimStartMs = obj.optLong("trimStartMs", 0L),
        trimEndMs = obj.optLong("trimEndMs", 0L),
        speed = obj.optDouble("speed", 1.0).toFloat(),
        volume = obj.optDouble("volume", 1.0).toFloat(),
        muted = obj.optBoolean("muted", false),
        rotateDegrees = obj.optInt("rotateDegrees", 0),
        flipHorizontal = obj.optBoolean("flipHorizontal", false),
        flipVertical = obj.optBoolean("flipVertical", false),
        brightness = obj.optDouble("brightness", 0.0).toFloat(),
        contrast = obj.optDouble("contrast", 1.0).toFloat(),
        saturation = obj.optDouble("saturation", 1.0).toFloat(),
        grayscale = obj.optBoolean("grayscale", false),
        sepia = obj.optBoolean("sepia", false),
        watermarkText = obj.optString("watermarkText", ""),
        thumbnailPath = if (obj.isNull("thumbnailPath")) null else obj.optString("thumbnailPath")
    )

    fun encodeClips(clips: List<TimelineClipItem>): String {
        val arr = JSONArray()
        clips.forEach { arr.put(encodeClip(it)) }
        return arr.toString()
    }

    fun decodeClips(json: String): List<TimelineClipItem> {
        if (json.isBlank()) return emptyList()
        val arr = JSONArray(json)
        return List(arr.length()) { idx -> decodeClip(arr.getJSONObject(idx)) }
    }

    fun encodeProject(project: TimelineProject): String = JSONObject().apply {
        put("id", project.id)
        put("schemaVersion", project.schemaVersion)
        put("name", project.name)
        val clipsArr = JSONArray()
        project.clips.forEach { clipsArr.put(encodeClip(it)) }
        put("clips", clipsArr)
        put("externalAudioInput", project.externalAudioInput?.let { encodeMediaInput(it) } ?: JSONObject.NULL)
        put("externalAudioVolume", project.externalAudioVolume.toDouble())
        put("audioFitMode", project.audioFitMode.name)
        put("exportSettings", encodeJobSettings(project.exportSettings))
        put("updatedAtMs", project.updatedAtMs)
    }.toString()

    fun decodeProject(json: String, fallbackSchemaVersion: Int = TimelineProject.CURRENT_SCHEMA_VERSION): TimelineProject {
        val obj = JSONObject(json)
        val clipsArr = obj.optJSONArray("clips") ?: JSONArray()
        val clips = List(clipsArr.length()) { idx -> decodeClip(clipsArr.getJSONObject(idx)) }
        val extAudioObj = if (obj.isNull("externalAudioInput")) null else obj.optJSONObject("externalAudioInput")
        val settingsObj = obj.optJSONObject("exportSettings") ?: JSONObject()
        return TimelineProject(
            id = obj.optString("id", TimelineProject.ACTIVE_PROJECT_ID),
            schemaVersion = obj.optInt("schemaVersion", fallbackSchemaVersion),
            name = obj.optString("name", "Yeni Stüdyo Projesi"),
            clips = clips,
            externalAudioInput = extAudioObj?.let { decodeMediaInput(it) },
            externalAudioVolume = obj.optDouble("externalAudioVolume", 0.7).toFloat(),
            audioFitMode = enumOrDefault(obj.optString("audioFitMode"), AudioFitMode.TRIM),
            exportSettings = decodeJobSettings(settingsObj),
            updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
        )
    }

    inline fun <reified T : Enum<T>> enumOrDefault(raw: String?, default: T): T {
        if (raw.isNullOrBlank()) return default
        return runCatching { enumValueOf<T>(raw) }.getOrDefault(default)
    }
}

class StudioDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    private val lock = Any()

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MEDIA_FILES (
                id TEXT PRIMARY KEY,
                uri_string TEXT UNIQUE NOT NULL,
                display_name TEXT NOT NULL,
                extension TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                media_type TEXT NOT NULL,
                imported_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_MEDIA_JOBS (
                id TEXT PRIMARY KEY,
                tool TEXT NOT NULL,
                title TEXT NOT NULL,
                inputs_json TEXT NOT NULL,
                settings_json TEXT NOT NULL,
                clips_json TEXT NOT NULL,
                status TEXT NOT NULL,
                progress REAL NOT NULL,
                is_indeterminate INTEGER NOT NULL,
                status_message TEXT NOT NULL,
                queue_order INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL,
                started_at_ms INTEGER,
                finished_at_ms INTEGER,
                output_record_id TEXT,
                output_uri_string TEXT,
                output_display_name TEXT,
                output_size_bytes INTEGER,
                error_code TEXT,
                error_message TEXT,
                error_suggestion TEXT,
                was_interrupted INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_PROJECTS (
                id TEXT PRIMARY KEY,
                schema_version INTEGER NOT NULL,
                name TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                updated_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_OUTPUT_HISTORY (
                id TEXT PRIMARY KEY,
                job_id TEXT NOT NULL,
                tool TEXT NOT NULL,
                media_type TEXT NOT NULL,
                display_name TEXT NOT NULL,
                format TEXT NOT NULL,
                mime_type TEXT NOT NULL,
                uri_string TEXT NOT NULL,
                size_bytes INTEGER NOT NULL,
                duration_ms INTEGER NOT NULL,
                width INTEGER NOT NULL,
                height INTEGER NOT NULL,
                created_at_ms INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_order ON $TABLE_MEDIA_JOBS(queue_order, created_at_ms)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_history_created ON $TABLE_OUTPUT_HISTORY(created_at_ms DESC)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_files_imported ON $TABLE_MEDIA_FILES(imported_at_ms DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onCreate(db)
    }

    // region MediaFileEntity DAO
    fun upsertRecentFile(input: MediaInput) = synchronized(lock) {
        val entity = MediaFileEntity(
            id = input.id,
            uriString = input.uriString,
            displayName = input.displayName,
            extension = input.extension,
            mimeType = input.mimeType,
            sizeBytes = input.sizeBytes,
            mediaType = input.mediaType.name,
            importedAtMs = input.importedAtMs
        )
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(TABLE_MEDIA_FILES, "uri_string = ?", arrayOf(entity.uriString))
            val cv = ContentValues().apply {
                put("id", entity.id)
                put("uri_string", entity.uriString)
                put("display_name", entity.displayName)
                put("extension", entity.extension)
                put("mime_type", entity.mimeType)
                put("size_bytes", entity.sizeBytes)
                put("media_type", entity.mediaType)
                put("imported_at_ms", entity.importedAtMs)
            }
            writableDatabase.insertWithOnConflict(TABLE_MEDIA_FILES, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun getRecentFiles(limit: Int = 30): List<MediaInput> = synchronized(lock) {
        val list = mutableListOf<MediaInput>()
        readableDatabase.query(
            TABLE_MEDIA_FILES,
            null,
            null,
            null,
            null,
            null,
            "imported_at_ms DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) {
                list.add(cursor.toMediaInput())
            }
        }
        list
    }

    fun deleteRecentFile(id: String) = synchronized(lock) {
        writableDatabase.delete(TABLE_MEDIA_FILES, "id = ?", arrayOf(id))
    }
    // endregion

    // region MediaJobEntity DAO
    fun upsertJob(job: MediaJob) = synchronized(lock) {
        val entity = job.toEntity()
        writableDatabase.insertWithOnConflict(
            TABLE_MEDIA_JOBS,
            null,
            entity.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun upsertJobs(jobs: List<MediaJob>) = synchronized(lock) {
        writableDatabase.beginTransaction()
        try {
            jobs.forEach { job ->
                writableDatabase.insertWithOnConflict(
                    TABLE_MEDIA_JOBS,
                    null,
                    job.toEntity().toContentValues(),
                    SQLiteDatabase.CONFLICT_REPLACE
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun getAllJobs(): List<MediaJob> = synchronized(lock) {
        val list = mutableListOf<MediaJob>()
        readableDatabase.query(
            TABLE_MEDIA_JOBS,
            null,
            null,
            null,
            null,
            null,
            "queue_order ASC, created_at_ms DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { cursor.toMediaJob() }.getOrNull()?.let { list.add(it) }
            }
        }
        list
    }

    fun deleteJob(jobId: String) = synchronized(lock) {
        writableDatabase.delete(TABLE_MEDIA_JOBS, "id = ?", arrayOf(jobId))
    }

    fun clearFinishedJobs() = synchronized(lock) {
        writableDatabase.delete(
            TABLE_MEDIA_JOBS,
            "status IN (?, ?, ?)",
            arrayOf(JobStatus.COMPLETED.name, JobStatus.CANCELLED.name, JobStatus.FAILED.name)
        )
    }

    /**
     * Uygulama yeniden başladığında önceki oturumda PROCESSING/PREPARING aşamasında
     * yarım kalan işleri kurtararak yeniden denenebilir / devam ettirilebilir duruma getirir.
     */
    fun recoverInterruptedJobs(): List<MediaJob> = synchronized(lock) {
        val interruptedErr = StudioErrorMapper.fromCode(StudioErrorCode.SERVICE_STOPPED)
        val cv = ContentValues().apply {
            put("status", JobStatus.FAILED.name)
            put("is_indeterminate", 0)
            put("status_message", "Önceki oturumda yarım kaldı (devam ettirilebilir)")
            put("finished_at_ms", System.currentTimeMillis())
            put("error_code", interruptedErr.code.name)
            put("error_message", interruptedErr.messageTr)
            put("error_suggestion", interruptedErr.suggestionTr)
            put("was_interrupted", 1)
        }
        writableDatabase.update(
            TABLE_MEDIA_JOBS,
            cv,
            "status IN (?, ?)",
            arrayOf(JobStatus.PROCESSING.name, JobStatus.PREPARING.name)
        )
        getAllJobs()
    }
    // endregion

    // region ProjectEntity DAO
    fun saveProject(project: TimelineProject) = synchronized(lock) {
        val entity = ProjectEntity(
            id = project.id,
            schemaVersion = project.schemaVersion,
            name = project.name,
            payloadJson = StudioJsonCodec.encodeProject(project),
            updatedAtMs = project.updatedAtMs
        )
        val cv = ContentValues().apply {
            put("id", entity.id)
            put("schema_version", entity.schemaVersion)
            put("name", entity.name)
            put("payload_json", entity.payloadJson)
            put("updated_at_ms", entity.updatedAtMs)
        }
        writableDatabase.insertWithOnConflict(TABLE_PROJECTS, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun loadProject(projectId: String = TimelineProject.ACTIVE_PROJECT_ID): TimelineProject? = synchronized(lock) {
        readableDatabase.query(
            TABLE_PROJECTS,
            null,
            "id = ?",
            arrayOf(projectId),
            null,
            null,
            null
        ).use { cursor ->
            if (!cursor.moveToFirst()) return@synchronized null
            val schemaVersion = cursor.getInt(cursor.getColumnIndexOrThrow("schema_version"))
            val payload = cursor.getString(cursor.getColumnIndexOrThrow("payload_json"))
            runCatching { StudioJsonCodec.decodeProject(payload, schemaVersion) }.getOrNull()
        }
    }
    // endregion

    // region OutputHistoryEntity DAO
    fun insertOutput(record: OutputRecord) = synchronized(lock) {
        val entity = OutputHistoryEntity(
            id = record.id,
            jobId = record.jobId,
            tool = record.tool.name,
            mediaType = record.mediaType.name,
            displayName = record.displayName,
            format = record.format,
            mimeType = record.mimeType,
            uriString = record.uriString,
            sizeBytes = record.sizeBytes,
            durationMs = record.durationMs,
            width = record.width,
            height = record.height,
            createdAtMs = record.createdAtMs
        )
        val cv = ContentValues().apply {
            put("id", entity.id)
            put("job_id", entity.jobId)
            put("tool", entity.tool)
            put("media_type", entity.mediaType)
            put("display_name", entity.displayName)
            put("format", entity.format)
            put("mime_type", entity.mimeType)
            put("uri_string", entity.uriString)
            put("size_bytes", entity.sizeBytes)
            put("duration_ms", entity.durationMs)
            put("width", entity.width)
            put("height", entity.height)
            put("created_at_ms", entity.createdAtMs)
        }
        writableDatabase.insertWithOnConflict(TABLE_OUTPUT_HISTORY, null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getAllOutputs(): List<OutputRecord> = synchronized(lock) {
        val list = mutableListOf<OutputRecord>()
        readableDatabase.query(
            TABLE_OUTPUT_HISTORY,
            null,
            null,
            null,
            null,
            null,
            "created_at_ms DESC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { cursor.toOutputRecord() }.getOrNull()?.let { list.add(it) }
            }
        }
        list
    }

    fun updateOutputName(id: String, newName: String, newUriString: String? = null) = synchronized(lock) {
        val cv = ContentValues().apply {
            put("display_name", newName)
            if (newUriString != null) {
                put("uri_string", newUriString)
            }
        }
        writableDatabase.update(TABLE_OUTPUT_HISTORY, cv, "id = ?", arrayOf(id))
    }

    fun deleteOutput(id: String) = synchronized(lock) {
        writableDatabase.delete(TABLE_OUTPUT_HISTORY, "id = ?", arrayOf(id))
    }
    // endregion

    private fun MediaJob.toEntity(): MediaJobEntity = MediaJobEntity(
        id = id,
        tool = tool.name,
        title = title,
        inputsJson = StudioJsonCodec.encodeMediaInputs(inputs),
        settingsJson = StudioJsonCodec.encodeJobSettings(settings).toString(),
        clipsJson = StudioJsonCodec.encodeClips(timelineClips),
        status = status.name,
        progress = progress,
        isIndeterminate = isIndeterminate,
        statusMessage = statusMessage,
        queueOrder = queueOrder,
        createdAtMs = createdAtMs,
        startedAtMs = startedAtMs,
        finishedAtMs = finishedAtMs,
        outputRecordId = outputRecordId,
        outputUriString = outputUriString,
        outputDisplayName = outputDisplayName,
        outputSizeBytes = outputSizeBytes,
        errorCode = errorCode,
        errorMessage = errorMessage,
        errorSuggestion = errorSuggestion,
        wasInterrupted = wasInterrupted
    )

    private fun MediaJobEntity.toContentValues(): ContentValues = ContentValues().apply {
        put("id", id)
        put("tool", tool)
        put("title", title)
        put("inputs_json", inputsJson)
        put("settings_json", settingsJson)
        put("clips_json", clipsJson)
        put("status", status)
        put("progress", progress)
        put("is_indeterminate", if (isIndeterminate) 1 else 0)
        put("status_message", statusMessage)
        put("queue_order", queueOrder)
        put("created_at_ms", createdAtMs)
        if (startedAtMs != null) put("started_at_ms", startedAtMs) else putNull("started_at_ms")
        if (finishedAtMs != null) put("finished_at_ms", finishedAtMs) else putNull("finished_at_ms")
        if (outputRecordId != null) put("output_record_id", outputRecordId) else putNull("output_record_id")
        if (outputUriString != null) put("output_uri_string", outputUriString) else putNull("output_uri_string")
        if (outputDisplayName != null) put("output_display_name", outputDisplayName) else putNull("output_display_name")
        if (outputSizeBytes != null) put("output_size_bytes", outputSizeBytes) else putNull("output_size_bytes")
        if (errorCode != null) put("error_code", errorCode) else putNull("error_code")
        if (errorMessage != null) put("error_message", errorMessage) else putNull("error_message")
        if (errorSuggestion != null) put("error_suggestion", errorSuggestion) else putNull("error_suggestion")
        put("was_interrupted", if (wasInterrupted) 1 else 0)
    }

    private fun Cursor.toMediaInput(): MediaInput = MediaInput(
        id = getString(getColumnIndexOrThrow("id")),
        uriString = getString(getColumnIndexOrThrow("uri_string")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        extension = getString(getColumnIndexOrThrow("extension")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        mediaType = StudioJsonCodec.enumOrDefault(
            getString(getColumnIndexOrThrow("media_type")),
            MediaType.UNKNOWN
        ),
        importedAtMs = getLong(getColumnIndexOrThrow("imported_at_ms"))
    )

    private fun Cursor.toMediaJob(): MediaJob = MediaJob(
        id = getString(getColumnIndexOrThrow("id")),
        tool = StudioJsonCodec.enumOrDefault(getString(getColumnIndexOrThrow("tool")), StudioTool.VIDEO_CONVERT),
        title = getString(getColumnIndexOrThrow("title")),
        inputs = StudioJsonCodec.decodeMediaInputs(getString(getColumnIndexOrThrow("inputs_json"))),
        settings = StudioJsonCodec.decodeJobSettings(JSONObject(getString(getColumnIndexOrThrow("settings_json")))),
        timelineClips = StudioJsonCodec.decodeClips(getString(getColumnIndexOrThrow("clips_json"))),
        status = StudioJsonCodec.enumOrDefault(getString(getColumnIndexOrThrow("status")), JobStatus.QUEUED),
        progress = getFloat(getColumnIndexOrThrow("progress")),
        isIndeterminate = getInt(getColumnIndexOrThrow("is_indeterminate")) == 1,
        statusMessage = getString(getColumnIndexOrThrow("status_message")),
        queueOrder = getInt(getColumnIndexOrThrow("queue_order")),
        createdAtMs = getLong(getColumnIndexOrThrow("created_at_ms")),
        startedAtMs = getNullableLong("started_at_ms"),
        finishedAtMs = getNullableLong("finished_at_ms"),
        outputRecordId = getNullableString("output_record_id"),
        outputUriString = getNullableString("output_uri_string"),
        outputDisplayName = getNullableString("output_display_name"),
        outputSizeBytes = getNullableLong("output_size_bytes"),
        errorCode = getNullableString("error_code"),
        errorMessage = getNullableString("error_message"),
        errorSuggestion = getNullableString("error_suggestion"),
        wasInterrupted = getInt(getColumnIndexOrThrow("was_interrupted")) == 1
    )

    private fun Cursor.toOutputRecord(): OutputRecord = OutputRecord(
        id = getString(getColumnIndexOrThrow("id")),
        jobId = getString(getColumnIndexOrThrow("job_id")),
        tool = StudioJsonCodec.enumOrDefault(getString(getColumnIndexOrThrow("tool")), StudioTool.VIDEO_CONVERT),
        mediaType = StudioJsonCodec.enumOrDefault(getString(getColumnIndexOrThrow("media_type")), MediaType.VIDEO),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        format = getString(getColumnIndexOrThrow("format")),
        mimeType = getString(getColumnIndexOrThrow("mime_type")),
        uriString = getString(getColumnIndexOrThrow("uri_string")),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        durationMs = getLong(getColumnIndexOrThrow("duration_ms")),
        width = getInt(getColumnIndexOrThrow("width")),
        height = getInt(getColumnIndexOrThrow("height")),
        createdAtMs = getLong(getColumnIndexOrThrow("created_at_ms"))
    )

    private fun Cursor.getNullableLong(column: String): Long? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getLong(idx)
    }

    private fun Cursor.getNullableString(column: String): String? {
        val idx = getColumnIndexOrThrow(column)
        return if (isNull(idx)) null else getString(idx)
    }

    companion object {
        private const val DB_NAME = "formatci_studio.db"
        private const val DB_VERSION = 1

        private const val TABLE_MEDIA_FILES = "media_files"
        private const val TABLE_MEDIA_JOBS = "media_jobs"
        private const val TABLE_PROJECTS = "projects"
        private const val TABLE_OUTPUT_HISTORY = "output_history"
    }
}

