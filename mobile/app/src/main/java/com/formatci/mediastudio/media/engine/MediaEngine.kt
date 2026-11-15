package com.formatci.mediastudio.media.engine

import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaJobEvent
import kotlinx.coroutines.flow.Flow

/**
 * Tüm medya inceleme, boyut/süre tahmini, işlem yürütme ve iptal akışlarını
 * birleştiren soyut medya motoru sözleşmesi.
 */
interface MediaEngine {
    suspend fun inspect(input: MediaInput): MediaInfo
    suspend fun estimate(job: MediaJob): JobEstimate
    fun execute(job: MediaJob): Flow<MediaJobEvent>
    suspend fun cancel(jobId: String)
    fun capabilities(): EngineCapabilities
    suspend fun extractFrameToFile(uriString: String, positionMs: Long): Result<String>
}
