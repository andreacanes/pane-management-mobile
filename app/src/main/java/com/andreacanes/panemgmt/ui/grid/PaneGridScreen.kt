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
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.data.CompanionClient
import com.andreacanes.panemgmt.data.models.ApprovalDto
import com.andreacanes.panemgmt.data.models.EventDto
import com.andreacanes.panemgmt.data.models.PaneDto
import com.andreacanes.panemgmt.data.models.PaneState
import com.andreacanes.panemgmt.data.models.UsageDto
import com.andreacanes.panemgmt.service.ApprovalService
import com.andreacanes.panemgmt.ui.common.StatusChip
import com.andreacanes.panemgmt.ui.theme.StatusColors
import com.andreacanes.panemgmt.ui.window.CreateWindowSheet
import kotlinx.coroutines.launch

private enum class GridTab(val label: String) {
    Active("Active"),
    Waiting("Waiting"),
    All("All"),
}

private enum class ClaudeAccount(val label: String, val color: Color) {
    Andrea("Andrea", Color(0xFF818CF8)),
    Bravura("Bravura", Color(0xFFF59E0B)),
}

/**
 * Pick the account for a pane. Prefer the server-detected
 * `claudeAccount` field (read from `/proc/<pid>/environ`), fall back
 * to the command-string regex for panes where detection failed or the
 * server is old.
 */
private fun accountFor(pane: PaneDto): ClaudeAccount? {
    pane.claudeAccount?.let {
        return when (it) {
            "bravura" -> ClaudeAccount.Bravura
            "andrea" -> ClaudeAccount.Andrea
            else -> null
        }
    }
    val c = pane.currentCommand.lowercase()
    if (c == "claude-b") return ClaudeAccount.Bravura
    if (c.contains("claude")) return ClaudeAccount.Andrea
    return null
}

private fun projectLabel(encoded: String?): String? {
    if (encoded.isNullOrBlank()) return null
    val decoded = encoded.replace("-", "/").trimEnd('/')
    val last = decoded.substringAfterLast('/')
    return last.ifBlank { encoded }
}

private fun fmtUsd(cost: Double): String = when {
    cost >= 100.0 -> "$" + String.format("%.0f", cost)
    cost >= 10.0  -> "$" + String.format("%.1f", cost)
    else          -> "$" + String.format("%.2f", cost)
}

