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
import com.andreacanes.panemgmt.ViewedPaneBus
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicInteger

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
        // paneId → attention notification id, so PaneStateChanged can cancel.
        val openAttention = mutableMapOf<String, Int>()
        // Maps paneId → list of approval UUIDs for that pane, so viewing a
        // pane can cancel all its approval notifications at once.
        val approvalsByPane = mutableMapOf<String, MutableList<String>>()

        // Cancel notifications when the user views a pane in the app.
        val viewedPaneJob = scope.launch {
            ViewedPaneBus.events.collect { viewedPaneId ->
                val nm = NotificationManagerCompat.from(applicationContext)
                // Cancel attention notification for this pane
                openAttention.remove(viewedPaneId)?.let { nm.cancel(it) }
                // Cancel all approval notifications for this pane
                approvalsByPane.remove(viewedPaneId)?.forEach { approvalId ->
                    openApprovals.remove(approvalId)?.let { nm.cancel(it) }
                }
            }
        }

        var backoff = 1_000L
        while (true) {
            val client = CompanionClient(config.baseUrl, config.bearerToken)
            try {
                client.events().collect { ev ->
                    when (ev) {
                        is EventDto.ApprovalCreated -> {
                            val notifId = nextNotifId.getAndIncrement()
                            openApprovals[ev.approval.id] = notifId
                            approvalsByPane.getOrPut(ev.approval.paneId) { mutableListOf() }
                                .add(ev.approval.id)
                            val project = ev.approval.projectDisplayName
                            val paneLabel = ev.approval.paneId.substringAfterLast(":")
                            val prefix = if (project != null) "[$project:$paneLabel] " else "[${ev.approval.paneId}] "
                            postApprovalNotification(
                                notifId = notifId,
                                paneId = ev.approval.paneId,
                                title = "${prefix}Claude: ${ev.approval.title}",
                                body = ev.approval.message.ifBlank { "Approval requested" },
                            )
                        }
                        is EventDto.AttentionNeeded -> {
                            // Reuse the same notifId for the same pane so a
                            // second attention event for the same pane updates
                            // the existing notification instead of stacking.
                            val notifId = openAttention.getOrPut(ev.paneId) {
                                nextNotifId.getAndIncrement()
                            }
                            val channel = if (ev.kind == "input")
                                NotificationChannels.ATTENTION_INPUT
                            else
                                NotificationChannels.ATTENTION_INFO
                            // Title already includes [project] from the Rust side;
                            // append the pane window.index so you can tell panes apart.
                            val paneLabel = ev.paneId.substringAfterLast(":")
                            val enrichedTitle = if (ev.title.startsWith("["))
                                ev.title.replaceFirst("] ", ":$paneLabel] ")
                            else
                                "[${ev.paneId}] ${ev.title}"
                            postAttentionNotification(
                                notifId = notifId,
                                paneId = ev.paneId,
                                title = enrichedTitle,
                                body = ev.message,
                                channel = channel,
                            )
                        }
                        is EventDto.ApprovalResolved -> {
                            val notifId = openApprovals.remove(ev.id)
                            if (notifId != null) {
                                NotificationManagerCompat.from(applicationContext).cancel(notifId)
                            }
                            // Clean up the pane→approval reverse index
                            for (list in approvalsByPane.values) { list.remove(ev.id) }
                        }
                        is EventDto.PaneStateChanged -> {
                            // When a pane leaves Waiting, drop any lingering
                            // attention notification for it — we've resolved.
                            if (ev.old == PaneState.Waiting && ev.new != PaneState.Waiting) {
                                val attnId = openAttention.remove(ev.paneId)
                                if (attnId != null) {
                                    NotificationManagerCompat.from(applicationContext).cancel(attnId)
                                }
                            }
                        }
                        is EventDto.Snapshot -> {
                            // On reconnect the snapshot replays every known approval.
                            // Don't re-notify for ones we already notified about.
                            ev.approvals.forEach { approval ->
                                if (!openApprovals.containsKey(approval.id)) {
                                    val notifId = nextNotifId.getAndIncrement()
                                    openApprovals[approval.id] = notifId
                                    approvalsByPane.getOrPut(approval.paneId) { mutableListOf() }
                                        .add(approval.id)
                                    val project = approval.projectDisplayName
                                    val paneLabel = approval.paneId.substringAfterLast(":")
                                    val prefix = if (project != null) "[$project:$paneLabel] " else "[${approval.paneId}] "
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
        /** Monotonic counter for notification IDs. Avoids hash collisions
         *  from UUID.hashCode() and resets safely on service restart. */
        private val nextNotifId = AtomicInteger(10_000)

        fun start(context: Context) {
            val intent = Intent(context, ApprovalService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ApprovalService::class.java))
        }
    }
}
