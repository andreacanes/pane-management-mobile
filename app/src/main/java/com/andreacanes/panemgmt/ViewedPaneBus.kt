package com.andreacanes.panemgmt

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Process-wide bus that the pane detail screen posts to when it opens.
 * The ApprovalService collects emissions and cancels any pending
 * approval / attention notifications for the viewed pane — the user is
 * already looking at it, so the notification is redundant.
 */
object ViewedPaneBus {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val events: SharedFlow<String> = _events

    fun post(paneId: String) {
        _events.tryEmit(paneId)
    }
}
