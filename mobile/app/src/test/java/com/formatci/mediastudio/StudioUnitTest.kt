package com.formatci.mediastudio

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.formatci.mediastudio.core.error.StudioErrorCode
import com.formatci.mediastudio.core.error.StudioErrorMapper
import com.formatci.mediastudio.core.error.StudioException
import com.formatci.mediastudio.core.files.FileStorageHelper
import com.formatci.mediastudio.data.database.StudioDatabase
import com.formatci.mediastudio.data.preferences.UserPreferencesRepository
import com.formatci.mediastudio.data.repository.StudioRepository
import com.formatci.mediastudio.domain.model.AppSettings
import com.formatci.mediastudio.domain.model.AspectRatioOption
import com.formatci.mediastudio.domain.model.AudioFitMode
import com.formatci.mediastudio.domain.model.EngineCapabilities
import com.formatci.mediastudio.domain.model.JobEstimate
import com.formatci.mediastudio.domain.model.JobStatus
import com.formatci.mediastudio.domain.model.MediaFormatUtils
import com.formatci.mediastudio.domain.model.MediaInfo
import com.formatci.mediastudio.domain.model.MediaInput
import com.formatci.mediastudio.domain.model.MediaJob
import com.formatci.mediastudio.domain.model.MediaJobEvent
import com.formatci.mediastudio.domain.model.MediaJobSettings
import com.formatci.mediastudio.domain.model.MediaType
import com.formatci.mediastudio.domain.model.QualityProfile
import com.formatci.mediastudio.domain.model.StudioTool
import com.formatci.mediastudio.domain.model.ThemePreference
import com.formatci.mediastudio.domain.model.TimelineClipItem
import com.formatci.mediastudio.domain.model.TimelineProject
import com.formatci.mediastudio.media.engine.AudioPcmUtils
import com.formatci.mediastudio.media.engine.MediaEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class StudioUnitTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // region 1. Domain Model Validations

    @Test
    fun `studio tools contain all 12 tools with non-blank Turkish metadata`() {
        val tools = StudioTool.entries
        assertEquals(12, tools.size)
        tools.forEach { tool ->
            assertTrue("Başlık boş olmamalı: $tool", tool.titleTr.isNotBlank())
            assertTrue("Alt başlık boş olmamalı: $tool", tool.subtitleTr.isNotBlank())
        }
        assertEquals(MediaType.VIDEO, StudioTool.VIDEO_CONVERT.mediaType)
        assertEquals(MediaType.VIDEO, StudioTool.SLIDESHOW_MAKER.mediaType)
        assertEquals(MediaType.AUDIO, StudioTool.AUDIO_CONVERT.mediaType)
        assertEquals(MediaType.IMAGE, StudioTool.IMAGE_CONVERT.mediaType)
        assertEquals(MediaType.UNKNOWN, StudioTool.BATCH_CONVERT.mediaType)
    }

    @Test
    fun `job status active and terminal flags are mutually exclusive`() {
        JobStatus.entries.forEach { status ->
            assertTrue(
                "Durum hem aktif hem terminal olamaz: $status",
                status.isActive != status.isTerminal
            )
            assertTrue(status.labelTr.isNotBlank())
        }
        assertTrue(JobStatus.QUEUED.isActive)
        assertTrue(JobStatus.PREPARING.isActive)
        assertTrue(JobStatus.PROCESSING.isActive)
        assertTrue(JobStatus.COMPLETED.isTerminal)
        assertTrue(JobStatus.CANCELLED.isTerminal)
        assertTrue(JobStatus.FAILED.isTerminal)
    }

    @Test
    fun `quality profiles, aspect ratios, audio fit modes and themes have valid defaults`() {
        assertTrue(QualityProfile.SMALL_FILE.bitrateMultiplier < QualityProfile.BALANCED.bitrateMultiplier)
        assertTrue(QualityProfile.BALANCED.bitrateMultiplier < QualityProfile.HIGH_QUALITY.bitrateMultiplier)
        assertTrue(QualityProfile.SMALL_FILE.imageQuality < QualityProfile.HIGH_QUALITY.imageQuality)

        assertEquals(0, AspectRatioOption.ORIGINAL.ratioW)
        assertEquals(16, AspectRatioOption.RATIO_16_9.ratioW)
        assertEquals(9, AspectRatioOption.RATIO_16_9.ratioH)
        assertEquals(3, AudioFitMode.entries.size)
        assertEquals(3, ThemePreference.entries.size)
    }

    @Test
    fun `timeline clip effectiveDurationMs respects trim, speed and minimum clamp`() {
        val dummyInput = MediaInput(
            uriString = "content://media/video/1",
            displayName = "ornek.mp4",
            extension = "mp4",
            mediaType = MediaType.VIDEO
        )
        val normalClip = TimelineClipItem(
            input = dummyInput,
            durationMs = 10_000L,
            trimStartMs = 2_000L,
            trimEndMs = 8_000L,
            speed = 1.0f
        )
        assertEquals(6_000L, normalClip.effectiveDurationMs)

        val fastClip = normalClip.copy(speed = 2.0f)
        assertEquals(3_000L, fastClip.effectiveDurationMs)

        val slowClip = normalClip.copy(speed = 0.5f)
        assertEquals(12_000L, slowClip.effectiveDurationMs)

        val tinyClip = normalClip.copy(trimStartMs = 5_000L, trimEndMs = 5_020L, speed = 4.0f)
        assertEquals(100L, tinyClip.effectiveDurationMs)

        val project = TimelineProject(clips = listOf(normalClip, fastClip))
        assertEquals(9_000L, project.totalDurationMs)
    }

    @Test
    fun `engine capabilities default conservatively without false crossfade or reverse support`() {
        val defaults = EngineCapabilities()
        assertFalse(defaults.supportsCrossfade)
        assertFalse(defaults.supportsReversePlayback)
        assertTrue("mp4" in defaults.supportedVideoOutputFormats)
        assertTrue("m4a" in defaults.supportedAudioOutputFormats)
        assertTrue("wav" in defaults.supportedAudioOutputFormats)
        assertEquals(listOf("jpg", "png", "webp", "bmp"), defaults.supportedImageOutputFormats)
    }

    @Test
    fun `media info display helpers format resolution, duration and size`() {
        val input = MediaInput(
            uriString = "file:///sample.mp4",
            displayName = "sample.mp4",
            extension = "mp4",
            sizeBytes = 2048L,
            mediaType = MediaType.VIDEO
        )
        val info = MediaInfo(
            input = input,
            width = 1920,
            height = 1080,
            durationMs = 65_000L
        )
        assertEquals("1920x1080", info.displayResolution)
        assertEquals("01:05", info.formattedDuration)
        assertFalse(info.formattedSize.isBlank())

        val noDimInfo = info.copy(width = 0, height = 0)
        assertEquals("-", noDimInfo.displayResolution)
    }

    // endregion

    // region 2. Error Mapping (14 Scenarios)

    @Test
    fun `studio error mapper provides Turkish title, message and suggestion for all 14 codes`() {
        assertEquals(14, StudioErrorCode.entries.size)
        StudioErrorCode.entries.forEach { code ->
            val info = StudioErrorMapper.fromCode(code, detail = "detay_$code")
            assertEquals(code, info.code)
            assertTrue("Başlık boş: $code", info.titleTr.isNotBlank())
            assertTrue("Mesaj boş: $code", info.messageTr.isNotBlank())
            assertTrue("Öneri boş: $code", info.suggestionTr.isNotBlank())
            assertEquals("detay_$code", info.technicalDetail)
        }
        val withoutDetail = StudioErrorMapper.fromCode(StudioErrorCode.CANCELLED, detail = "   ")
        assertNull(withoutDetail.technicalDetail)
    }

    @Test
    fun `studio error mapper translates throwables accurately`() {
        val direct = StudioException(StudioErrorCode.EXTENSION_MISMATCH, "mp4 != png")
        assertEquals(StudioErrorCode.EXTENSION_MISMATCH, StudioErrorMapper.fromThrowable(direct).code)

        assertEquals(
            StudioErrorCode.CANCELLED,
            StudioErrorMapper.fromThrowable(CancellationException("cancelled")).code
        )
        assertEquals(
            StudioErrorCode.URI_PERMISSION_LOST,
            StudioErrorMapper.fromThrowable(SecurityException("Permission Denial")).code
        )
        assertEquals(
            StudioErrorCode.FILE_NOT_FOUND,
            StudioErrorMapper.fromThrowable(FileNotFoundException("missing")).code
        )
        assertEquals(
            StudioErrorCode.LOW_STORAGE,
            StudioErrorMapper.fromThrowable(IOException("write failed: ENOSPC (No space left)")).code
        )
        assertEquals(
            StudioErrorCode.THERMAL_OR_RESOURCE,
            StudioErrorMapper.fromThrowable(OutOfMemoryError("bitmap allocation")).code
        )
        assertEquals(
            StudioErrorCode.NO_ENCODER,
            StudioErrorMapper.fromThrowable(IllegalStateException("no suitable encoder found")).code
        )
        assertEquals(
            StudioErrorCode.CODEC_UNSUPPORTED,
            StudioErrorMapper.fromThrowable(IllegalArgumentException("format not supported")).code
        )
        assertEquals(
            StudioErrorCode.INVALID_RESOLUTION_FPS,
            StudioErrorMapper.fromThrowable(IllegalArgumentException("invalid resolution 7680x4320")).code
        )
        assertEquals(
            StudioErrorCode.DURATION_MISMATCH,
            StudioErrorMapper.fromThrowable(IllegalArgumentException("invalid trim range")).code
        )
        assertEquals(
            StudioErrorCode.OUTPUT_CREATE_FAILED,
            StudioErrorMapper.fromThrowable(IOException("mediastore insert failed")).code
        )
        assertEquals(
            StudioErrorCode.IO_READ_ERROR,
            StudioErrorMapper.fromThrowable(IOException("stream closed")).code
        )
    }

    // endregion

    // region 3. File, Extension & Mismatch Detection

    @Test
    fun `extension extraction handles paths, case and missing extensions`() {
        assertEquals("mp4", MediaFormatUtils.extensionFromFileName("video.MP4"))
        assertEquals("mkv", MediaFormatUtils.extensionFromFileName("/storage/emulated/0/Movies/test.Mkv"))
        assertEquals("wav", MediaFormatUtils.extensionFromFileName("C:\\ses\\kayit.wav"))
        assertEquals("", MediaFormatUtils.extensionFromFileName("uzantisiz_dosya"))
        assertEquals("", MediaFormatUtils.extensionFromFileName(".gizlidosya"))
        assertEquals("", MediaFormatUtils.extensionFromFileName("nokta."))
    }

    @Test
    fun `media type inference prioritizes mime type and falls back to extension`() {
        assertEquals(MediaType.VIDEO, MediaFormatUtils.inferMediaType("mp4", null))
        assertEquals(MediaType.AUDIO, MediaFormatUtils.inferMediaType("m4a", null))
        assertEquals(MediaType.IMAGE, MediaFormatUtils.inferMediaType("webp", null))
        assertEquals(MediaType.UNKNOWN, MediaFormatUtils.inferMediaType("xyz", null))

        // MIME türü uyuşmazlıkta belirleyicidir
        assertEquals(MediaType.IMAGE, MediaFormatUtils.inferMediaType("mp4", "image/png"))
        assertEquals("audio/wav", MediaFormatUtils.mimeTypeForExtension("WAV"))
        assertEquals("video/mp4", MediaFormatUtils.mimeTypeForExtension(".mp4"))
        assertEquals("application/octet-stream", MediaFormatUtils.mimeTypeForExtension("unknown"))
    }

    @Test
    fun `extension mismatch detection identifies fake extensions while allowing m4a container`() {
        assertFalse(MediaFormatUtils.isExtensionMismatch("mp4", "video/mp4"))
        assertFalse(MediaFormatUtils.isExtensionMismatch("jpg", "image/jpeg"))
        assertFalse(MediaFormatUtils.isExtensionMismatch("m4a", "video/mp4"))
        assertFalse(MediaFormatUtils.isExtensionMismatch("mp4", "application/octet-stream"))

        assertTrue(MediaFormatUtils.isExtensionMismatch("mp4", "image/png"))
        assertTrue(MediaFormatUtils.isExtensionMismatch("jpg", "image/png"))
        assertTrue(MediaFormatUtils.isExtensionMismatch("wav", "audio/mpeg"))
        assertTrue(MediaFormatUtils.isExtensionMismatch("png", "video/mp4"))
    }

    @Test
    fun `file name sanitization and unique naming prevent collisions`() {
        assertEquals("video_dosyam", MediaFormatUtils.sanitizeBaseName("video/dosyam?.mp4"))
        assertEquals("avenlyx_cikti", MediaFormatUtils.sanitizeBaseName("   ///   "))

        val existing = setOf("klip.mp4", "KLIP_1.mp4")
        assertEquals(
            "klip_2.mp4",
            MediaFormatUtils.buildUniqueDisplayName("klip", "MP4", existing)
        )
        assertEquals(
            "yeni.mp4",
            MediaFormatUtils.buildUniqueDisplayName("yeni.mov", "mp4", existing)
        )
    }

    @Test
    fun `duration and byte formatters produce readable outputs`() {
        assertEquals("00:00", MediaFormatUtils.formatDurationMs(0L))
        assertEquals("00:09", MediaFormatUtils.formatDurationMs(9_000L))
        assertEquals("02:05", MediaFormatUtils.formatDurationMs(125_000L))
        assertEquals("01:01:05", MediaFormatUtils.formatDurationMs(3_665_000L))
        assertEquals("0 B", MediaFormatUtils.formatBytes(0L))
        assertEquals("512 B", MediaFormatUtils.formatBytes(512L))
    }

    // endregion

    // region 4. Job Estimation

    @Test
    fun `estimateOutputBytes reflects format, quality profile, wav pcm math and speed`() {
        val wavSettings = MediaJobSettings(
            outputFormat = "wav",
            audioSampleRateHz = 48_000,
            audioChannels = 2
        )
        // 10 sn * 48000 * 2 kanal * 2 byte + 44 header = 1_920_044 byte
        val wavEst = MediaFormatUtils.estimateOutputBytes(
            tool = StudioTool.AUDIO_CONVERT,
            durationMs = 10_000L,
            inputSizeBytes = 500_000L,
            settings = wavSettings
        )
        assertEquals(1_920_044L, wavEst)

        val smallVideo = MediaFormatUtils.estimateOutputBytes(
            tool = StudioTool.VIDEO_CONVERT,
            durationMs = 20_000L,
            inputSizeBytes = 10_000_000L,
            settings = MediaJobSettings(qualityProfile = QualityProfile.SMALL_FILE)
        )
        val highVideo = MediaFormatUtils.estimateOutputBytes(
            tool = StudioTool.VIDEO_CONVERT,
            durationMs = 20_000L,
            inputSizeBytes = 10_000_000L,
            settings = MediaJobSettings(qualityProfile = QualityProfile.HIGH_QUALITY)
        )
        assertTrue(smallVideo < highVideo)

        val fastVideo = MediaFormatUtils.estimateOutputBytes(
            tool = StudioTool.VIDEO_EDIT,
            durationMs = 20_000L,
            inputSizeBytes = 10_000_000L,
            settings = MediaJobSettings(playbackSpeed = 2.0f, removeAudio = true)
        )
        val normalNoAudio = MediaFormatUtils.estimateOutputBytes(
            tool = StudioTool.VIDEO_EDIT,
            durationMs = 20_000L,
            inputSizeBytes = 10_000_000L,
            settings = MediaJobSettings(playbackSpeed = 1.0f, removeAudio = true)
        )
        assertTrue(fastVideo < normalNoAudio)

        val pngEst = MediaFormatUtils.estimateOutputBytes(
            tool = StudioTool.IMAGE_CONVERT,
            durationMs = 0L,
            inputSizeBytes = 1_000_000L,
            settings = MediaJobSettings(outputFormat = "png", imageScalePercent = 100)
        )
        val webpSmallEst = MediaFormatUtils.estimateOutputBytes(
            tool = StudioTool.IMAGE_COMPRESS,
            durationMs = 0L,
            inputSizeBytes = 1_000_000L,
            settings = MediaJobSettings(outputFormat = "webp", imageScalePercent = 50, imageQuality = 60)
        )
        assertTrue(webpSmallEst < pngEst)
    }

    @Test
    fun `repository estimateJob delegates to engine and falls back gracefully on engine failure`() = runBlocking {
        val repo = createTestRepository(throwOnEstimate = true)
        val job = MediaJob(
            tool = StudioTool.VIDEO_CONVERT,
            title = "Test Tahmin",
            inputs = listOf(
                MediaInput(
                    uriString = "file:///video.mp4",
                    displayName = "video.mp4",
                    extension = "mp4",
                    sizeBytes = 4_000_000L,
                    mediaType = MediaType.VIDEO
                )
            ),
            settings = MediaJobSettings(outputFormat = "mp4", qualityProfile = QualityProfile.BALANCED)
        )
        val fallbackEstimate = repo.estimateJob(job)
        assertTrue(fallbackEstimate.estimatedOutputBytes > 0L)
        assertTrue(fallbackEstimate.requiredFreeSpaceBytes >= fallbackEstimate.estimatedOutputBytes)
        assertTrue(fallbackEstimate.disclaimerTr.contains("tahmin", ignoreCase = true))
    }

    // endregion

    // region 5. Timeline Undo/Redo & Audio Resampler

    @Test
    fun `repository timeline undo and redo maintain history stack and flags`() = runBlocking {
        val repo = createTestRepository()
        val inputA = MediaInput(uriString = "file:///a.mp4", displayName = "a.mp4", extension = "mp4", mediaType = MediaType.VIDEO)
        val inputB = MediaInput(uriString = "file:///b.mp4", displayName = "b.mp4", extension = "mp4", mediaType = MediaType.VIDEO)

        assertFalse(repo.canUndoFlow.value)
        assertFalse(repo.canRedoFlow.value)
        assertEquals(0, repo.activeTimelineFlow.value.clips.size)

        val clipA = TimelineClipItem(clipId = "c1", input = inputA, durationMs = 4_000L, trimEndMs = 4_000L)
        val clipB = TimelineClipItem(clipId = "c2", input = inputB, durationMs = 6_000L, trimEndMs = 6_000L)

        repo.updateTimeline { it.copy(clips = listOf(clipA)) }
        assertEquals(1, repo.activeTimelineFlow.value.clips.size)
        assertTrue(repo.canUndoFlow.value)
        assertFalse(repo.canRedoFlow.value)

        repo.updateTimeline { it.copy(clips = listOf(clipA, clipB)) }
        assertEquals(2, repo.activeTimelineFlow.value.clips.size)
        assertEquals(10_000L, repo.activeTimelineFlow.value.totalDurationMs)

        // Undo -> back to 1 clip
        assertTrue(repo.undoTimeline())
        assertEquals(1, repo.activeTimelineFlow.value.clips.size)
        assertTrue(repo.canUndoFlow.value)
        assertTrue(repo.canRedoFlow.value)

        // Undo -> back to 0 clips
        assertTrue(repo.undoTimeline())
        assertEquals(0, repo.activeTimelineFlow.value.clips.size)
        assertFalse(repo.canUndoFlow.value)
        assertTrue(repo.canRedoFlow.value)
        assertFalse(repo.undoTimeline())

        // Redo -> 1 clip
        assertTrue(repo.redoTimeline())
        assertEquals(1, repo.activeTimelineFlow.value.clips.size)

        // New edit clears redo stack
        val clipC = TimelineClipItem(clipId = "c3", input = inputB, durationMs = 2_000L, trimEndMs = 2_000L)
        repo.updateTimeline { it.copy(clips = listOf(clipA, clipC)) }
        assertFalse(repo.canRedoFlow.value)
        assertEquals(6_000L, repo.activeTimelineFlow.value.totalDurationMs)
    }

    @Test
    fun `repository undo stack is bounded at 30 entries`() {
        val repo = createTestRepository()
        val input = MediaInput(uriString = "file:///a.mp4", displayName = "a.mp4", extension = "mp4", mediaType = MediaType.VIDEO)

        repeat(35) { step ->
            repo.updateTimeline { project ->
                project.copy(
                    name = "Proje_$step",
                    clips = listOf(TimelineClipItem(clipId = "c_$step", input = input, durationMs = 1_000L, trimEndMs = 1_000L))
                )
            }
        }

        var undoCount = 0
        while (repo.undoTimeline()) {
            undoCount++
        }
        assertEquals(30, undoCount)
        assertFalse(repo.canUndoFlow.value)
    }

    @Test
    fun `user preferences repository updates and reads AppSettings via DataStore`() = runBlocking {
        val fakeStore = InMemoryPreferencesDataStore()
        val prefsRepo = UserPreferencesRepository(fakeStore)

        val initial = prefsRepo.settingsFlow.first()
        assertEquals(ThemePreference.SYSTEM, initial.themePreference)
        assertTrue(initial.simpleMode)
        assertEquals(QualityProfile.BALANCED, initial.defaultQuality)

        prefsRepo.setThemePreference(ThemePreference.DARK)
        prefsRepo.setSimpleMode(false)
        prefsRepo.setDefaultQuality(QualityProfile.HIGH_QUALITY)
        prefsRepo.setDefaultVideoFormat("webm")
        prefsRepo.setDefaultAudioFormat("wav")
        prefsRepo.setDefaultImageFormat("webp")
        prefsRepo.setNotificationsEnabled(false)
        prefsRepo.setAutoCleanCache(true)

        val updated = prefsRepo.settingsFlow.first()
        assertEquals(ThemePreference.DARK, updated.themePreference)
        assertFalse(updated.simpleMode)
        assertEquals(QualityProfile.HIGH_QUALITY, updated.defaultQuality)
        assertEquals("webm", updated.defaultVideoFormat)
        assertEquals("wav", updated.defaultAudioFormat)
        assertEquals("webp", updated.defaultImageFormat)
        assertFalse(updated.notificationsEnabled)
        assertTrue(updated.autoCleanCache)
    }

    @Test
    fun `streaming linear resampler converts mono to stereo and preserves sample amplitude`() {
        val resampler = AudioPcmUtils.StreamingLinearResampler(
            targetSampleRate = 48_000,
            targetChannels = 2
        )
        val out = ByteArrayOutputStream()
        val monoSamples = shortArrayOf(1000, -2000, 3000)

        resampler.processAndWrite(
            inputShorts = monoSamples,
            inSampleRate = 48_000,
            inChannels = 1,
            outStream = out
        )

        val bytes = out.toByteArray()
        assertEquals(monoSamples.size * 2 * 2, bytes.size)
        val shorts = ShortArray(bytes.size / 2)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts)
        assertEquals(1000.toShort(), shorts[0])
        assertEquals(1000.toShort(), shorts[1])
        assertEquals((-2000).toShort(), shorts[2])
        assertEquals((-2000).toShort(), shorts[3])
        assertEquals(3000.toShort(), shorts[4])
        assertEquals(3000.toShort(), shorts[5])
    }

    // endregion

    // region Helpers

    private fun createTestRepository(throwOnEstimate: Boolean = false): StudioRepository {
        val rootDir = tempFolder.newFolder("studio_test_${System.nanoTime()}")
        val testContext = TestStudioContext(rootDir)
        val fileHelper = FileStorageHelper(testContext)
        val db = StudioDatabase(testContext)
        val prefs = UserPreferencesRepository(InMemoryPreferencesDataStore())
        val fakeEngine = FakeMediaEngine(throwOnEstimate)
        return StudioRepository(
            context = testContext,
            engine = fakeEngine,
            db = db,
            fileHelper = fileHelper,
            prefs = prefs
        )
    }

    private class TestStudioContext(private val rootDir: File) : ContextWrapper(null) {
        private val cache = File(rootDir, "cache").apply { mkdirs() }
        private val files = File(rootDir, "files").apply { mkdirs() }
        private val extFiles = File(rootDir, "ext_files").apply { mkdirs() }

        override fun getApplicationContext(): Context = this
        override fun getCacheDir(): File = cache
        override fun getFilesDir(): File = files
        override fun getExternalFilesDir(type: String?): File =
            if (type == null) extFiles else File(extFiles, type).apply { mkdirs() }
        override fun getExternalCacheDir(): File = cache
        override fun getPackageName(): String = "com.formatci.mediastudio"
    }

    private class InMemoryPreferencesDataStore : DataStore<Preferences> {
        private val state = MutableStateFlow(emptyPreferences())
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            val next = transform(state.value)
            state.value = next
            return next
        }
    }

    private class FakeMediaEngine(private val throwOnEstimate: Boolean) : MediaEngine {
        override suspend fun inspect(input: MediaInput): MediaInfo = MediaInfo(input = input)

        override suspend fun estimate(job: MediaJob): JobEstimate {
            if (throwOnEstimate) throw IllegalStateException("Simüle edilmiş tahmin hatası")
            return JobEstimate(
                estimatedOutputBytes = 100_000L,
                estimatedDurationMs = 5_000L,
                estimatedProcessingTimeMs = 2_000L,
                requiredFreeSpaceBytes = 200_000L,
                hasEnoughStorage = true
            )
        }

        override fun execute(job: MediaJob): Flow<MediaJobEvent> = flow {
            emit(MediaJobEvent.Started(job.id))
            emit(MediaJobEvent.Cancelled(job.id))
        }

        override suspend fun cancel(jobId: String) = Unit

        override fun capabilities(): EngineCapabilities = EngineCapabilities()

        override suspend fun extractFrameToFile(uriString: String, positionMs: Long): Result<String> =
            Result.failure(UnsupportedOperationException("Test ortamında desteklenmiyor"))
    }

    // endregion
}
