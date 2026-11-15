package com.formatci.mediastudio.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.formatci.mediastudio.R
import com.formatci.mediastudio.domain.model.MediaJob
import kotlin.math.abs

/**
 * Android 14/15 `foregroundServiceType="mediaProcessing"` kurallarıyla uyumlu ön plan servisi.
 * Kalıcı bildirim kanalını oluşturur, ilerleme çubuğunu ve "İptal Et" aksiyonunu yönetir,
 * sistem 6 saatlik zaman aşımını (`onTimeout`) güvenli biçimde karşılar.
 */
class MediaProcessingService : Service() {

    private var activeJobId: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel(this)

        when (intent?.action) {
            ACTION_CANCEL_ACTIVE -> {
                val targetId = intent.getStringExtra(EXTRA_JOB_ID) ?: activeJobId
                onCancelRequested?.invoke(targetId)
                val cancellingNotif = buildNotification(
                    jobId = targetId,
                    title = "İşlem iptal ediliyor...",
                    statusText = "Geçici dosyalar temizleniyor",
                    progressPercent = 0,
                    indeterminate = true,
                    activeCount = 1
                )
                startForegroundCompat(cancellingNotif)
                return START_NOT_STICKY
            }

            ACTION_STOP -> {
                val closingNotif = buildNotification(
                    jobId = null,
                    title = getString(R.string.app_name),
                    statusText = "İşlemler tamamlandı",
                    progressPercent = 100,
                    indeterminate = false,
                    activeCount = 0
                )
                startForegroundCompat(closingNotif)
                stopServiceGracefully()
                return START_NOT_STICKY
            }

            else -> {
                val jobId = intent?.getStringExtra(EXTRA_JOB_ID)
                val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank {
                    getString(R.string.app_name)
                }
                val status = intent?.getStringExtra(EXTRA_STATUS).orEmpty().ifBlank {
                    "Medya işleniyor..."
                }
                val progress = intent?.getIntExtra(EXTRA_PROGRESS, 0)?.coerceIn(0, 100) ?: 0
                val indeterminate = intent?.getBooleanExtra(EXTRA_INDETERMINATE, true) ?: true
                val activeCount = intent?.getIntExtra(EXTRA_ACTIVE_COUNT, 1) ?: 1

                activeJobId = jobId
                isRunning = true

                val notification = buildNotification(
                    jobId = jobId,
                    title = title,
                    statusText = status,
                    progressPercent = progress,
                    indeterminate = indeterminate,
                    activeCount = activeCount
                )
                startForegroundCompat(notification)

                if (activeCount <= 0) {
                    stopServiceGracefully()
                    return START_NOT_STICKY
                }
                return START_NOT_STICKY
            }
        }
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onTimeout(startId: Int) {
        handleSystemTimeout(startId)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        handleSystemTimeout(startId)
    }

    override fun onDestroy() {
        isRunning = false
        activeJobId = null
        super.onDestroy()
    }

    private fun handleSystemTimeout(startId: Int) {
        val timedOutId = activeJobId
        onTimeoutCallback?.invoke(timedOutId)
        onCancelRequested?.invoke(timedOutId)
        stopForegroundCompat()
        isRunning = false
        stopSelf(startId)
    }

    private fun stopServiceGracefully() {
        stopForegroundCompat()
        isRunning = false
        activeJobId = null
        stopSelf()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun buildNotification(
        jobId: String?,
        title: String,
        statusText: String,
        progressPercent: Int,
        indeterminate: Boolean,
        activeCount: Int
    ): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val contentPendingIntent = launchIntent?.let {
            PendingIntent.getActivity(
                this,
                REQ_CONTENT,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val cancelIntent = Intent(this, MediaProcessingService::class.java).apply {
            action = ACTION_CANCEL_ACTIVE
            putExtra(EXTRA_JOB_ID, jobId)
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            REQ_CANCEL,
            cancelIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentTitle = if (activeCount > 1) {
            "$title (+${activeCount - 1} sırada)"
        } else {
            title
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(contentTitle)
            .setContentText(statusText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setProgress(100, progressPercent.coerceIn(0, 100), indeterminate)

        if (contentPendingIntent != null) {
            builder.setContentIntent(contentPendingIntent)
        }
        if (activeCount > 0) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "İptal Et",
                cancelPendingIntent
            )
        }
        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "formatci_media_processing"
        const val NOTIFICATION_ID = 4102

        const val ACTION_UPDATE = "com.formatci.mediastudio.action.UPDATE_PROCESSING"
        const val ACTION_CANCEL_ACTIVE = "com.formatci.mediastudio.action.CANCEL_ACTIVE"
        const val ACTION_STOP = "com.formatci.mediastudio.action.STOP_PROCESSING"

        const val EXTRA_JOB_ID = "extra_job_id"
        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_STATUS = "extra_status"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_INDETERMINATE = "extra_indeterminate"
        const val EXTRA_ACTIVE_COUNT = "extra_active_count"

        private const val REQ_CONTENT = 1001
        private const val REQ_CANCEL = 1002

        @Volatile
        var isRunning: Boolean = false
            private set

        @Volatile
        var onCancelRequested: ((jobId: String?) -> Unit)? = null

        @Volatile
        var onTimeoutCallback: ((jobId: String?) -> Unit)? = null

        @Volatile
        private var lastUpdateMs: Long = 0L

        @Volatile
        private var lastJobId: String? = null

        @Volatile
        private var lastPct: Int = -1

        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return

            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.notification_channel_desc)
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        fun syncProcessingState(
            context: Context,
            activeJob: MediaJob?,
            activeOrQueuedCount: Int
        ) {
            if (activeOrQueuedCount <= 0 || activeJob == null) {
                if (isRunning) {
                    lastJobId = null
                    lastPct = -1
                    runCatching {
                        context.stopService(Intent(context, MediaProcessingService::class.java))
                    }
                }
                return
            }

            val pct = (activeJob.progress * 100f).toInt().coerceIn(0, 100)
            val now = System.currentTimeMillis()
            val sameJob = activeJob.id == lastJobId
            if (isRunning && sameJob && abs(pct - lastPct) < 4 && now - lastUpdateMs < 450L) {
                return
            }
            lastJobId = activeJob.id
            lastPct = pct
            lastUpdateMs = now

            val intent = Intent(context, MediaProcessingService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_JOB_ID, activeJob.id)
                putExtra(EXTRA_TITLE, activeJob.title)
                putExtra(EXTRA_STATUS, activeJob.statusMessage)
                putExtra(EXTRA_PROGRESS, pct)
                putExtra(EXTRA_INDETERMINATE, activeJob.isIndeterminate)
                putExtra(EXTRA_ACTIVE_COUNT, activeOrQueuedCount)
            }

            runCatching {
                ContextCompat.startForegroundService(context, intent)
            }
        }
    }
}
