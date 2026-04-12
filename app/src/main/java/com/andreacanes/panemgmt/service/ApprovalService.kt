package com.andreacanes.panemgmt.service

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.andreacanes.panemgmt.MainActivity
import com.andreacanes.panemgmt.R
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.data.CompanionClient
import com.andreacanes.panemgmt.data.models.EventDto
import com.andreacanes.panemgmt.data.models.PaneState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.URLEncoder

/**
 * Foreground service that keeps a Ktor WebSocket subscription to the
 * companion's `/api/v1/events` stream alive while the user is logged in,
 * so approval prompts and idle notifications can fire even when the app
 * is backgrounded or the screen is off.
 */
class ApprovalService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationChannels.register(this)
        startInForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (worker == null || worker?.isActive != true) {
            worker = scope.launch { runWatcher() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        worker?.cancel()
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    // -----------------------------------------------------------------------
    // Worker loop
    // -----------------------------------------------------------------------

    private suspend fun runWatcher() {
        val authStore = AuthStore(applicationContext)
        val config = authStore.configFlow.first()
        if (config == null) {
            stopSelf()
            return
        }

        // approvalId (uuid string) → notification id, so we can cancel when
        // the approval resolves from the desktop side.
        val openApprovals = mutableMapOf<String, Int>()

        var backoff = 1_000L
        while (true) {
            val client = CompanionClient(config.baseUrl, config.bearerToken)
            try {
                client.events().collect { ev ->
                    when (ev) {
                        is EventDto.ApprovalCreated -> {
                            val notifId = ev.approval.id.hashCode()
                            openApprovals[ev.approval.id] = notifId
                            val project = ev.approval.projectDisplayName
                            val prefix = if (project != null) "[$project] " else ""
                            postApprovalNotification(
                                notifId = notifId,
                                paneId = ev.approval.paneId,
                                title = "${prefix}Claude: ${ev.approval.title}",
                                body = ev.approval.message.ifBlank { "Approval requested" },
                            )
                        }
                        is EventDto.AttentionNeeded -> {
                            val notifId = ("attn:${ev.paneId}").hashCode()
                            val channel = if (ev.kind == "input")
                                NotificationChannels.ATTENTION_INPUT
                            else
                                NotificationChannels.ATTENTION_INFO
                            postAttentionNotification(
                                notifId = notifId,
                                paneId = ev.paneId,
                                title = ev.title,
                                body = ev.message,
                                channel = channel,
                            )
                        }
                        is EventDto.ApprovalResolved -> {
                            val notifId = openApprovals.remove(ev.id) ?: ev.id.hashCode()
                            NotificationManagerCompat.from(applicationContext).cancel(notifId)
                        }
                        is EventDto.PaneStateChanged -> {
                            // When a pane leaves Waiting, drop any lingering
                            // attention notification for it — we've resolved.
                            if (ev.old == PaneState.Waiting && ev.new != PaneState.Waiting) {
                                val attnId = ("attn:${ev.paneId}").hashCode()
                                NotificationManagerCompat.from(applicationContext).cancel(attnId)
                            }
                        }
                        is EventDto.Snapshot -> {
                            // On reconnect the snapshot replays every known approval.
                            // Don't re-notify for ones we already notified about.
                            ev.approvals.forEach { approval ->
                                if (!openApprovals.containsKey(approval.id)) {
                                    val notifId = approval.id.hashCode()
                                    openApprovals[approval.id] = notifId
                                    val project = approval.projectDisplayName
                                    val prefix = if (project != null) "[$project] " else ""
                                    postApprovalNotification(
                                        notifId = notifId,
                                        paneId = approval.paneId,
                                        title = "${prefix}Claude: ${approval.title}",
                                        body = approval.message.ifBlank { "Approval requested" },
                                    )
                                }
                            }
                        }
                        else -> Unit
                    }
                }
                // Stream completed cleanly — reconnect after a beat.
            } catch (t: CancellationException) {
                throw t
            } catch (_: Throwable) {
                // Swallow — we'll back off and retry.
            } finally {
                runCatching { client.close() }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    // -----------------------------------------------------------------------
    // Notification helpers
    // -----------------------------------------------------------------------

    private fun startInForeground() {
        val notif = NotificationCompat.Builder(this, NotificationChannels.WATCHER)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Pane management watcher")
            .setContentText("Listening for approvals")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(buildOpenAppPendingIntent())
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIF_WATCHER_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIF_WATCHER_ID, notif)
        }
    }

    private fun postApprovalNotification(notifId: Int, paneId: String, title: String, body: String) {
        val notif = NotificationCompat.Builder(this, NotificationChannels.APPROVALS_HIGH)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(buildDeepLinkPendingIntent(paneId, notifId))
            .build()
        safeNotify(notifId, notif)
    }

    private fun postAttentionNotification(
        notifId: Int,
        paneId: String,
        title: String,
        body: String,
        channel: String = NotificationChannels.ATTENTION_INPUT,
    ) {
        val isInput = channel == NotificationChannels.ATTENTION_INPUT
        val notif = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(
                if (isInput) NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (isInput) NotificationCompat.CATEGORY_MESSAGE
                else NotificationCompat.CATEGORY_STATUS
            )
            .setAutoCancel(true)
            .setContentIntent(buildDeepLinkPendingIntent(paneId, notifId))
            .build()
        safeNotify(notifId, notif)
    }

    private fun safeNotify(notifId: Int, notif: Notification) {
        // POST_NOTIFICATIONS is runtime on API 33+; the user may have
        // denied it. NotificationManagerCompat.notify swallows silently
        // if we lack the permission, but we guard anyway so the worker
        // loop never throws SecurityException into our retry path.
        runCatching {
            NotificationManagerCompat.from(applicationContext).notify(notifId, notif)
        }
    }

    private fun buildOpenAppPendingIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQ_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun buildDeepLinkPendingIntent(paneId: String, requestCode: Int): PendingIntent {
        val encoded = URLEncoder.encode(paneId, "UTF-8")
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("panemgmt://pane/$encoded")).apply {
            setPackage(packageName)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        private const val NOTIF_WATCHER_ID = 1001
        private const val REQ_OPEN_APP = 2001

        fun start(context: Context) {
            val intent = Intent(context, ApprovalService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApprovalService::class.java))
        }
    }
}
