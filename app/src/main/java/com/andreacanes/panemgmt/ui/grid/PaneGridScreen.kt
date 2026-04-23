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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.data.CompanionClient
import com.andreacanes.panemgmt.data.models.AccountRateLimitDto
import com.andreacanes.panemgmt.data.models.ApprovalDto
import com.andreacanes.panemgmt.data.models.EventDto
import com.andreacanes.panemgmt.data.models.PaneDto
import com.andreacanes.panemgmt.data.models.PaneState
import com.andreacanes.panemgmt.data.models.WaitingReason
import com.andreacanes.panemgmt.data.models.UsageDto
import com.andreacanes.panemgmt.service.ApprovalService
import com.andreacanes.panemgmt.ui.common.StatusChip
import com.andreacanes.panemgmt.ui.theme.StatusColors
import com.andreacanes.panemgmt.ui.window.CreateWindowSheet
import kotlinx.coroutines.launch

private enum class GridTab(val label: String) {
    Active("Active"),
    Waiting("Waiting"),
    Stashed("Stashed"),
    All("All"),
}

/** A pane is "stashed" when it's neither active nor waiting and hasn't
 *  been touched in over an hour — long-idle background sessions you've
 *  forgotten about. */
private const val STASHED_THRESHOLD_MS: Long = 60L * 60L * 1000L

private enum class ClaudeAccount(val label: String, val color: Color) {
    Andrea("Andrea", Color(0xFF818CF8)),     // indigo
    Bravura("Bravura", Color(0xFFF59E0B)),   // amber
    Sully("Sully", Color(0xFF14B8A6)),       // teal
}

/**
 * Pick the account for a pane. Prefer the server-detected
 * `claudeAccount` field (read from `/proc/<pid>/environ` on local panes,
 * synthesized from pane assignment on remote panes), fall back
 * to the command-string regex for panes where detection failed or the
 * server is old.
 */
