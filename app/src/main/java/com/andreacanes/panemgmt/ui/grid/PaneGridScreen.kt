package com.andreacanes.panemgmt.ui.grid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import com.andreacanes.panemgmt.data.models.ApprovalDto
import com.andreacanes.panemgmt.data.models.EventDto
import com.andreacanes.panemgmt.data.models.PaneDto
import com.andreacanes.panemgmt.data.models.PaneState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneGridScreen(
    authStore: AuthStore,
    onOpenPane: (paneId: String) -> Unit,
    onLoggedOut: () -> Unit,
) {
    val config by authStore.configFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var panes by remember { mutableStateOf<List<PaneDto>>(emptyList()) }
    var approvals by remember { mutableStateOf<List<ApprovalDto>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // Load initial state + open WS stream whenever the auth config is ready.
    LaunchedEffect(config) {
        val cfg = config ?: return@LaunchedEffect
        val client = CompanionClient(cfg.baseUrl, cfg.bearerToken)
        loading = true
        error = null
        try {
            panes = client.listPanes()
            approvals = client.listApprovals()
            loading = false

            // Stream events until the socket closes or the effect is cancelled.
            client.events().collect { ev ->
                when (ev) {
                    is EventDto.Snapshot -> {
                        panes = ev.panes
                        approvals = ev.approvals
                    }
                    is EventDto.PaneStateChanged -> {
                        panes = panes.map { p ->
                            if (p.id == ev.paneId) p.copy(state = ev.new, updatedAt = ev.at) else p
                        }
                    }
                    is EventDto.PaneOutputChanged -> {
                        panes = panes.map { p ->
                            if (p.id == ev.paneId) {
                                p.copy(lastOutputPreview = ev.tail, updatedAt = ev.at)
                            } else {
                                p
                            }
                        }
                    }
                    is EventDto.ApprovalCreated -> {
                        approvals = approvals + ev.approval
                    }
                    is EventDto.ApprovalResolved -> {
                        approvals = approvals.filterNot { it.id == ev.id }
                    }
                    else -> Unit
                }
            }
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
        } finally {
            client.close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Panes") },
                actions = {
                    BadgedBox(
                        modifier = Modifier.padding(horizontal = 4.dp),
                        badge = {
                            if (approvals.isNotEmpty()) {
                                Badge { Text(approvals.size.toString()) }
                            }
                        },
                    ) {
                        IconButton(onClick = {
                            scope.launch {
                                val cfg = config ?: return@launch
                                val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                                runCatching {
                                    panes = c.listPanes()
                                    approvals = c.listApprovals()
                                }.onFailure { error = it.message }
                                c.close()
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            authStore.clear()
                            onLoggedOut()
                        }
                    }) {
                        Icon(Icons.Default.Logout, contentDescription = "Log out")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (loading && panes.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(16.dp),
                    )
                }
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(panes, key = { it.id }) { pane ->
                        val waitingHere = approvals.any { it.paneId == pane.id }
                        PaneCard(
                            pane = pane,
                            hasApproval = waitingHere,
                            onClick = { onOpenPane(pane.id) },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaneCard(
    pane: PaneDto,
    hasApproval: Boolean,
    onClick: () -> Unit,
) {
    val effectiveState = if (hasApproval) PaneState.Waiting else pane.state
    val stateColor = stateColor(effectiveState)

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(stateColor),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pane.id,
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pane.currentCommand,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = effectiveState.name.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = stateColor,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = pane.currentPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (pane.lastOutputPreview.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Surface(
                    tonalElevation = 2.dp,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(8.dp)) {
                        pane.lastOutputPreview.takeLast(5).forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            if (hasApproval) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "\u26A0 Approval pending — tap to decide",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

private fun stateColor(s: PaneState): Color = when (s) {
    PaneState.Idle -> Color(0xFF888888)
    PaneState.Running -> Color(0xFF4CAF50)
    PaneState.Waiting -> Color(0xFFFFB300)
    PaneState.Done -> Color(0xFF2196F3)
}
