package com.formatci.mediastudio.media.engine

import android.content.Context
import com.formatci.mediastudio.core.files.FileStorageHelper
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaJobEvent
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.StudioTool
import com.formatci.mediastudio.media.capability.EngineCapabilityRegistry
import com.formatci.mediastudio.media.inspector.MediaInspector
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Gelen MediaJob türüne ve hedef formata göre işi ImageEngine, AndroidPlatformEngine
 * veya Media3Engine motoruna yönlendiren birleşik MediaEngine cephesi.
 */
class CompositeMediaEngine(
    context: Context,
    fileHelper: FileStorageHelper,
    val capabilityRegistry: EngineCapabilityRegistry = EngineCapabilityRegistry(),
    val inspector: MediaInspector = MediaInspector(context, fileHelper),
    val media3Engine: Media3Engine = Media3Engine(context, fileHelper, capabilityRegistry, inspector),
    val platformEngine: AndroidPlatformEngine = AndroidPlatformEngine(context, fileHelper, capabilityRegistry, inspector),
    val imageEngine: ImageEngine = ImageEngine(context, fileHelper, inspector, capabilityRegistry)
) : MediaEngine {

    override suspend fun inspect(input: MediaInput): MediaInfo {
        return inspector.inspect(input)
    }

    override suspend fun estimate(job: MediaJob): JobEstimate {
        return resolveTargetEngine(job).estimate(job)
    }

    override fun execute(job: MediaJob): Flow<MediaJobEvent> {
        return resolveTargetEngine(job).execute(job)
    }

    override suspend fun cancel(jobId: String) {
        media3Engine.cancel(jobId)
        platformEngine.cancel(jobId)
        imageEngine.cancel(jobId)
    }

    override fun capabilities(): EngineCapabilities {
        return capabilityRegistry.getCapabilities()
    }

    override suspend fun extractFrameToFile(uriString: String, positionMs: Long): Result<String> {
        return inspector.extractFrameToFile(uriString, positionMs)
    }

    private fun resolveTargetEngine(job: MediaJob): MediaEngine {
        return when (job.tool) {
            StudioTool.IMAGE_CONVERT,
            StudioTool.IMAGE_COMPRESS -> imageEngine

            StudioTool.AUDIO_CONVERT,
            StudioTool.AUDIO_EDIT_MERGE -> platformEngine

            StudioTool.VIDEO_EXTRACT_AUDIO -> {
                val ext = job.settings.outputFormat.lowercase(Locale.ROOT).trimStart('.')
                if (ext in setOf("wav", "opus", "flac")) platformEngine else media3Engine
            }

            StudioTool.VIDEO_CONVERT,
            StudioTool.VIDEO_EDIT,
            StudioTool.VIDEO_MERGE,
            StudioTool.VIDEO_TRIM_SPLIT,
            StudioTool.VIDEO_ADD_AUDIO,
            StudioTool.SLIDESHOW_MAKER -> media3Engine

            StudioTool.BATCH_CONVERT -> {
                val first = job.inputs.firstOrNull()
                val type = first?.mediaType?.takeIf { it != MediaType.UNKNOWN }
                    ?: first?.let { MediaFormatUtils.inferMediaType(it.extension, it.mimeType) }
                    ?: MediaType.VIDEO
                when (type) {
                    MediaType.IMAGE -> imageEngine
                    MediaType.AUDIO -> platformEngine
                    MediaType.VIDEO, MediaType.UNKNOWN -> media3Engine
                }
            }
        }
    }
}
