package com.andreacanes.panemgmt.ui.window

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.data.CompanionClient
import com.andreacanes.panemgmt.data.models.CreateWindowRequest
import com.andreacanes.panemgmt.data.models.CreateWindowResponse
import com.andreacanes.panemgmt.data.models.LaunchHostSessionRequest
import com.andreacanes.panemgmt.data.models.ProjectDto
import com.andreacanes.panemgmt.data.models.SyncProjectRequest
import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.statement.bodyAsText
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private val AndreaColor = Color(0xFF818CF8) // indigo
private val BravuraColor = Color(0xFFF59E0B) // amber
private val SullyColor = Color(0xFF14B8A6)   // teal

private enum class LauncherAccount(val key: String, val label: String, val color: Color) {
    Andrea("andrea", "Andrea", AndreaColor),
    Bravura("bravura", "Bravura", BravuraColor),
    Sully("sully", "Sully", SullyColor),
}

/**
 * One option in the host segmented control. `key = "local"` routes to
 * the existing `/windows` codepath (tmux window on the local main
 * session, starts `ncld`); any other key routes to
 * `/launch-host-session` (per-project tmux session on the named SSH
 * alias, starts `mncld`). Labels match the desktop's CreatePaneModal.
 *
 * The full list comes from `GET /api/v1/remote-hosts` at sheet open;
 * the bootstrap `[WSL, Mac]` keeps the segmented row populated even
 * if the host fetch fails or the user is fully offline.
 */
private data class LauncherHost(val key: String, val label: String)

private val WSL_HOST = LauncherHost("local", "WSL")
private val MAC_HOST_DEFAULT = LauncherHost("mac", "Mac")

/** Title-case an SSH alias for display ("mac" → "Mac"). Kept simple
 *  because the alias is already user-chosen and short. */
private fun aliasLabel(alias: String): String =
    alias.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

/**
 * Pull the readable error message out of a thrown exception. Ktor's
 * `ClientRequestException` carries the server's response body (which
 * contains the AppError JSON like `{"error": "bad request: ..."}`);
 * plain exceptions fall back to `message`. Without this the user sees
 * "Client request invalid: https://..." which is useless. Runs
 * `bodyAsText()` inside a `runCatching` so a body read failure can't
 * throw inside an error handler.
 */