private fun fmtTokens(n: Long): String = when {
    n >= 1_000_000L -> String.format("%.1fM", n / 1_000_000.0)
    n >= 1_000L     -> String.format("%.1fk", n / 1_000.0)
    else            -> n.toString()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneGridScreen(
    authStore: AuthStore,
    onOpenPane: (paneId: String) -> Unit,
    onLoggedOut: () -> Unit,
) {
    val config by authStore.configFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Runtime POST_NOTIFICATIONS on API 33+. Without this the service
    // foreground notification and all approval/attention notifications
    // are enqueued but blocked with numBlocked++.
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* result ignored — if denied, user can re-grant in settings */ },
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Kick off the background watcher service whenever we have valid auth.
    // startForegroundService is idempotent — re-entering the grid is a no-op.
    LaunchedEffect(config?.baseUrl, config?.bearerToken) {
        if (config != null) {
            ApprovalService.start(context)
        }
    }

    var panes by remember { mutableStateOf<List<PaneDto>>(emptyList()) }
    var approvals by remember { mutableStateOf<List<ApprovalDto>>(emptyList()) }
    var usage by remember { mutableStateOf<UsageDto?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var wsConnected by remember { mutableStateOf(false) }
    var activeTab by remember { mutableIntStateOf(GridTab.Active.ordinal) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showCreateSheet by remember { mutableStateOf(false) }

    // Grid refresh: panes + approvals only. Usage is intentionally NOT
    // awaited here — it scans every JSONL file on disk on the Rust side
    // and used to pin the pull-to-refresh spinner for 1-2 s. The grid
    // only needs panes/approvals to show fresh data; usage lives on a
    // separate coroutine and updates the summary row a moment later.
    suspend fun refreshOnce(cfg: com.andreacanes.panemgmt.data.AuthConfig) {
        val client = CompanionClient(cfg.baseUrl, cfg.bearerToken)
        try {
            panes = client.listPanes()
            approvals = client.listApprovals()
        } finally {
            client.close()
        }
    }

    suspend fun refreshUsageOnce(cfg: com.andreacanes.panemgmt.data.AuthConfig) {
        val client = CompanionClient(cfg.baseUrl, cfg.bearerToken)
        try {
            usage = runCatching { client.usage() }.getOrNull()
        } finally {
            client.close()
        }
    }

    // Auto-reconnecting WebSocket loop. The connection drops whenever the
    // companion exe restarts (full Tauri rebuild) or the host briefly loses
    // network. Without a retry the screen sticks on "Software caused
    // connection abort" forever and the user has to relaunch the app.
    LaunchedEffect(config) {
        val cfg = config ?: return@LaunchedEffect
        var backoff = 1_000L
        loading = true
        error = null
        // Usage is expensive (full JSONL disk scan) so keep it off the
        // WS-open hot path — fire it on a side coroutine that runs in
        // parallel. First load still gets it within ~1 s without
        // delaying the grid's first paint.
        launch { runCatching { refreshUsageOnce(cfg) } }
        while (true) {
            val client = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            try {
                panes = client.listPanes()
                approvals = client.listApprovals()
                loading = false
                wsConnected = true
                error = null
                backoff = 1_000L

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
                        is EventDto.PaneUpdated -> {
                            // Replace the whole pane dto — used for
                            // metadata updates like claude_account that
                            // state-change events can't represent.
                            val incoming = ev.pane
                            val existing = panes.any { it.id == incoming.id }
                            panes = if (existing) {
                                panes.map { if (it.id == incoming.id) incoming else it }
                            } else {
                                panes + incoming
                            }
                        }
                        is EventDto.PaneOutputChanged -> {
                            // Grid doesn't render lastOutputPreview anymore — Phase 2
                            // moved that to the detail screen. Ignore the tail entirely
                            // so Claude's status-bar ticks don't rebuild the panes list
                            // and recompose every card N times per second.
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
                // Stream completed cleanly — try to reconnect immediately.
                wsConnected = false
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                wsConnected = false
                error = t.message ?: t::class.simpleName
            } finally {
                runCatching { client.close() }
            }
            kotlinx.coroutines.delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    val approvalPaneIds = remember(approvals) { approvals.map { it.paneId }.toSet() }
    fun effectiveState(p: PaneDto): PaneState =
        if (approvalPaneIds.contains(p.id)) PaneState.Waiting else p.state
    fun isWaiting(p: PaneDto): Boolean =
        p.state == PaneState.Waiting || approvalPaneIds.contains(p.id)

    val activeCount = remember(panes, approvalPaneIds) {
        panes.count { it.state == PaneState.Running && !approvalPaneIds.contains(it.id) }
    }
    val waitingCount = remember(panes, approvalPaneIds) {
        panes.count { isWaiting(it) }
    }
    val allCount = panes.size

    val filteredPanes = remember(panes, approvalPaneIds, activeTab) {
        when (GridTab.entries[activeTab]) {
            GridTab.Active  -> panes.filter { it.state == PaneState.Running && !approvalPaneIds.contains(it.id) }
            GridTab.Waiting -> panes.filter { isWaiting(it) }
            GridTab.All     -> panes
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Panes")
                        Spacer(Modifier.width(10.dp))
                        ConnectionDot(
                            connected = wsConnected,
                            reconnecting = !wsConnected && (loading || error != null),
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showCreateSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "New window")
                    }
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
                                isRefreshing = true
                                runCatching { refreshOnce(cfg) }
                                    .onFailure { error = it.message }
                                isRefreshing = false
                            }
                            scope.launch {
                                val cfg = config ?: return@launch
                                runCatching { refreshUsageOnce(cfg) }
                            }
                        }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                    IconButton(onClick = {
                        scope.launch {
                            ApprovalService.stop(context)
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
            TabRow(selectedTabIndex = activeTab) {
                GridTab.entries.forEachIndexed { index, tab ->
                    val count = when (tab) {
                        GridTab.Active  -> activeCount
                        GridTab.Waiting -> waitingCount
                        GridTab.All     -> allCount
                    }
                    Tab(
                        selected = activeTab == index,
                        onClick = { activeTab = index },
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tab.label)
                                if (count > 0) {
                                    Spacer(Modifier.width(6.dp))
                                    CountBadge(count = count, highlight = tab == GridTab.Waiting)
                                }
                            }
                        },
                    )
                }
            }

            usage?.let { UsageSummaryRow(it) }

            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp),
                )
            }

            if (loading && panes.isEmpty()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            } else {
                PullToRefreshBox(
                    isRefreshing = isRefreshing,
                    onRefresh = {
                        scope.launch {
                            val cfg = config ?: return@launch
                            isRefreshing = true
                            runCatching { refreshOnce(cfg) }
                                .onFailure { error = it.message }
                            isRefreshing = false
                        }
                        scope.launch {
                            val cfg = config ?: return@launch
                            runCatching { refreshUsageOnce(cfg) }
                        }
                    },
                    state = rememberPullToRefreshState(),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (filteredPanes.isEmpty()) {
                        EmptyTabState(tab = GridTab.entries[activeTab])
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            items(filteredPanes, key = { it.id }) { pane ->
                                PaneCard(
                                    pane = pane,
                                    effectiveState = effectiveState(pane),
                                    hasApproval = approvalPaneIds.contains(pane.id),
                                    onClick = { onOpenPane(pane.id) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCreateSheet) {
        CreateWindowSheet(
            authStore = authStore,
            onDismiss = { showCreateSheet = false },
            onLaunched = { response ->
                showCreateSheet = false
                onOpenPane(response.paneId)
            },
        )
    }
}

@Composable
private fun ConnectionDot(connected: Boolean, reconnecting: Boolean) {
    val color = when {
        connected -> StatusColors.Running
        reconnecting -> StatusColors.Waiting
        else -> StatusColors.Idle
    }
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color),
    )
}

@Composable
private fun CountBadge(count: Int, highlight: Boolean) {
    val bg = if (highlight) StatusColors.Waiting.copy(alpha = 0.18f)
             else MaterialTheme.colorScheme.surfaceContainerHigh
    val fg = if (highlight) StatusColors.Waiting
             else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 1.dp),
    ) {
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun UsageSummaryRow(u: UsageDto) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UsageStat("Cost", fmtUsd(u.totalCostUsd))
        UsageStat("Tokens", fmtTokens(u.totalTokens))
        UsageStat("Projects", u.projects.toString())
        UsageStat("Sessions", u.sessions.toString())
    }
}

@Composable
private fun UsageStat(label: String, value: String) {
    Column {
        Text(
            text = label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun EmptyTabState(tab: GridTab) {
    val (title, hint) = when (tab) {
        GridTab.Active ->
            "No Claude sessions running" to "Start one from tmux or the desktop app."
        GridTab.Waiting ->
            "Nothing waiting on you" to "You'll see approvals here when they arrive."
        GridTab.All ->
            "No panes" to "Open a tmux pane on the host to see it here."
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                hint,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PaneCard(
    pane: PaneDto,
    effectiveState: PaneState,
    hasApproval: Boolean,
    onClick: () -> Unit,
) {
    val title = pane.projectDisplayName
        ?: projectLabel(pane.projectEncodedName)
        ?: pane.currentPath
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
        ?: pane.sessionName.ifBlank { pane.id }
    val coords = "${pane.sessionName}:${pane.windowIndex}.${pane.paneIndex}"
    val claudeSessionShort = pane.claudeSessionId?.take(8)
    val account = accountFor(pane)
    val elapsed = formatElapsed(pane.updatedAt)

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                account?.let {
                    Spacer(Modifier.width(8.dp))
                    AccountPill(account = it)
                }
                Spacer(Modifier.width(8.dp))
                StatusChip(state = effectiveState, compact = true)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = coords,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                )
                claudeSessionShort?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                if (elapsed != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = elapsed,
                        style = MaterialTheme.typography.labelSmall,
                        color = when (effectiveState) {
                            PaneState.Waiting -> StatusColors.Waiting
                            PaneState.Idle -> StatusColors.Idle
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
            Text(
                text = pane.currentPath,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                modifier = Modifier.padding(top = 2.dp),
            )
            if (hasApproval) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "\u26A0 Approval pending — tap to decide",
                    style = MaterialTheme.typography.labelMedium,
                    color = StatusColors.Waiting,
                )
            }
        }
    }
}

@Composable
private fun AccountPill(account: ClaudeAccount) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(account.color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(account.color),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            text = account.label.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = account.color,
        )
    }
}

/**
 * Human-readable elapsed time since [epochMs]. Returns null if the
 * timestamp is in the future or less than 5 seconds ago (too noisy
 * for running panes that update every 2 s).
 */
private fun formatElapsed(epochMs: Long): String? {
    val now = System.currentTimeMillis()
    val delta = now - epochMs
    if (delta < 5_000) return null
    val seconds = delta / 1_000
    val minutes = seconds / 60
    val hours = minutes / 60
    return when {
        seconds < 60 -> "${seconds}s ago"
        minutes < 60 -> "${minutes}m ago"
        hours < 24   -> "${hours}h ${minutes % 60}m ago"
        else         -> "${hours / 24}d ${hours % 24}h ago"
    }
}
