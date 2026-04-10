package com.andreacanes.panemgmt.data

import com.andreacanes.panemgmt.data.models.CaptureDto
import com.andreacanes.panemgmt.data.models.HealthDto
import com.andreacanes.panemgmt.data.models.PaneDto
import com.andreacanes.panemgmt.data.models.ResolveApprovalRequest
import com.andreacanes.panemgmt.data.models.SendInputRequest
import com.andreacanes.panemgmt.data.models.SendVoiceRequest
import com.andreacanes.panemgmt.data.models.SessionDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.takeFrom
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

class CompanionClient(
    private val baseUrl: String,
    private val token: String,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        expectSuccess = true
        install(ContentNegotiation) {
            json(json)
        }
        install(WebSockets)
        install(DefaultRequest) {
            header(HttpHeaders.Authorization, "Bearer $token")
            val parsed = URLBuilder().takeFrom(baseUrl)
            url.protocol = parsed.protocol
            url.host = parsed.host
            url.port = parsed.port
        }
    }

    suspend fun health(): HealthDto =
        client.get("/api/v1/health").body()

    suspend fun listSessions(): List<SessionDto> =
        client.get("/api/v1/sessions").body()

    suspend fun listPanes(session: String): List<PaneDto> =
        client.get("/api/v1/sessions/$session/panes").body()

    suspend fun capture(paneId: String): CaptureDto =
        client.get("/api/v1/panes/$paneId/capture").body()

    suspend fun sendInput(paneId: String, text: String, submit: Boolean) {
        client.post("/api/v1/panes/$paneId/input") {
            contentType(ContentType.Application.Json)
            setBody(SendInputRequest(text = text, submit = submit))
        }
    }

    suspend fun sendVoice(paneId: String, transcript: String) {
        client.post("/api/v1/panes/$paneId/voice") {
            contentType(ContentType.Application.Json)
            setBody(SendVoiceRequest(transcript = transcript))
        }
    }

    suspend fun resolveApproval(id: String, decision: String) {
        client.post("/api/v1/approvals/$id/resolve") {
            contentType(ContentType.Application.Json)
            setBody(ResolveApprovalRequest(decision = decision))
        }
    }

    fun close() {
        client.close()
    }
}
