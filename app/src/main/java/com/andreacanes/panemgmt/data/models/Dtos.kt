package com.andreacanes.panemgmt.data.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionDto(
    val name: String,
    val windows: Int,
    val attached: Boolean,
)

@Serializable
enum class PaneState {
    @SerialName("idle") Idle,
    @SerialName("running") Running,
    @SerialName("waiting") Waiting,
    @SerialName("done") Done,
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
)

@Serializable
data class ResolveApprovalRequest(
    val decision: String,
)

@Serializable
data class HealthDto(
    val status: String,
)
