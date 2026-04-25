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

/** Sub-category of [PaneState.Waiting]. Carried as `waiting_reason` on
 *  the wire and absent when the pane is not waiting. */
@Serializable
enum class WaitingReason {
    /** Claude asked something (permission, elicitation, AskUserQuestion,
     *  idle_prompt). User needs to make a decision. */
    @SerialName("request")
    Request,

    /** Claude finished a turn (Stop hook) and is waiting for the user
     *  to nudge with the next prompt. Not a Claude-initiated request. */
    @SerialName("continue")
    Continue,
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
    /** Sub-category when [state] is [PaneState.Waiting]. `Request` when
     *  Claude is asking something; `Continue` when Claude stopped and
     *  wants a user nudge. Null for all other states. */
    @SerialName("waiting_reason") val waitingReason: WaitingReason? = null,
    @SerialName("last_output_preview") val lastOutputPreview: List<String>,
    @SerialName("project_encoded_name") val projectEncodedName: String? = null,
    @SerialName("project_display_name") val projectDisplayName: String? = null,
    @SerialName("claude_session_id") val claudeSessionId: String? = null,
    /** "andrea" | "bravura" | "sully" | null — detected from child process
     *  env (`CLAUDE_CONFIG_DIR`) on local panes, synthesized from pane
     *  assignment on remote (Mac) panes. */
    @SerialName("claude_account") val claudeAccount: String? = null,
    /** Which host the pane lives on: "local" (WSL on the desktop) or an
     *  SSH alias such as "mac". Null on servers predating the Mac-host
     *  integration — treat as "local". */
    @SerialName("host") val host: String? = null,
    /** Current `/effort` level ("low" | "medium" | "high" | "max") detected
     *  by the companion poller from the pane's terminal output. Null when
     *  detection hasn't fired yet (fresh pane, or banner scrolled off before
     *  desktop startup) or for non-Claude panes. The EffortChip prefers this
     *  over its own client-side tail scan. */
    @SerialName("claude_effort") val claudeEffort: String? = null,
    @SerialName("updated_at") val updatedAt: Long,
    /** Epoch ms of the last conversation message (JSONL mtime). Null
     *  when the pane has no bound Claude session or the JSONL doesn't
     *  exist yet. Use this for "real activity" filters. */
    @SerialName("last_activity_at") val lastActivityAt: Long? = null,
    /** Operator-visible warning set by the companion when it detects an
     *  abnormal state — e.g., this pane's session_id collides with
     *  another pane. UI renders as a yellow chip with this text as the
     *  tooltip / subtitle. Null for healthy panes. */
    val warning: String? = null,
    /** Set when this pane is a local SSH mirror — its `start_command`
     *  is `ssh -t <alias> tmux attach-session -t <session>`, used by
     *  the desktop as a viewport into a remote tmux server. The phone
     *  has no local terminal to mirror into, so the APK filters these
     *  panes out of its main list. The backend stamps this via
     *  `services::ssh_mirror::parse_mirror_target`; the same field
     *  drives the desktop's 🔗-label rendering. Null for ordinary panes. */
    @SerialName("mirror_target") val mirrorTarget: MirrorTargetDto? = null,
)

/** Companion of [PaneDto.mirrorTarget]. The `<alias>/<session>` pair
 *  the local SSH-mirror pane points at on a remote tmux server. */
@Serializable
data class MirrorTargetDto(
    val alias: String,
    val session: String,
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

/**
 * Request body for `POST /api/v1/launch-host-session` — the APK's
 * entry point for "start Claude on a Mac (or any remote) without
 * needing the desktop attached". `host` must be a non-local SSH alias
 * ("mac" for now); the backend 400s on "local" because the existing
 * `/windows` endpoint already handles that case. `projectPath` is the
 * WSL-side path returned by `/projects` — the backend derives the
 * basename and translates to the remote-host convention.
 */
@Serializable
data class LaunchHostSessionRequest(
    val host: String,
    val account: String,
    @SerialName("project_path") val projectPath: String,
    @SerialName("project_display_name") val projectDisplayName: String,
)

@Serializable
data class LaunchHostSessionResponse(
    @SerialName("pane_id") val paneId: String,
    @SerialName("window_index") val windowIndex: Int,
    @SerialName("session_name") val sessionName: String,
)

/**
 * Request body for `POST /api/v1/sync-project-to-mac`. Kicks the
 * Mutagen helper so a not-yet-mirrored project becomes reachable for
 * a Mac launch. Idempotent — re-running for an already-synced project
 * is a no-op. Used by the "Sync now" retry button that the launch
 * sheet shows when `launchHostSession` returns 400 "not mirrored".
 */
@Serializable
data class SyncProjectRequest(
    @SerialName("encoded_project") val encodedProject: String,
)

@Serializable
data class SyncProjectResponse(
    val output: String,
)

/**
 * Request body for `POST /api/v1/attach-remote-session`. Asks the
 * desktop to create (or re-select) a local WSL tmux window that
 * SSH-attaches to the named remote session. Used by the phone's
 * "Attach here" affordance so a Mac mirror is ready in WezTerm by
 * the time the user gets back to their desktop. Idempotent — a
 * duplicate request just re-selects the existing window.
 */
@Serializable
data class AttachRemoteSessionRequest(
    val alias: String,
    @SerialName("session_name") val sessionName: String,
)

@Serializable
data class AttachRemoteSessionResponse(
    @SerialName("local_window_name") val localWindowName: String,
)

/**
 * Response for `GET /api/v1/remote-hosts`. Sorted union of hosts
 * referenced by any pane_assignment + the user's configured
 * `remote_hosts` store, with a `["mac"]` first-run fallback. Used by
 * the launch sheet's host segmented control so adding a third host
 * doesn't require an APK rebuild.
 */
@Serializable
data class RemoteHostsResponse(
    val hosts: List<String>,
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
 * Request body for `POST /api/v1/panes/{id}/fork`. Target pane id is
 * in the URL path; the source session_id is read server-side from the
 * companion's poller state, so the caller only needs to supply which
 * account's launcher (`ncld` vs `ncld2`) to use in the new pane.
 * Response reuses [CreatePaneResponse].
 */
@Serializable
data class ForkPaneRequest(
    val account: String,
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
    val thinking: String? = null,
    @SerialName("tool_result") val toolResult: String? = null,
    @SerialName("tool_result_truncated") val toolResultTruncated: Boolean? = null,
    @SerialName("tool_result_error") val toolResultError: Boolean? = null,
)

@Serializable
data class ConversationResponseDto(
    @SerialName("session_id") val sessionId: String,
    val messages: List<ConversationMessageDto>,
)

/** Snapshot of an active attention notification, replayed on WS reconnect. */
@Serializable
data class AttentionSnapshotDto(
    @SerialName("pane_id") val paneId: String,
    val title: String,
    val message: String,
    val kind: String = "input",
    val at: Long,
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
        val attention: List<AttentionSnapshotDto> = emptyList(),
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