private suspend fun extractErrorBody(t: Throwable): String {
    if (t is ClientRequestException) {
        val body = runCatching { t.response.bodyAsText() }.getOrNull().orEmpty()
        if (body.isNotBlank()) return body
    }
    return t.message ?: t::class.simpleName ?: "unknown error"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWindowSheet(
    authStore: AuthStore,
    defaultSessionName: String = "main",
    onDismiss: () -> Unit,
    onLaunched: (CreateWindowResponse) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    var projects by remember { mutableStateOf<List<ProjectDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var selectedProject by remember { mutableStateOf<ProjectDto?>(null) }
    var selectedAccount by remember { mutableStateOf(LauncherAccount.Andrea) }
    // Bootstrap with WSL + Mac so the row is interactive even before
    // the remote-hosts fetch lands. Replaced once the fetch succeeds.
    var hostOptions by remember {
        mutableStateOf(listOf(WSL_HOST, MAC_HOST_DEFAULT))
    }
    var selectedHost by remember { mutableStateOf(WSL_HOST) }
    var launching by remember { mutableStateOf(false) }
    // When a Mac launch fails with 400 "not mirrored", we show an
    // inline "Sync now" button that kicks the Mutagen helper and
    // retries the launch. Non-null = sync button visible, carries the
    // encoded_project to pass to /api/v1/sync-project-to-mac.
    var notMirroredFor by remember { mutableStateOf<String?>(null) }
    var syncing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val config = authStore.configFlow.first()
        if (config == null) {
            error = "Not authenticated"
            loading = false
            return@LaunchedEffect
        }
        val client = CompanionClient(config.baseUrl, config.bearerToken)
        try {
            projects = client.listProjects()
            // Replace the bootstrap [WSL, Mac] with the actual host list
            // from the desktop. WSL is always prepended because the
            // server only returns *remote* aliases. Failure is silent —
            // the bootstrap list keeps working.
            runCatching { client.listRemoteHosts() }
                .onSuccess { resp ->
                    hostOptions = listOf(WSL_HOST) + resp.hosts
                        .filter { it.isNotBlank() && it != "local" }
                        .map { LauncherHost(it, aliasLabel(it)) }
                }
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
        } finally {
            loading = false
            client.close()
        }
    }

    val filtered = remember(projects, query) {
        if (query.isBlank()) {
            projects
        } else {
            val q = query.lowercase()
            projects.filter {
                it.displayName.lowercase().contains(q) ||
                    it.actualPath.lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Launch window",
                style = MaterialTheme.typography.titleMedium,
            )

            // Host selector — WSL keeps the pre-existing "add a window to
            // the local main session" flow; Mac hits the new
            // `/launch-host-session` endpoint which creates or attaches
            // to a per-project Mac tmux session and starts mncld.
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                hostOptions.forEachIndexed { index, host ->
                    SegmentedButton(
                        selected = selectedHost.key == host.key,
                        onClick = { selectedHost = host },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = hostOptions.size,
                        ),
                    ) {
                        Text(host.label)
                    }
                }
            }

            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                LauncherAccount.entries.forEachIndexed { index, acct ->
                    SegmentedButton(
                        selected = selectedAccount == acct,
                        onClick = { selectedAccount = acct },
                        shape = SegmentedButtonDefaults.itemShape(
                            index = index,
                            count = LauncherAccount.entries.size,
                        ),
                        colors = SegmentedButtonDefaults.colors(
                            activeContainerColor = acct.color.copy(alpha = 0.18f),
                            activeContentColor = acct.color,
                        ),
                    ) {
                        Text(acct.label)
                    }
                }
            }

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Search projects") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 420.dp),
            ) {
                when {
                    loading -> Box(
                        Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                    error != null -> Text(
                        text = "Failed to load projects: $error",
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                    filtered.isEmpty() -> Text(
                        text = if (query.isBlank()) "No projects found" else "No matches for \"$query\"",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                    else -> LazyColumn(
                        contentPadding = PaddingValues(vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(filtered, key = { it.encodedName }) { project ->
                            ProjectRow(
                                project = project,
                                selected = selectedProject?.encodedName == project.encodedName,
                                accentColor = selectedAccount.color,
                                onClick = { selectedProject = project },
                            )
                        }
                    }
                }
            }

            // Inline "Sync now" retry: only visible when the previous
            // Mac launch failed with the "not mirrored" 400. One tap
            // runs Mutagen, then retries the launch automatically.
            if (notMirroredFor != null) {
                Button(
                    onClick = {
                        val proj = selectedProject ?: return@Button
                        val encoded = notMirroredFor ?: return@Button
                        scope.launch {
                            syncing = true
                            val config = authStore.configFlow.first() ?: run {
                                syncing = false
                                return@launch
                            }
                            val client = CompanionClient(config.baseUrl, config.bearerToken)
                            val syncResult = runCatching {
                                client.syncProjectToMac(SyncProjectRequest(encodedProject = encoded))
                            }
                            syncing = false
                            syncResult.onSuccess {
                                // Sync done — retry the original launch
                                // immediately so the user doesn't have
                                // to hunt for the Launch button again.
                                notMirroredFor = null
                                error = null
                                launching = true
                                val retry = runCatching {
                                    val resp = client.launchHostSession(
                                        LaunchHostSessionRequest(
                                            host = selectedHost.key,
                                            account = selectedAccount.key,
                                            projectPath = proj.actualPath,
                                            projectDisplayName = proj.displayName,
                                        )
                                    )
                                    CreateWindowResponse(
                                        windowIndex = resp.windowIndex,
                                        paneId = resp.paneId,
                                    )
                                }
                                client.close()
                                launching = false
                                retry.onSuccess { response ->
                                    onLaunched(response)
                                    onDismiss()
                                }.onFailure { t ->
                                    error = "Launch still failed after sync: " +
                                        extractErrorBody(t)
                                }
                            }.onFailure { t ->
                                client.close()
                                error = "Sync failed: " + extractErrorBody(t)
                            }
                        }
                    },
                    enabled = !syncing && !launching,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (syncing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("Syncing to Mac…")
                    } else {
                        Text("Sync now and retry")
                    }
                }
            }

            Button(
                onClick = {
                    val proj = selectedProject ?: return@Button
                    notMirroredFor = null
                    error = null
                    scope.launch {
                        launching = true
                        val config = authStore.configFlow.first() ?: run {
                            launching = false
                            return@launch
                        }
                        val client = CompanionClient(config.baseUrl, config.bearerToken)
                        val result = runCatching {
                            if (selectedHost.key == "local") {
                                client.createWindow(
                                    CreateWindowRequest(
                                        sessionName = defaultSessionName,
                                        projectPath = proj.actualPath,
                                        projectDisplayName = proj.displayName,
                                        account = selectedAccount.key,
                                    )
                                )
                            } else {
                                // Remote host: different endpoint, same
                                // response shape for `onLaunched`. The
                                // CreateWindowResponse mapping drops
                                // `session_name` since callers only need
                                // the pane_id to deep-link.
                                val resp = client.launchHostSession(
                                    LaunchHostSessionRequest(
                                        host = selectedHost.key,
                                        account = selectedAccount.key,
                                        projectPath = proj.actualPath,
                                        projectDisplayName = proj.displayName,
                                    )
                                )
                                CreateWindowResponse(
                                    windowIndex = resp.windowIndex,
                                    paneId = resp.paneId,
                                )
                            }
                        }
                        client.close()
                        launching = false
                        result.onSuccess { response ->
                            onLaunched(response)
                            onDismiss()
                        }.onFailure { t ->
                            val body = extractErrorBody(t)
                            // Detect the pre-flight "project not
                            // mirrored" case so we can offer a
                            // one-click Sync+Retry instead of making
                            // the user bounce to the desktop.
                            if (body.contains("not mirrored", ignoreCase = true) && selectedHost.key != "local") {
                                notMirroredFor = proj.encodedName
                                error = body
                            } else {
                                error = "Launch failed: $body"
                            }
                        }
                    }
                },
                enabled = selectedProject != null && !launching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (launching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Launching…")
                } else {
                    Text("Launch as ${selectedAccount.label}")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProjectRow(
    project: ProjectDto,
    selected: Boolean,
    accentColor: Color,
    onClick: () -> Unit,
) {
    val borderColor = if (selected) accentColor else Color.Transparent
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(
                width = 1.5.dp,
                color = borderColor,
                shape = RoundedCornerShape(12.dp),
            )
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = project.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (project.activePaneCount > 0) {
                    ActivePaneBadge(count = project.activePaneCount)
                }
                project.gitBranch?.let {
                    Spacer(Modifier.width(6.dp))
                    BranchBadge(branch = it)
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = project.actualPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun ActivePaneBadge(count: Int) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(Color(0xFF22C55E).copy(alpha = 0.18f))
            .padding(horizontal = 7.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color(0xFF22C55E)),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF22C55E),
        )
    }
}

@Composable
private fun BranchBadge(branch: String) {
    Text(
        text = branch,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        maxLines = 1,
    )
}
