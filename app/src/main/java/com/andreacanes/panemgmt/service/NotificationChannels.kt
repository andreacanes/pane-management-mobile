package com.andreacanes.panemgmt.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build

/**
 * Notification channel ids used by [ApprovalService]. Channel names are
 * registered idempotently — calling register() multiple times is safe.
 */
object NotificationChannels {
    const val WATCHER = "pm_watcher"
    const val APPROVALS_HIGH = "pm_approvals_high"
    /** Claude needs user input (idle, question, elicitation). HIGH priority. */
    const val ATTENTION_INPUT = "pm_attention_input"
    /** Informational updates (Claude finished). DEFAULT priority. */
    const val ATTENTION_INFO = "pm_attention_info"

    fun register(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return

        if (nm.getNotificationChannel(WATCHER) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    WATCHER,
                    "Watcher",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Persistent indicator that the companion is connected."
                    setShowBadge(false)
                }
            )
        }

        if (nm.getNotificationChannel(APPROVALS_HIGH) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    APPROVALS_HIGH,
                    "Approvals",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Claude is asking for permission or plan confirmation."
                    enableVibration(true)
                    lockscreenVisibility = NotificationManager.IMPORTANCE_HIGH
                }
            )
        }

        if (nm.getNotificationChannel(ATTENTION_INPUT) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ATTENTION_INPUT,
                    "Input needed",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply {
                    description = "Claude is waiting for your input (idle, question, or prompt)."
                    enableVibration(true)
                }
            )
        }

        if (nm.getNotificationChannel(ATTENTION_INFO) == null) {
            nm.createNotificationChannel(
                NotificationChannel(
                    ATTENTION_INFO,
                    "Session updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Informational updates — Claude finished a task."
                }
            )
        }

        // Clean up the old unified attention channel if it exists.
        nm.deleteNotificationChannel("pm_attention")
    }
}