private fun accountFor(pane: PaneDto): ClaudeAccount? {
    pane.claudeAccount?.let {
        return when (it) {
            "sully" -> ClaudeAccount.Sully
            "bravura" -> ClaudeAccount.Bravura
            "andrea" -> ClaudeAccount.Andrea
            else -> null
        }
    }
    val c = pane.currentCommand.lowercase()
    if (c == "claude-c") return ClaudeAccount.Sully
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
    // are enqueued but blocked with numBlocked++. We track the denial
    // state explicitly so the user gets a persistent, clickable banner
    // instead of silently missing every approval notification.
    var notifPermissionDenied by remember { mutableStateOf(false) }
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { granted -> notifPermissionDenied = !granted },
    )
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            notifPermissionDenied = !granted
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
    val pagerState = rememberPagerState(initialPage = GridTab.Active.ordinal) { GridTab.entries.size }
    var isRefreshing by remember { mutableStateOf(false) }
    var showCreateSheet by remember { mutableStateOf(false) }
    var rateLimits by remember { mutableStateOf<List<AccountRateLimitDto>>(emptyList()) }

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

    suspend fun refreshRateLimitsOnce(cfg: com.andreacanes.panemgmt.data.AuthConfig) {
        val client = CompanionClient(cfg.baseUrl, cfg.bearerToken)
        try {
            rateLimits = runCatching { client.rateLimits() }.getOrDefault(emptyList())
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
        launch { runCatching { refreshRateLimitsOnce(cfg) } }
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
                        is EventDto.PaneRemoved -> {
                            panes = panes.filterNot { it.id == ev.paneId }
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

    // Refresh rate limits every 60s in the background. The companion
    // caches the Anthropic API response so this is cheap.
    LaunchedEffect(config) {
        val cfg = config ?: return@LaunchedEffect
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            runCatching { refreshRateLimitsOnce(cfg) }
        }
    }

    val approvalPaneIds = remember(approvals) { approvals.map { it.paneId }.toSet() }
    fun effectiveState(p: PaneDto): PaneState =
        if (approvalPaneIds.contains(p.id)) PaneState.Waiting else p.state
    fun isWaiting(p: PaneDto): Boolean =
        p.state == PaneState.Waiting || approvalPaneIds.contains(p.id)

    // `nowMs` is refreshed every 60s so the Stashed filter (anything
    // older than an hour) updates even when no pane events arrive.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            nowMs = System.currentTimeMillis()
        }
    }

    /** "Last real activity" = JSONL mtime if Claude is/was bound to this
     *  pane, otherwise the DTO's updated_at. lastActivityAt is null for
     *  non-Claude panes and stale for ones where Claude has exited (the
     *  poller clears claude_session_id on exit), so the fallback covers
     *  those cases gracefully. */
    fun lastActivity(p: PaneDto): Long = p.lastActivityAt ?: p.updatedAt

    fun isStashed(p: PaneDto): Boolean =
        p.state != PaneState.Running &&
            !isWaiting(p) &&
            (nowMs - lastActivity(p)) > STASHED_THRESHOLD_MS

    val activeCount = remember(panes, approvalPaneIds) {
        panes.count { it.state == PaneState.Running && !approvalPaneIds.contains(it.id) }
    }
    val waitingCount = remember(panes, approvalPaneIds) {
        panes.count { isWaiting(it) }
    }
    val stashedCount = remember(panes, approvalPaneIds, nowMs) {
        panes.count { isStashed(it) }
    }
    val allCount = panes.size

    // Every tab is ordered by the visible terminal coordinate
    // (session:window.pane) so that the cards stay in a stable, predictable
    // position regardless of which filter is active or what the companion's
    // snapshot order happens to be.
    val terminalOrder = compareBy<PaneDto>({ it.sessionName }, { it.windowIndex }, { it.paneIndex })

    fun panesForTab(tab: GridTab): List<PaneDto> = when (tab) {
        GridTab.Active  -> panes.filter { it.state == PaneState.Running && !approvalPaneIds.contains(it.id) }
                                .sortedWith(terminalOrder)
        GridTab.Waiting -> panes.filter { isWaiting(it) }.sortedWith(terminalOrder)
        GridTab.Stashed -> panes.filter { isStashed(it) }.sortedWith(terminalOrder)
        GridTab.All     -> panes.sortedWith(terminalOrder)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        ConnectionDot(
                            connected = wsConnected,
                            reconnecting = !wsConnected && (loading || error != null),
                        )
                        rateLimits.forEach { rl ->
                            RateLimitChip(rl)
                        }
                    }
                },
                actions = {
                    if (approvals.isNotEmpty()) {
                        Badge(modifier = Modifier.padding(end = 4.dp)) {
                            Text(approvals.size.toString())
                        }
                    }
                    IconButton(onClick = { showCreateSheet = true }) {
                        Icon(Icons.Default.Add, contentDescription = "New window")
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
            if (notifPermissionDenied) {
                Card(
                    onClick = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifPermissionLauncher.launch(
                                android.Manifest.permission.POST_NOTIFICATIONS,
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(
                        "Notifications disabled — approval prompts will be missed. Tap to grant.",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(12.dp),
                    )
                }
            }

            TabRow(selectedTabIndex = pagerState.currentPage) {
                GridTab.entries.forEachIndexed { index, tab ->
                    val count = when (tab) {
                        GridTab.Active  -> activeCount
                        GridTab.Waiting -> waitingCount
                        GridTab.Stashed -> stashedCount
                        GridTab.All     -> allCount
                    }
                    Tab(
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
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
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    val tab = GridTab.entries[page]
                    // nowMs in the key so the Stashed tab re-filters when the
                    // hour-old threshold sweeps over a previously fresh pane.
                    val tabPanes = remember(panes, approvalPaneIds, page, nowMs) { panesForTab(tab) }

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
                            scope.launch {
                                val cfg = config ?: return@launch
                                runCatching { refreshRateLimitsOnce(cfg) }
                            }
                        },
                        state = rememberPullToRefreshState(),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        if (tabPanes.isEmpty()) {
                            EmptyTabState(tab = tab)
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                items(tabPanes, key = { it.id }) { pane ->
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

/**
 * Compact per-account rate limit chip for the toolbar. Shows both the
 * 5h session and 7d weekly utilization stacked vertically with mini
 * progress bars and countdowns. Account color from [ClaudeAccount].
 */
@Composable
private fun RateLimitChip(rl: AccountRateLimitDto) {
    val acctColor = when (rl.account) {
        "sully" -> ClaudeAccount.Sully.color
        "bravura" -> ClaudeAccount.Bravura.color
        else -> ClaudeAccount.Andrea.color
    }
    var showDetail by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(acctColor.copy(alpha = 0.10f))
                .clickable { showDetail = true }
                .padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Account initial
            Text(
                text = rl.label.take(1),
                style = MaterialTheme.typography.labelSmall,
                color = acctColor,
                fontWeight = FontWeight.Bold,
            )
            // Two rows: 5h and 7d
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                RateLimitRow("5h", rl.fiveHourPct, rl.fiveHourResetsAt, acctColor)
                RateLimitRow("7d", rl.sevenDayPct, rl.sevenDayResetsAt, acctColor)
            }
        }

        if (showDetail) {
            RateLimitDetailPopup(rl, acctColor, onDismiss = { showDetail = false })
        }
    }
}

@Composable
private fun RateLimitDetailPopup(
    rl: AccountRateLimitDto,
    acctColor: Color,
    onDismiss: () -> Unit,
) {
    Popup(
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true),
    ) {
        Card(
            modifier = Modifier
                .width(220.dp)
                .padding(top = 4.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Header
                Text(
                    text = rl.label,
                    style = MaterialTheme.typography.titleSmall,
                    color = acctColor,
                    fontWeight = FontWeight.Bold,
                )

                // 5-hour window
                RateLimitDetailSection(
                    title = "5-hour session",
                    pct = rl.fiveHourPct,
                    totalMinutes = 300,  // 5h = 300 min
                    resetsAt = rl.fiveHourResetsAt,
                    color = acctColor,
                )

                // 7-day window
                RateLimitDetailSection(
                    title = "7-day weekly",
                    pct = rl.sevenDayPct,
                    totalMinutes = 10_080,  // 7d = 10,080 min
                    resetsAt = rl.sevenDayResetsAt,
                    color = acctColor,
                )
            }
        }
    }
}

@Composable
private fun RateLimitDetailSection(
    title: String,
    pct: Double,
    totalMinutes: Int,
    resetsAt: Long?,
    color: Color,
) {
    val pctInt = pct.toInt().coerceIn(0, 100)
    val usedMinutes = (pct / 100.0 * totalMinutes).toInt()
    val remainingMinutes = (totalMinutes - usedMinutes).coerceAtLeast(0)
    val countdown = fmtCountdown(resetsAt)

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Progress bar — 10 segments for finer granularity
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val filledSegments = (pctInt + 9) / 10  // 10 segments, 10% each
            repeat(10) { i ->
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(
                            if (i < filledSegments) color
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$pctInt% used",
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontFamily = FontFamily.Monospace,
            )
            Text(
                text = fmtMinutesRemaining(remainingMinutes),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
        }
        if (countdown.isNotEmpty()) {
            Text(
                text = "resets in $countdown",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@Composable
private fun RateLimitRow(
    label: String,
    pct: Double,
    resetsAt: Long?,
    color: Color,
) {
    val pctInt = pct.toInt().coerceIn(0, 100)
    val filledSegments = (pctInt + 19) / 20  // 5 segments, 20% each, round up
    val countdown = fmtCountdown(resetsAt)

    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 8.sp),
            color = color.copy(alpha = 0.6f),
        )
        // 5-segment mini bar
        Row(horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            repeat(5) { i ->
                Box(
                    modifier = Modifier
                        .size(width = 5.dp, height = 7.dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (i < filledSegments) color
                            else MaterialTheme.colorScheme.surfaceContainerHighest,
                        ),
                )
            }
        }
        Text(
            text = "$pctInt%",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
            color = color,
            fontFamily = FontFamily.Monospace,
        )
        if (countdown.isNotEmpty()) {
            Text(
                text = countdown,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp),
                color = color.copy(alpha = 0.6f),
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun fmtCountdown(resetsAtUnixSec: Long?): String {
    val r = resetsAtUnixSec ?: return ""
    val remaining = r - (System.currentTimeMillis() / 1000)
    return when {
        remaining <= 0 -> "now"
        remaining < 3600 -> "${remaining / 60}m"
        remaining < 86400 -> "${remaining / 3600}h"
        else -> "${remaining / 86400}d"
    }
}

private fun fmtMinutesRemaining(minutes: Int): String = when {
    minutes < 60 -> "${minutes}m left"
    minutes < 1440 -> "${minutes / 60}h ${minutes % 60}m left"
    else -> {
        val days = minutes / 1440
        val hours = (minutes % 1440) / 60
        "${days}d ${hours}h left"
    }
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
        GridTab.Stashed ->
            "No stashed panes" to "Idle panes appear here after an hour of inactivity."
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
                // Approvals always map to Request; otherwise use the server
                // reason. Non-Waiting states carry no reason.
                val effectiveReason = if (effectiveState == PaneState.Waiting) {
                    if (hasApproval) WaitingReason.Request else pane.waitingReason
                } else null
                StatusChip(state = effectiveState, compact = true, waitingReason = effectiveReason)
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
