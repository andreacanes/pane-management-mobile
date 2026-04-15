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
    @SerialName("project_display_name") val projectDisplayName: String? = null,
    @SerialName("claude_session_id") val claudeSessionId: String? = null,
    /** "andrea" | "bravura" | null — detected from child process env. */
    @SerialName("claude_account") val claudeAccount: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
    /** Epoch ms of the last conversation message (JSONL mtime). Null
     *  when the pane has no bound Claude session or the JSONL doesn't
     *  exist yet. Use this for "real activity" filters. */
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
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
    @SerialName("project_display_name") val projectDisplayName: String? = null,
    @SerialName("claude_account") val claudeAccount: String? = null,
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
data class SendKeyRequest(
    val key: String,
)

@Serializable
data class ImageItemDto(
    @SerialName("image_base64") val imageBase64: String,
    @SerialName("media_type") val mediaType: String = "image/png",
)

@Serializable
data class SendImageRequest(
    val images: List<ImageItemDto>,
    val prompt: String? = null,
)

@Serializable
enum class Decision {
    @SerialName("allow") Allow,
    @SerialName("deny") Deny,
}

@Serializable
data class ResolveApprovalRequest(
    val decision: Decision,
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

@Serializable
data class ProjectDto(
    @SerialName("encoded_name") val encodedName: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("actual_path")  val actualPath: String,
    @SerialName("git_branch")   val gitBranch: String? = null,
    @SerialName("session_count") val sessionCount: Int,
    @SerialName("active_pane_count") val activePaneCount: Int,
    val tier: String? = null,
)

@Serializable
data class CreateWindowRequest(
    @SerialName("session_name") val sessionName: String,
    @SerialName("project_path") val projectPath: String,
    @SerialName("project_display_name") val projectDisplayName: String,
    val account: String,
)

@Serializable
data class CreateWindowResponse(
    @SerialName("window_index") val windowIndex: Int,
    @SerialName("pane_id") val paneId: String,
)

@Serializable
data class CreatePaneRequest(
    @SerialName("target_pane_id") val targetPaneId: String,
    val account: String,
    /** "horizontal" (side-by-side) or "vertical" (stacked). Null → server default = horizontal. */
    val direction: String? = null,
)

@Serializable
data class CreatePaneResponse(
    @SerialName("pane_id") val paneId: String,
)

/**
 * Aggregate usage summary returned by `GET /api/v1/usage`. Mirrors the
 * ad-hoc JSON object built in `companion/http.rs::usage_summary`.
 */
@Serializable
data class UsageDto(
    val projects: Int,
    val sessions: Int,
    @SerialName("input_tokens")       val inputTokens: Long,
    @SerialName("output_tokens")      val outputTokens: Long,
    @SerialName("cache_write_tokens") val cacheWriteTokens: Long,
    @SerialName("cache_read_tokens")  val cacheReadTokens: Long,
    @SerialName("total_cost_usd")     val totalCostUsd: Double,
) {
    val totalTokens: Long
        get() = inputTokens + outputTokens + cacheWriteTokens + cacheReadTokens
}

/**
 * Per-account Anthropic rate limit utilization. Returned by
 * `GET /api/v1/rate-limits`. Timestamps are Unix seconds.
 */
@Serializable
data class AccountRateLimitDto(
    val account: String,
    val label: String,
    @SerialName("five_hour_pct") val fiveHourPct: Double,
    @SerialName("five_hour_resets_at") val fiveHourResetsAt: Long? = null,
    @SerialName("seven_day_pct") val sevenDayPct: Double,
    @SerialName("seven_day_resets_at") val sevenDayResetsAt: Long? = null,
)

// ---------------------------------------------------------------------------
// Conversation (JSONL session transcript)
// ---------------------------------------------------------------------------

@Serializable
data class ConversationMessageDto(
    val uuid: String,
    val role: String,
    val text: String,
    val timestamp: String,
    @SerialName("tool_name") val toolName: String? = null,
    @SerialName("tool_input") val toolInput: JsonElement? = null,
)

@Serializable
data class ConversationResponseDto(
    @SerialName("session_id") val sessionId: String,
    val messages: List<ConversationMessageDto>,
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
    @SerialName("pane_updated")
    data class PaneUpdated(
        val pane: PaneDto,
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
        val decision: Decision,
        val at: Long,
    ) : EventDto()

    @Serializable
    @SerialName("attention_needed")
    data class AttentionNeeded(
        @SerialName("pane_id") val paneId: String,
        val title: String,
        val message: String,
        val at: Long,
        val kind: String = "input",
        @SerialName("project_display_name") val projectDisplayName: String? = null,
        @SerialName("claude_account") val claudeAccount: String? = null,
    ) : EventDto()

    @Serializable
    @SerialName("pane_removed")
    data class PaneRemoved(
        @SerialName("pane_id") val paneId: String,
        val at: Long,
    ) : EventDto()

    @Serializable
    @SerialName("session_started")
    data class SessionStarted(val name: String, val at: Long) : EventDto()

    @Serializable
    @SerialName("session_ended")
    data class SessionEnded(val name: String, val at: Long) : EventDto()
}
