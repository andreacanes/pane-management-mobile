package com.andreacanes.panemgmt.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class SessionDto(
    val name: String,
    val windows: Int,
    val attached: Boolean,
)

@Serializable
enum class PaneState {
    @SerialName("idle")
    Idle,

    @SerialName("running")
    Running,

    @SerialName("waiting")
    Waiting,

    @SerialName("done")
    Done,
}

@Serializable
data class PaneDto(
    val id: String,
    @SerialName("session_name") val sessionName: String,
    @SerialName("window_index") val windowIndex: Int,
    @SerialName("window_name") val windowName: String,
    @SerialName("pane_index") val paneIndex: Int,
    @SerialName("current_command") val currentCommand: String,
    @SerialName("current_path") val currentPath: String,
    val state: PaneState,
    @SerialName("last_output_preview") val lastOutputPreview: List<String>,
    @SerialName("project_encoded_name") val projectEncodedName: String? = null,
    @SerialName("claude_session_id") val claudeSessionId: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
)

@Serializable
data class CaptureDto(
    val lines: List<String>,
    val seq: Long,
)

@Serializable
data class ApprovalDto(
    val id: String,
    @SerialName("pane_id") val paneId: String,
    val title: String,
    val message: String,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_input") val toolInput: JsonElement? = null,
    @SerialName("created_at") val createdAt: Long,
    @SerialName("expires_at") val expiresAt: Long,
)

@Serializable
data class SendInputRequest(
    val text: String,
    val submit: Boolean,
)

@Serializable
data class SendVoiceRequest(
    val transcript: String,
    val submit: Boolean = true,
    val locale: String? = null,
)

@Serializable
data class ResolveApprovalRequest(
    val decision: String,
    val reason: String? = null,
)

/**
 * Matches the Rust companion's HealthDto { version, bind, uptime_s }.
 */
@Serializable
data class HealthDto(
    val version: String,
    val bind: String,
    @SerialName("uptime_s") val uptimeSeconds: Long,
)

/**
 * Tagged envelope for WebSocket events. The Rust side uses
 * `#[serde(tag = "type", rename_all = "snake_case")]`.
 */
@Serializable
sealed class EventDto {
    @Serializable
    @SerialName("snapshot")
    data class Snapshot(
        val panes: List<PaneDto>,
        val approvals: List<ApprovalDto>,
    ) : EventDto()

    @Serializable
    @SerialName("hello")
    data class Hello(val at: Long) : EventDto()

    @Serializable
    @SerialName("pane_state_changed")
    data class PaneStateChanged(
        @SerialName("pane_id") val paneId: String,
        val old: PaneState,
        val new: PaneState,
        val at: Long,
    ) : EventDto()

    @Serializable
    @SerialName("pane_output_changed")
    data class PaneOutputChanged(
        @SerialName("pane_id") val paneId: String,
        val tail: List<String>,
        val seq: Long,
        val at: Long,
    ) : EventDto()

    @Serializable
    @SerialName("approval_created")
    data class ApprovalCreated(val approval: ApprovalDto) : EventDto()

    @Serializable
    @SerialName("approval_resolved")
    data class ApprovalResolved(
        val id: String,
        val decision: String,
        val at: Long,
    ) : EventDto()

    @Serializable
    @SerialName("session_started")
    data class SessionStarted(val name: String, val at: Long) : EventDto()

    @Serializable
    @SerialName("session_ended")
    data class SessionEnded(val name: String, val at: Long) : EventDto()
}
