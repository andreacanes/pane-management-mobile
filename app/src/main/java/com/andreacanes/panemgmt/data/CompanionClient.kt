package com.andreacanes.panemgmt.data

import com.andreacanes.panemgmt.data.models.ApprovalDto
import com.andreacanes.panemgmt.data.models.CaptureDto
import com.andreacanes.panemgmt.data.models.CreateWindowRequest
import com.andreacanes.panemgmt.data.models.CreateWindowResponse
import com.andreacanes.panemgmt.data.models.EventDto
import com.andreacanes.panemgmt.data.models.HealthDto
import com.andreacanes.panemgmt.data.models.PaneDto
import com.andreacanes.panemgmt.data.models.ProjectDto
import com.andreacanes.panemgmt.data.models.ResolveApprovalRequest
import com.andreacanes.panemgmt.data.models.SendInputRequest
import com.andreacanes.panemgmt.data.models.SendKeyRequest
import com.andreacanes.panemgmt.data.models.SendVoiceRequest
import com.andreacanes.panemgmt.data.models.SessionDto
import com.andreacanes.panemgmt.data.models.UsageDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.Url
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.Json

/**
 * Ktor client for the pane-management companion service.
 * Base URL is the companion's HTTP origin, e.g. `http://100.110.47.29:8833`.
 */
class CompanionClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        classDiscriminator = "type"
    }

    private val parsedBase: Url = URLBuilder().takeFrom(baseUrl.trim()).build()

    private val client = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets)
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 5_000
            socketTimeoutMillis = 30_000
        }
        install(DefaultRequest) {
            header(HttpHeaders.Authorization, "Bearer $token")
            url.protocol = parsedBase.protocol
            url.host = parsedBase.host
            url.port = parsedBase.port
        }
    }

    // ---- HTTP -------------------------------------------------------------

    suspend fun health(): HealthDto =
        client.get("/api/v1/health").body()

    suspend fun listSessions(): List<SessionDto> =
        client.get("/api/v1/sessions").body()

    suspend fun listPanes(session: String? = null): List<PaneDto> =
        client.get("/api/v1/panes") {
            if (session != null) parameter("session", session)
        }.body()

    suspend fun capture(paneId: String, lines: Int = 1000): CaptureDto =
        client.get("/api/v1/panes/$paneId/capture") {
            parameter("lines", lines)
        }.body()

    suspend fun sendInput(paneId: String, text: String, submit: Boolean = true) {
        client.post("/api/v1/panes/$paneId/input") {
            contentType(ContentType.Application.Json)
            setBody(SendInputRequest(text = text, submit = submit))
        }
    }

    suspend fun sendVoice(paneId: String, transcript: String, locale: String? = null) {
        client.post("/api/v1/panes/$paneId/voice") {
            contentType(ContentType.Application.Json)
            setBody(SendVoiceRequest(transcript = transcript, submit = true, locale = locale))
        }
    }

    /**
     * Send a single named key to a pane via tmux send-keys (no `-l`).
     * Companion whitelists names like `S-Tab`, `Enter`, `Escape`,
     * `Up`/`Down`/`Left`/`Right`. Used by the mode-cycle button to
     * shift Claude between Normal / Auto-Accept / Plan / Bypass.
     */
    suspend fun sendKey(paneId: String, key: String) {
        client.post("/api/v1/panes/$paneId/key") {
            contentType(ContentType.Application.Json)
            setBody(SendKeyRequest(key = key))
        }
    }

    suspend fun cancelPane(paneId: String) {
        client.post("/api/v1/panes/$paneId/cancel")
    }

    suspend fun listApprovals(): List<ApprovalDto> =
        client.get("/api/v1/approvals").body()

    /** Aggregate usage summary across all projects and sessions. */
    suspend fun usage(): UsageDto =
        client.get("/api/v1/usage").body()

    suspend fun resolveApproval(id: String, decision: String, reason: String? = null) {
        client.post("/api/v1/approvals/$id") {
            contentType(ContentType.Application.Json)
            setBody(ResolveApprovalRequest(decision = decision, reason = reason))
        }
    }

    /**
     * Fetch every project the desktop knows about, sorted server-side
     * by active-pane count then alphabetical. Used by the mobile
     * CreateWindowSheet project picker.
     */
    suspend fun listProjects(): List<ProjectDto> =
        client.get("/api/v1/projects").body()

    /**
     * Create a new tmux window rooted at `project_path` in `session_name`
     * and launch `ncld`/`ncld2` inside it (Andrea / Bravura). Returns
     * the new window index and the pane id the caller can deep-link to.
     */
    suspend fun createWindow(req: CreateWindowRequest): CreateWindowResponse =
        client.post("/api/v1/windows") {
            contentType(ContentType.Application.Json)
            setBody(req)
        }.body()

    /**
     * Kill a tmux window by session + index. Terminates every pane in
     * the window. No body; returns 204 on success.
     */
    suspend fun killWindow(sessionName: String, windowIndex: Int) {
        client.delete("/api/v1/windows/$sessionName/$windowIndex")
    }

    // ---- WebSocket --------------------------------------------------------

    /**
     * Open a WebSocket to `/api/v1/events` and emit decoded [EventDto]s.
     * Retries are the caller's responsibility — this flow completes when
     * the socket closes.
     */
    fun events(): Flow<EventDto> = callbackFlow {
        try {
            client.webSocket(
                urlString = "/api/v1/events",
                request = { parameter("token", token) },
            ) {
                while (isActive) {
                    val frame = incoming.receive()
                    if (frame is Frame.Text) {
                        val text = frame.readText()
                        tryDecodeAndSend(text)
                    }
                }
            }
        } catch (t: Throwable) {
            close(t)
        }
        awaitClose { /* the webSocket builder owns its lifecycle */ }
    }

    private fun ProducerScope<EventDto>.tryDecodeAndSend(text: String) {
        val decoded: EventDto? = runCatching { json.decodeFromString<EventDto>(text) }.getOrNull()
        if (decoded != null) {
            trySend(decoded)
        }
    }

    fun close() {
        client.close()
    }
}
