package com.andreacanes.panemgmt.ui.detail

import android.Manifest
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TextField
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.andreacanes.panemgmt.ViewedPaneBus
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.data.CompanionClient
import com.andreacanes.panemgmt.data.models.ApprovalDto
import com.andreacanes.panemgmt.data.models.AttachRemoteSessionRequest
import com.andreacanes.panemgmt.data.models.Decision
import com.andreacanes.panemgmt.data.models.EventDto
import com.andreacanes.panemgmt.data.models.ImageItemDto
import com.andreacanes.panemgmt.data.models.PaneDto
import com.andreacanes.panemgmt.ui.common.collectVoiceInput
import com.andreacanes.panemgmt.ui.theme.StatusColors
import com.andreacanes.panemgmt.voice.VoiceInputController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

// 0 = "all scrollback" — the companion translates this to tmux
// `capture-pane -S -` which grabs the full history buffer.
private const val CAPTURE_LINES = 0

// Max entries retained by LineCache. With /capture now returning full
// replayed scrollback (typically 400-700 lines) and ANSI-decorated strings
// that hash-differ on tiny pen-state changes, a generous cache cuts the
// per-refetch parseAnsiLine work dramatically. 2000 entries ~= 2-3 refetches'
// worth — plenty for LRU re-use while capping memory.
private const val LINE_CACHE_MAX = 2000
private const val LINE_CACHE_TRIM_TARGET = 1500

/**
 * Incremental line-cleaning cache. Keeps a map from raw line → parsed
 * AnnotatedString so that on a refetch where only the last few lines
 * changed, we skip the expensive stripAnsi + regex + ANSI-parse work for
 * everything that's unchanged. Keyed on the raw String (server returns
 * verbatim for unchanged lines).
 *
 * Uses `accessOrder = true` so get() promotes entries to "recently used".
 * When capacity is exceeded we evict the least-recently-used entries down
 * to LINE_CACHE_TRIM_TARGET.
 */
private class LineCache {
    private val cache = LinkedHashMap<String, AnnotatedString>(LINE_CACHE_MAX, 0.75f, true)

    fun getOrParse(raw: String, defaultColor: Color): AnnotatedString {
        cache[raw]?.let { return it }
        val parsed = if (raw.isBlank()) AnnotatedString("")
                     else parseAnsiLine(raw, defaultColor)
        cache[raw] = parsed
        if (cache.size > LINE_CACHE_MAX) {
            val evict = cache.size - LINE_CACHE_TRIM_TARGET
            val iter = cache.entries.iterator()
            repeat(evict) { if (iter.hasNext()) { iter.next(); iter.remove() } }
        }
        return parsed
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaneDetailScreen(
    authStore: AuthStore,
    paneId: String,
    onBack: () -> Unit,
    onNavigateToPane: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val config by authStore.configFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    // Per-pane state: key to paneId so navigating/creating a sibling pane
    // gives a clean slate. Without the key, the captured chat `lines` from
    // the previous pane persist in the composition until the first fresh
    // fetch lands (~50-200 ms), which visibly bleeds the parent pane's
    // history into a newly-created child. Same reasoning for paneInfo,
    // error, approvals, currentApproval — all pane-scoped content.
    // `inputText` deliberately does NOT key on paneId so a typed draft
    // survives swipe-between-siblings.
    var lines by remember(paneId) { mutableStateOf<List<String>>(emptyList()) }
    var error by remember(paneId) { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var voiceListening by remember { mutableStateOf(false) }
    var voiceTranscript by remember { mutableStateOf("") }
    var approvals by remember(paneId) { mutableStateOf<List<ApprovalDto>>(emptyList()) }
    var currentApproval by remember(paneId) { mutableStateOf<ApprovalDto?>(null) }
    var paneInfo by remember(paneId) { mutableStateOf<PaneDto?>(null) }
    var showKillDialog by remember { mutableStateOf(false) }
    // Split/fork chooser — opens when the user taps + in the toolbar.
    // Keyed to paneId so the dialog never stays open across navigation.
    var showSplitOrForkDialog by remember(paneId) { mutableStateOf(false) }
    var forking by remember(paneId) { mutableStateOf(false) }
    var killing by remember { mutableStateOf(false) }
    var attaching by remember(paneId) { mutableStateOf(false) }
    var showEffortMenu by remember { mutableStateOf(false) }
    var showModeMenu by remember { mutableStateOf(false) }
    var pendingEffort by remember(paneId) { mutableStateOf<String?>(null) }
    var splitting by remember { mutableStateOf(false) }
    var switching by remember { mutableStateOf(false) }
    // Sibling panes in the same tmux window, sorted by pane index — drives
    // the swipe-between-panes navigation and the "Pane N of M" header.
    var siblingPaneIds by remember { mutableStateOf<List<String>>(emptyList()) }
    val swipeOffsetX = remember { Animatable(0f) }
    // Image attachments queued for the next send. Each Uri is resolved
    // through contentResolver on send; the list survives across recompositions.
    val pendingImages = remember { mutableStateListOf<android.net.Uri>() }

    val voice = remember { VoiceInputController(context) }
    val clipboard = LocalClipboardManager.current

    // Tell ApprovalService the user is now looking at this pane so it can
    // dismiss any pending approval/attention notifications for it. Re-fires
    // when paneId changes (e.g. swipe between siblings).
    LaunchedEffect(paneId) {
        ViewedPaneBus.post(paneId)
    }

    fun startVoiceCapture() {
        voiceListening = true
        scope.launch {
            collectVoiceInput(
                voice = voice,
                onPartial = { voiceTranscript = it },
                onFinal = { text ->
                    voiceTranscript = text
                    inputText = text
                    voiceListening = false
                },
                onError = { message ->
                    error = "Voice error: $message"
                    voiceListening = false
                },
                onDone = { voiceListening = false },
            )
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) startVoiceCapture()
        else error = "Microphone permission denied"
    }

    // Image picker — just appends to the pending attachments list. Actual
    // upload happens when the user hits Send, so they can add multiple
    // images and type a prompt before committing.
    val imagePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri: android.net.Uri? ->
        uri?.let { pendingImages.add(it) }
    }

    // Initial load + auto-reconnecting WS for this pane's updates. Same
    // shape as the grid screen — wrap in a retry loop with exponential
    // backoff so the screen doesn't stick on "connection abort" forever
    // when the companion exe restarts.
    //
    // PaneOutputChanged events fire on every status-bar tick from Claude.
    // We never *append* the tail — that produced visible duplicate copies
    // of Claude's status line. Instead we debounce-refetch the canonical
    // last 200 lines from the companion.
    LaunchedEffect(config, paneId) {
        val cfg = config ?: return@LaunchedEffect
        // Coalescing refetch channel: events arriving during an in-flight
        // fetch get conflated into a single follow-up fetch, not a cascade
        // of cancelled half-fetches. Previous `refetchJob.cancel()` pattern
        // killed the HTTP request mid-flight when events arrived faster
        // than fetches completed, starving the phone of updates.
        val refetchChannel =
            kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.CONFLATED)
        var backoff = 1_000L
        while (true) {
            val client = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            suspend fun refetchCapture() {
                runCatching { client.capture(paneId, lines = CAPTURE_LINES) }
                    .onSuccess { lines = it.lines }
                    .onFailure { error = it.message }
            }
            // Long-running consumer that drains the conflated channel and
            // fires fetches serially with a 200ms debounce. Lives inside
            // this scope so it's torn down with the outer LaunchedEffect.
            val refetchConsumer = scope.launch {
                for (@Suppress("UNUSED_VARIABLE") signal in refetchChannel) {
                    // No artificial delay — the CONFLATED channel already
                    // coalesces rapid-fire events and the server responds
                    // in ~50ms. Adding a debounce delay was the difference
                    // between "instant" (initial load, direct call) and
                    // "noticeably laggy" (subsequent event-driven refetches).
                    refetchCapture()
                }
            }
            try {
                refetchCapture()
                approvals = client.listApprovals().filter { it.paneId == paneId }
                val allPanes = runCatching { client.listPanes() }.getOrDefault(emptyList())
                paneInfo = allPanes.firstOrNull { it.id == paneId }
                paneInfo?.let { info ->
                    siblingPaneIds = allPanes
                        .filter { it.sessionName == info.sessionName && it.windowIndex == info.windowIndex }
                        .sortedBy { it.paneIndex }
                        .map { it.id }
                }
                error = null
                backoff = 1_000L
                client.events().collect { ev ->
                    when (ev) {
                        is EventDto.Snapshot -> {
                            approvals = ev.approvals.filter { it.paneId == paneId }
                            paneInfo = ev.panes.firstOrNull { it.id == paneId } ?: paneInfo
                            paneInfo?.let { info ->
                                siblingPaneIds = ev.panes
                                    .filter { it.sessionName == info.sessionName && it.windowIndex == info.windowIndex }
                                    .sortedBy { it.paneIndex }
                                    .map { it.id }
                            }
                        }
                        is EventDto.PaneOutputChanged -> {
                            if (ev.paneId == paneId) {
                                // Signal the conflated channel; the consumer
                                // coalesces bursts into single fetches and
                                // never cancels in-flight HTTP calls.
                                refetchChannel.trySend(Unit)
                            }
                        }
                        is EventDto.ApprovalCreated -> {
                            if (ev.approval.paneId == paneId) {
                                approvals = approvals + ev.approval
                                if (currentApproval == null) currentApproval = ev.approval
                            }
                        }
                        is EventDto.ApprovalResolved -> {
                            approvals = approvals.filterNot { it.id == ev.id }
                            if (currentApproval?.id == ev.id) currentApproval = null
                        }
                        else -> Unit
                    }
                }
            } catch (t: kotlinx.coroutines.CancellationException) {
                refetchConsumer.cancel()
                runCatching { client.close() }
                throw t
            } catch (t: Throwable) {
                error = t.message ?: t::class.simpleName
            } finally {
                refetchConsumer.cancel()
                runCatching { client.close() }
            }
            kotlinx.coroutines.delay(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    // Hoist the output cleanup so the TopAppBar can read the extracted
    // context percentage and the LazyColumn body can read the visible lines.
    // LineCache survives across recompositions so unchanged lines (the vast
    // majority on each refetch) skip all ANSI/regex parsing.
    val onSurface = MaterialTheme.colorScheme.onSurface
    val lineCache = remember { LineCache() }
    // Transcript (full JSONL history) sits above the live capture overlay
    // with a dim separator. The capture reflects what Claude is drawing
    // tmux scrollback is the sole content source — no JSONL reconstruction.
    val cleaned = remember(lines, onSurface) {
        cleanOutputLines(lines, onSurface, lineCache)
    }
    val displayLines = cleaned.lines
    val contextPct = cleaned.contextPct
    val claudeMode = remember(lines) { detectClaudeMode(lines) }
    val detectedEffort = remember(lines) { detectEffort(lines) }
    var lastKnownEffort by remember(paneId) { mutableStateOf<String?>(null) }
    LaunchedEffect(detectedEffort) {
        if (detectedEffort != null) {
            lastKnownEffort = detectedEffort
            if (detectedEffort == pendingEffort) pendingEffort = null
        }
    }
    // Server-side claude_effort is the authoritative sticky cache (the
    // companion poller sees the banner before it scrolls out of its
    // 45-line window and caches it for the lifetime of the Claude
    // session). Fall back to client-side detection for older desktop
    // builds that don't ship the field, or while the first server
    // snapshot is still in flight.
    val serverEffort = paneInfo?.claudeEffort
    LaunchedEffect(serverEffort) {
        if (serverEffort != null && serverEffort == pendingEffort) {
            pendingEffort = null
        }
    }
    val currentEffort = pendingEffort ?: serverEffort ?: lastKnownEffort

    // Auto-scroll to bottom whenever the *visible* line count changes,
    // but only when the user hasn't scrolled up to read scrollback —
    // otherwise each poll yanks them back to the bottom and scrolling
    // is impossible in practice. `stickToBottom` flips off when the
    // user releases a scroll away from the tail and back on when they
    // return near the tail.
    // Fresh listState per pane so navigation doesn't inherit a stale
    // scroll offset from a previous (often longer) transcript.
    val listState = remember(paneId) { androidx.compose.foundation.lazy.LazyListState() }
    // stickToBottom is a boolean latch, not a pure derivation. It starts
    // true (auto-scroll on initial load), only flips to false while the
    // user is actively dragging away from the tail, and flips back to
    // true when they drag near the tail again. The earlier derivedStateOf
    // version flipped false the moment layout revealed only a top slice
    // of a long transcript, killing the initial auto-scroll.
    var stickToBottom by remember(paneId) { mutableStateOf(true) }
    LaunchedEffect(listState, paneId) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset,
            )
        }.collect { (scrolling, _, _) ->
            if (scrolling) {
                val info = listState.layoutInfo
                val total = info.totalItemsCount
                val last = info.visibleItemsInfo.lastOrNull()
                stickToBottom = total == 0 || (last != null && last.index >= total - 2)
            }
        }
    }
    LaunchedEffect(displayLines.size, paneId) {
        if (displayLines.isNotEmpty() && stickToBottom) {
            delay(50)
            listState.scrollToItem(displayLines.lastIndex)
        }
    }

    // Auto-scroll to bottom whenever the keyboard opens or closes — but only
    // if the user was already tailing. IME-driven layout shouldn't yank a
    // user who has scrolled up to read scrollback.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (displayLines.isNotEmpty() && stickToBottom) {
            delay(120) // wait for the IME-driven layout pass to settle
            listState.scrollToItem(displayLines.lastIndex)
        }
    }

    // If there's any pending approval for this pane, surface the first one.
    LaunchedEffect(approvals) {
        if (currentApproval == null && approvals.isNotEmpty()) {
            currentApproval = approvals.first()
        }
    }

    // Walk Claude's shift-tab cycle one step at a time until the detected
    // mode matches the target. We don't hardcode the cycle order because it
    // has shifted across Claude Code versions (a previous dropdown that
    // assumed {Normal, AutoAccept, Plan, Bypass} landed on the wrong mode).
    // Bail if detection doesn't move within the timeout — either we already
    // hit target or the pane output isn't flowing; either way, hammering
    // more S-Tabs won't help.
    fun setMode(target: ClaudeMode) {
        scope.launch {
            val cfg = config ?: return@launch
            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            try {
                repeat(ClaudeMode.entries.size) {
                    val before = detectClaudeMode(lines)
                    if (before == target) return@launch
                    c.sendKey(paneId, "S-Tab")
                    val settled = withTimeoutOrNull(1500) {
                        snapshotFlow { detectClaudeMode(lines) }.first { it != before }
                    }
                    if (settled == null) return@launch
                }
            } catch (t: Throwable) {
                error = t.message
            } finally {
                c.close()
            }
        }
    }

    fun sendKey(key: String) {
        scope.launch {
            val cfg = config ?: return@launch
            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            runCatching { c.sendKey(paneId, key) }
                .onFailure { error = it.message }
            c.close()
        }
    }

    fun setEffort(level: String) {
        scope.launch {
            val cfg = config ?: return@launch
            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            runCatching { c.sendInput(paneId, "/effort $level") }
                .onFailure { error = it.message }
            c.close()
        }
    }

    fun splitPane() {
        val info = paneInfo ?: return
        val account = info.claudeAccount ?: "andrea"
        splitting = true
        scope.launch {
            val cfg = config ?: run { splitting = false; return@launch }
            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            runCatching { c.createPane(paneId, account) }
                .onSuccess { resp ->
                    splitting = false
                    onNavigateToPane(resp.paneId)
                }
                .onFailure {
                    error = "Split failed: ${it.message ?: it::class.simpleName}"
                    splitting = false
                }
            c.close()
        }
    }

    /**
     * Fork: drive Claude's `/branch` slash command on this pane (it mints
     * a new session UUID internally, no process restart), then split a
     * sibling pane that resumes the *original* session. Companion does
     * the orchestration server-side. See
     * `workspace-resume/src-tauri/src/companion/http.rs::fork_pane`.
     */
    fun forkPane() {
        val info = paneInfo ?: return
        val account = info.claudeAccount ?: "andrea"
        forking = true
        scope.launch {
            val cfg = config ?: run { forking = false; return@launch }
            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            runCatching { c.forkPane(paneId, account) }
                .onSuccess { resp ->
                    forking = false
                    onNavigateToPane(resp.paneId)
                }
                .onFailure {
                    error = "Fork failed: ${it.message ?: it::class.simpleName}"
                    forking = false
                }
            c.close()
        }
    }

    /** Stop Claude and restart under the next account in the cycle.
     *  andrea → bravura → sully → andrea. Each step uses that account's
     *  launcher alias (ncld / ncld2 / ncld3) so the in-pane Claude
     *  picks up the right CLAUDE_CONFIG_DIR. Appends --resume <uuid>
     *  when a session id is known so the conversation carries over. */
    fun switchAccount() {
        val info = paneInfo ?: return
        val currentAcct = info.claudeAccount ?: "andrea"
        val (targetCmd, _) = nextAccountAfter(currentAcct)
        val resumeFlag = info.claudeSessionId?.let { " --resume $it" } ?: ""
        switching = true
        scope.launch {
            val cfg = config ?: run { switching = false; return@launch }
            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            runCatching { c.sendInput(paneId, "/exit") }
                .onFailure {
                    error = "Switch failed: ${it.message}"
                    switching = false
                    c.close()
                    return@launch
                }
            // Wait for Claude to exit and the shell prompt to appear
            delay(2000)
            runCatching { c.sendInput(paneId, "$targetCmd$resumeFlag") }
                .onFailure { error = "Switch failed: ${it.message}" }
            switching = false
            c.close()
        }
    }

    // Swipe navigation: pick the prev/next sibling pane, if any
    val currentIdx = siblingPaneIds.indexOf(paneId)
    val prevPaneId = if (currentIdx > 0) siblingPaneIds[currentIdx - 1] else null
    val nextPaneId = if (currentIdx in 0 until siblingPaneIds.lastIndex)
        siblingPaneIds[currentIdx + 1]
    else null

    Scaffold(
        contentWindowInsets = WindowInsets.systemBars.union(WindowInsets.ime),
        topBar = {
            TopAppBar(
                title = {
                    val info = paneInfo
                    val titleText = info?.projectDisplayName
                        ?: paneProjectLabel(info?.projectEncodedName)
                        ?: info?.currentPath
                            ?.trimEnd('/')
                            ?.substringAfterLast('/')
                            ?.takeIf { it.isNotBlank() }
                        ?: paneId
                    val subtitleText = buildString {
                        append(info?.let { "${it.sessionName}:${it.windowIndex}.${it.paneIndex}" } ?: paneId)
                        info?.currentPath?.takeIf { it.isNotBlank() }?.let {
                            append("  ·  ")
                            append(it)
                        }
                    }
                    Column {
                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                        )
                        Text(
                            text = subtitleText,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    contextPct?.let { ContextChip(pct = it) }
                    ModeChip(
                        mode = claudeMode,
                        expanded = showModeMenu,
                        onToggle = { showModeMenu = !showModeMenu },
                        onSelect = { target ->
                            showModeMenu = false
                            setMode(target)
                        },
                    )
                    EffortChip(
                        current = currentEffort,
                        expanded = showEffortMenu,
                        onToggle = { showEffortMenu = !showEffortMenu },
                        onSelect = { level ->
                            showEffortMenu = false
                            pendingEffort = level
                            setEffort(level)
                        },
                    )
                    // Account switch: stop + restart cycling through the
                    // known accounts (andrea → bravura → sully → andrea),
                    // carrying --resume when we know the session id.
                    if (paneInfo?.claudeAccount != null) {
                        val acct = paneInfo?.claudeAccount ?: "andrea"
                        val (_, nextAcctKey) = nextAccountAfter(acct)
                        val targetLabel = when (nextAcctKey) {
                            "andrea" -> "A"
                            "bravura" -> "B"
                            "sully" -> "S"
                            else -> "?"
                        }
                        val acctColor = when (nextAcctKey) {
                            "andrea" -> Color(0xFF60A5FA)  // blue
                            "bravura" -> Color(0xFFA78BFA) // purple
                            "sully" -> Color(0xFF14B8A6)   // teal
                            else -> Color(0xFF888888)
                        }
                        val nextLabel = when (nextAcctKey) {
                            "andrea" -> "Andrea"
                            "bravura" -> "Bravura"
                            "sully" -> "Sully"
                            else -> nextAcctKey
                        }
                        IconButton(
                            onClick = { switchAccount() },
                            enabled = !switching,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.SwapHoriz,
                                    contentDescription = "Switch to $nextLabel",
                                    tint = acctColor,
                                    modifier = Modifier.size(20.dp),
                                )
                                Text(
                                    text = targetLabel,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = acctColor,
                                    fontSize = 8.sp,
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(bottom = 2.dp),
                                )
                            }
                        }
                    }
                    // Remote-pane only: ask the desktop to open (or
                    // re-select) a local WSL tmux window that
                    // SSH-attaches to this pane's session, so the user
                    // sees the same terminal in WezTerm next time
                    // they're on the desktop. Mirrors the desktop's
                    // "Attach here" PaneSlot menu item. Idempotent:
                    // backing endpoint re-uses an existing mirror.
                    val remoteHost = paneInfo?.host
                    if (!remoteHost.isNullOrEmpty() && remoteHost != "local") {
                        IconButton(
                            onClick = {
                                val info = paneInfo ?: return@IconButton
                                val cfg = config ?: return@IconButton
                                attaching = true
                                scope.launch {
                                    val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                                    val result = runCatching {
                                        c.attachRemoteSession(
                                            AttachRemoteSessionRequest(
                                                alias = remoteHost,
                                                sessionName = info.sessionName,
                                            )
                                        )
                                    }
                                    c.close()
                                    attaching = false
                                    result.onSuccess { resp ->
                                        android.widget.Toast.makeText(
                                            context,
                                            "Mirror ready: ${resp.localWindowName}",
                                            android.widget.Toast.LENGTH_SHORT,
                                        ).show()
                                    }.onFailure { t ->
                                        android.widget.Toast.makeText(
                                            context,
                                            "Attach failed: ${t.message ?: "unknown"}",
                                            android.widget.Toast.LENGTH_LONG,
                                        ).show()
                                    }
                                }
                            },
                            enabled = paneInfo != null && !attaching,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Default.OpenInNew,
                                contentDescription = "Attach mirror window on desktop",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    IconButton(
                        onClick = { showSplitOrForkDialog = true },
                        enabled = paneInfo != null && !splitting && !forking,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = "Split pane",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                    IconButton(
                        onClick = { showKillDialog = true },
                        enabled = paneInfo != null && !killing,
                        modifier = Modifier.size(36.dp),
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "Kill pane",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .offset { IntOffset(swipeOffsetX.value.roundToInt(), 0) }
                .pointerInput(siblingPaneIds, paneId) {
                    val threshold = 80.dp.toPx()
                    val screenW = size.width.toFloat()
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                when {
                                    swipeOffsetX.value > threshold && prevPaneId != null -> {
                                        swipeOffsetX.animateTo(screenW, tween(180))
                                        onNavigateToPane(prevPaneId)
                                    }
                                    swipeOffsetX.value < -threshold && nextPaneId != null -> {
                                        swipeOffsetX.animateTo(-screenW, tween(180))
                                        onNavigateToPane(nextPaneId)
                                    }
                                    else -> swipeOffsetX.animateTo(0f, spring())
                                }
                            }
                        },
                        onDragCancel = {
                            scope.launch { swipeOffsetX.animateTo(0f, spring()) }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            // Dampen drag to 20% when no sibling in that direction
                            val dampened = when {
                                dragAmount > 0 && prevPaneId == null -> dragAmount * 0.2f
                                dragAmount < 0 && nextPaneId == null -> dragAmount * 0.2f
                                else -> dragAmount
                            }
                            scope.launch { swipeOffsetX.snapTo(swipeOffsetX.value + dampened) }
                        },
                    )
                },
        ) {
            // Pane position indicator — only shown when the window has
            // more than one pane, so users know the swipe gesture applies.
            if (siblingPaneIds.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = "Pane ${currentIdx + 1} of ${siblingPaneIds.size}  ‹ swipe ›",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
            }

            // Warning strip — surfaces companion-detected abnormalities on
            // this pane, most commonly a session_id collision with a sibling
            // pane. The companion guard in tmux_poller.rs refuses to bind a
            // session_id already owned elsewhere and stamps the warning on
            // the losing pane's DTO. Clears automatically when detection
            // succeeds cleanly on a later tick.
            paneInfo?.warning?.let { msg ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0x33FFA726))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "⚠  $msg",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color(0xFFFFB74D),
                    )
                }
            }

            // Output view + floating quick-key column on the right edge.
            // Keys used to live in a full-width row below the output; moving
            // them inside the output Box as an overlay reclaims vertical
            // space for the chat while keeping arrow/Ent/Esc/Tab one tap away.
            Box(Modifier.weight(1f)) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        // Text extends edge-to-edge; the FloatingQuickKeys
                        // column draws on top of the right-edge strip. The
                        // buttons are intentionally translucent so the
                        // terminal output behind them stays readable.
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = 12.dp,
                            top = 12.dp,
                            end = 12.dp,
                            bottom = 12.dp,
                        ),
                    ) {
                        items(
                            count = displayLines.size,
                            // Stable key: index + content hash. Compose can
                            // skip re-measuring items whose key hasn't changed,
                            // which is most of the list on each refetch.
                            key = { idx -> displayLines[idx].text.hashCode().toLong() * 31 + idx },
                        ) { idx ->
                            val line = displayLines[idx]
                            if (line.text.isBlank()) {
                                androidx.compose.material3.HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant,
                                )
                            } else {
                                Text(
                                    text = line,
                                    fontFamily = FontFamily.Monospace,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        lineHeight = 18.sp,
                                    ),
                                    softWrap = true,
                                )
                            }
                        }
                    }
                }
                FloatingQuickKeys(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 6.dp),
                    onKey = { key -> sendKey(key) },
                )
            }
            error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            // Voice transcript preview
            if (voiceListening || voiceTranscript.isNotEmpty()) {
                Surface(
                    tonalElevation = 4.dp,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = null,
                            tint = if (voiceListening) Color(0xFFE53935) else MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = voiceTranscript.ifBlank { "Listening…" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            // Attachment chips — one per pending image, horizontally
            // scrollable with a close button to remove each. Visible only
            // when at least one image is queued.
            if (pendingImages.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    pendingImages.forEachIndexed { idx, uri ->
                        val label = remember(uri) { filenameFor(context, uri) ?: "Image ${idx + 1}" }
                        AttachmentChip(
                            label = label,
                            onRemove = { pendingImages.remove(uri) },
                        )
                    }
                }
            }

            // Chat-style input bar. The TextField owns its own rounded
            // shape and surface color (no wrapping Surface) so the long-
            // press selection gesture goes straight to the field — wrapping
            // it in a Surface was eating the gesture and breaking paste.
            // Mic and send live in a Row alongside the field, not inside it.
            //
            // KeyboardOptions disables autocorrect / capitalization and
            // suppresses the OnePlus "Writing Tools" overlay that hijacks
            // long-press on AI-augmentable input fields.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = {
                        Text(
                            "Type or dictate…",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    textStyle = MaterialTheme.typography.bodyLarge,
                    shape = RoundedCornerShape(24.dp),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent,
                        errorIndicatorColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary,
                    ),
                    keyboardOptions = KeyboardOptions(
                        autoCorrectEnabled = false,
                        capitalization = KeyboardCapitalization.None,
                        keyboardType = KeyboardType.Text,
                    ),
                    maxLines = 5,
                    modifier = Modifier.weight(1f),
                )
                // Explicit paste button — long-press paste is unreliable on
                // OnePlus (the Writing Tools overlay swallows the gesture)
                // so we expose a tap-to-paste icon whenever the system
                // clipboard contains text. Re-check on each user-input
                // change (not on every recomposition) to avoid blocking
                // the main thread with a system call during output updates.
                val clipText = remember(inputText) { clipboard.getText()?.text }
                if (!clipText.isNullOrEmpty()) {
                    IconButton(
                        onClick = { inputText = inputText + clipText },
                        modifier = Modifier.size(48.dp),
                    ) {
                        Icon(
                            Icons.Default.ContentPaste,
                            contentDescription = "Paste from clipboard",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(
                    onClick = { imagePickerLauncher.launch("image/*") },
                    enabled = !sending,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Attach image",
                        tint = if (pendingImages.isNotEmpty()) MaterialTheme.colorScheme.primary
                        else if (!sending) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
                IconButton(
                    onClick = {
                        if (!voice.isAvailable()) {
                            error = "SpeechRecognizer unavailable on this device"
                            return@IconButton
                        }
                        if (!voice.hasMicPermission()) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            startVoiceCapture()
                        }
                    },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Mic,
                        contentDescription = "Voice input",
                        tint = if (voiceListening) StatusColors.Waiting
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val canSend = !sending && (inputText.isNotBlank() || pendingImages.isNotEmpty())
                IconButton(
                    onClick = {
                        val text = inputText.trim()
                        val attachments = pendingImages.toList()
                        if (text.isBlank() && attachments.isEmpty()) return@IconButton
                        sending = true
                        scope.launch {
                            val cfg = config ?: run { sending = false; return@launch }
                            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                            runCatching {
                                if (attachments.isNotEmpty()) {
                                    val items = attachments.map { uri ->
                                        val bytes = context.contentResolver
                                            .openInputStream(uri)?.use { it.readBytes() }
                                            ?: throw Exception("Failed to read $uri")
                                        ImageItemDto(
                                            imageBase64 = Base64.encodeToString(bytes, Base64.NO_WRAP),
                                            mediaType = context.contentResolver.getType(uri) ?: "image/png",
                                        )
                                    }
                                    c.sendImage(paneId, items, prompt = text.takeIf { it.isNotEmpty() })
                                } else {
                                    c.sendInput(paneId, text, submit = true)
                                }
                                inputText = ""
                                voiceTranscript = ""
                                pendingImages.clear()
                            }.onFailure { error = it.message }
                            c.close()
                            sending = false
                        }
                    },
                    enabled = canSend,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Send,
                        contentDescription = "Send",
                        tint = if (canSend) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                }
            }
        }
    }

    // Approval dialog overlay
    currentApproval?.let { approval ->
        ApprovalDialog(
            approval = approval,
            onAllow = {
                scope.launch {
                    val cfg = config ?: return@launch
                    val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                    runCatching { c.resolveApproval(approval.id, Decision.Allow) }
                        .onFailure { error = it.message }
                    c.close()
                    currentApproval = null
                }
            },
            onDeny = {
                scope.launch {
                    val cfg = config ?: return@launch
                    val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                    runCatching { c.resolveApproval(approval.id, Decision.Deny) }
                        .onFailure { error = it.message }
                    c.close()
                    currentApproval = null
                }
            },
            onDismiss = { currentApproval = null },
        )
    }

    // Kill pane confirmation dialog
    if (showKillDialog) {
        AlertDialog(
            onDismissRequest = { if (!killing) showKillDialog = false },
            title = { Text("Kill pane?") },
            text = {
                Text(
                    "This will terminate pane $paneId. Other panes in the same window will stay alive. The action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            killing = true
                            val cfg = config ?: run { killing = false; return@launch }
                            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                            val result = runCatching { c.killPane(paneId) }
                            c.close()
                            killing = false
                            showKillDialog = false
                            result.onSuccess { onBack() }
                                .onFailure { error = "Kill failed: ${it.message ?: it::class.simpleName}" }
                        }
                    },
                    enabled = !killing,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
                ) {
                    Text(if (killing) "Killing…" else "Kill")
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showKillDialog = false },
                    enabled = !killing,
                ) { Text("Cancel") }
            },
        )
    }

    // Split-or-fork chooser dialog — shown when the user taps + in the
    // top toolbar. Two primary actions stacked in `confirmButton`; Fork
    // disabled when no session_id has been detected yet (companion poller
    // hasn't bound it, or pane isn't running Claude).
    if (showSplitOrForkDialog) {
        AlertDialog(
            onDismissRequest = { if (!splitting && !forking) showSplitOrForkDialog = false },
            title = { Text("Open new pane") },
            text = {
                Text(
                    "Split this window — choose what the new pane should contain.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Button(
                        onClick = {
                            showSplitOrForkDialog = false
                            splitPane()
                        },
                        enabled = !splitting && !forking,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (splitting) "Opening…" else "New session") }
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            showSplitOrForkDialog = false
                            forkPane()
                        },
                        enabled = paneInfo?.claudeSessionId != null && !splitting && !forking,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (forking) "Forking…" else "Fork conversation") }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showSplitOrForkDialog = false },
                    enabled = !splitting && !forking,
                ) { Text("Cancel") }
            },
        )
    }
}

/**
 * Claude Code's runtime mode. Cycled via Shift-Tab on the host. Detected
 * by scanning the bottom of Claude's captured terminal output for the
 * mode-indicator strings Claude prints just above its input prompt:
 *   "bypass permissions on (shift+tab to cycle)"
 *   "auto-accept edits on (shift+tab to cycle)"
 *   "plan mode on (shift+tab to cycle)"
 *   "auto mode on (shift+tab to cycle)"
 * If none of those appear, the pane is in Normal mode.
 */
internal enum class ClaudeMode(val label: String) {
    Normal("Normal"),
    AutoAccept("Auto-Accept"),
    Plan("Plan"),
    Bypass("Bypass"),
    Auto("Auto"),
}

internal fun detectClaudeMode(lines: List<String>): ClaudeMode {
    // Scan only the bottom of the buffer — mode indicators are always
    // near the prompt, never in scrollback content.
    val tail = lines.asReversed().take(20).joinToString("\n").lowercase()
    // Test "auto-accept edits on" before "auto mode on" so the more-specific
    // banner wins even if Claude ever reformats either line.
    return when {
        "bypass permissions on" in tail -> ClaudeMode.Bypass
        "auto-accept edits on" in tail -> ClaudeMode.AutoAccept
        "plan mode on" in tail -> ClaudeMode.Plan
        "auto mode on" in tail -> ClaudeMode.Auto
        else -> ClaudeMode.Normal
    }
}

/**
 * Client-side fallback effort detector, used when the companion's
 * `claude_effort` DTO field is null (older desktop build, or the first
 * server snapshot hasn't arrived yet). Scans the last 60 lines of the
 * captured output for two signals:
 * - Claude Code 2.1+ session banner: `"with max effort · Claude Max"` —
 *   the level precedes the word "effort".
 * - Echoed `/effort <level>` — what the chip setter sends, and what
 *   the user types manually.
 *
 * Returns null when neither signal is found; the chip renders "—"
 * instead of guessing. We deliberately do NOT match the looser
 * `"effort <level>"` pattern because Claude's `/` menu help text
 * contains phrases like `"effort: low | medium | high | max"` that
 * would false-positive on the first listed level.
 */
internal fun detectEffort(lines: List<String>): String? {
    val tail = lines.asReversed().take(60).joinToString("\n").lowercase()
    val banner = Regex("""\b(low|medium|high|max)\s+effort\b""").findAll(tail)
        .lastOrNull()?.groupValues?.get(1)
    if (banner != null) return banner
    val slash = Regex("""/effort\s+(low|medium|high|max)\b""").findAll(tail)
        .lastOrNull()?.groupValues?.get(1)
    return slash
}

/**
 * Strip pure-visual lines (Unicode box drawing, shading, full-block) from
 * Claude's captured terminal output and collapse runs of empty / visual-only
 * lines into a single blank entry. Also drops lines that contain a long
 * run of horizontal box-drawing characters anywhere in the line — that
 * catches Claude's `─────── project-name ──` cwd-separator headers,
 * which carry no useful information on a phone screen even though they
 * do contain text and so wouldn't be caught by the pure-visual check.
 *
 * Additionally extracts the Barkeep statusline (`████░░ 64%`) into the
 * returned `contextPct` and drops the line from the visible output —
 * the percentage is shown in the TopAppBar instead so it doesn't waste
 * a row at the bottom of the chat.
 */
private val LONG_DASH_RUN = Regex("[\u2500\u2501\u2550\u2014_\\-]{10,}")
// Barkeep Light output: optional "A"/"B" account prefix, then block chars, then N%.
// e.g. "A ▓▓▓▓░░░░░░ 42%" or plain "▓▓▓▓░░░░░░ 42%".
private val BARKEEP_LINE = Regex("^\\s*(?:[A-Z]\\s+)?[\u2580-\u259F]{2,}\\s+(\\d{1,3})\\s*%\\s*$")

internal data class CleanedOutput(
    val lines: List<AnnotatedString>,
    val contextPct: Int?,
)

private fun cleanOutputLines(raw: List<String>, defaultColor: Color, cache: LineCache): CleanedOutput {
    var contextPct: Int? = null
    val out = ArrayList<String>(raw.size)
    var lastWasBlank = false
    for (line in raw) {
        // Strip ANSI for classification — the raw line (with ANSI) goes into
        // `out` so parseAnsiLine can render colours later.
        val stripped = stripAnsi(line).trim()

        // Pull the Barkeep context line out before any other handling and
        // record its percentage for the toolbar.
        val barkeep = BARKEEP_LINE.matchEntire(stripped)
        if (barkeep != null) {
            contextPct = barkeep.groupValues[1].toIntOrNull()
            if (!lastWasBlank && out.isNotEmpty()) out.add("")
            lastWasBlank = true
            continue
        }

        val visualOnly = stripped.isNotEmpty() && stripped.all { c ->
            c.isWhitespace() ||
                c in '\u2500'..'\u257F' || // box drawing
                c in '\u2580'..'\u259F' || // block elements (incl. shading)
                c == '\u00A0'              // non-breaking space (Claude uses these)
        }
        val hasLongDashRun = LONG_DASH_RUN.containsMatchIn(stripped)
        val blank = stripped.isEmpty() || visualOnly || hasLongDashRun
        if (blank) {
            if (!lastWasBlank && out.isNotEmpty()) out.add("")
            lastWasBlank = true
        } else {
            out.add(line)
            lastWasBlank = false
        }
    }
    while (out.lastOrNull() == "") out.removeAt(out.size - 1)

    // Parse ANSI escape sequences into styled AnnotatedStrings using the
    // incremental cache — unchanged lines (the vast majority on each
    // refetch) hit the cache and skip all ANSI/regex work.
    val parsed = out.map { line -> cache.getOrParse(line, defaultColor) }
    return CleanedOutput(parsed, contextPct)
}

@OptIn(ExperimentalSerializationApi::class)
private val prettyJsonFmt = Json { prettyPrint = true; prettyPrintIndent = "  " }

private fun prettyJson(el: JsonElement): String =
    runCatching { prettyJsonFmt.encodeToString(JsonElement.serializer(), el) }
        .getOrDefault(el.toString())

private fun paneProjectLabel(encoded: String?): String? {
    if (encoded.isNullOrBlank()) return null
    val decoded = encoded.replace("-", "/").trimEnd('/')
    val last = decoded.substringAfterLast('/')
    return last.ifBlank { encoded }
}

/**
 * Given a current account identity (`"andrea"` / `"bravura"` / `"sully"`),
 * return `(targetLauncherCmd, nextAcctKey)` for the next step of the
 * andrea → bravura → sully cycle. The launcher cmd matches the WSL
 * shell functions in `~/.bashrc` (`ncld`, `ncld2`, `ncld3`) so the new
 * Claude run picks up the right `CLAUDE_CONFIG_DIR`.
 */
private fun nextAccountAfter(current: String): Pair<String, String> = when (current) {
    "andrea" -> Pair("ncld2", "bravura")
    "bravura" -> Pair("ncld3", "sully")
    "sully" -> Pair("ncld", "andrea")
    else -> Pair("ncld2", "bravura")
}

/**
 * Tappable mode chip — shows the pane's current Claude mode and cycles
 * to the next mode (via Shift-Tab on the host pane) when clicked. The
 * mode label re-derives from the captured output on the next refetch,
 * so the chip updates within a few seconds of the cycle landing.
 */
@Composable
private fun QuickKeyButton(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = modifier.size(44.dp),
        // Translucent fill so the terminal output flowing underneath the
        // button column stays readable — the chat is now full-width, so
        // the right-edge strip is covered by these buttons at all times.
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                .copy(alpha = 0.55f),
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        icon()
    }
}

/**
 * Vertical stack of quick-keys overlaid on the right edge of the chat
 * output. Rewires arrow navigation (Up/Down/Left/Right) plus the three
 * submit-shape keys Claude's pickers need (Ent, Esc, Tab). Translucent
 * background so text behind stays readable.
 */
@Composable
private fun FloatingQuickKeys(
    modifier: Modifier = Modifier,
    onKey: (String) -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        QuickKeyButton(icon = { Icon(Icons.Default.KeyboardArrowUp, "Up") }) { onKey("Up") }
        QuickKeyButton(icon = { Icon(Icons.Default.KeyboardArrowDown, "Down") }) { onKey("Down") }
        QuickKeyButton(icon = { Icon(Icons.Default.KeyboardArrowLeft, "Left") }) { onKey("Left") }
        QuickKeyButton(icon = { Icon(Icons.Default.KeyboardArrowRight, "Right") }) { onKey("Right") }
        QuickKeyButton(icon = { Text("Ent", style = MaterialTheme.typography.labelSmall) }) { onKey("Enter") }
        QuickKeyButton(icon = { Text("Esc", style = MaterialTheme.typography.labelSmall) }) { onKey("Escape") }
        QuickKeyButton(icon = { Text("Tab", style = MaterialTheme.typography.labelSmall) }) { onKey("Tab") }
    }
}

/**
 * Mode picker — pill showing the currently-detected mode with a dropdown
 * listing all four. The current mode is marked with `• `. Selecting an
 * item invokes `onSelect`, which the caller wires to a closed-loop setter
 * that walks Claude's shift-tab cycle until the target is reached.
 */
@Composable
private fun ModeChip(
    mode: ClaudeMode,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (ClaudeMode) -> Unit,
) {
    val color = when (mode) {
        ClaudeMode.Normal     -> StatusColors.Idle
        ClaudeMode.AutoAccept -> StatusColors.Running
        ClaudeMode.Plan       -> StatusColors.Done
        ClaudeMode.Bypass     -> Color(0xFFFF6B6B)
        ClaudeMode.Auto       -> Color(0xFFA78BFA) // violet — distinct from auto-accept green
    }
    Box(modifier = Modifier.padding(end = 2.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(color.copy(alpha = 0.14f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onToggle,
        ) {
            ClaudeMode.entries.forEach { option ->
                val marker = if (option == mode) "• " else "  "
                DropdownMenuItem(
                    text = { Text("$marker${option.label}") },
                    onClick = { onSelect(option) },
                )
            }
        }
    }
}

/**
 * Claude `/effort` level picker — pill-shaped chip that both displays
 * the current level and opens a dropdown of low / medium / high / max.
 * Current level comes from (in priority order): the user's just-tapped
 * pending pick, the companion's server-side `claude_effort` field, or
 * the client's own tail scan (`detectEffort`). Renders "—" only when
 * all three are unknown. Tapping a level sends `/effort <level>` into
 * the pane.
 */
@Composable
private fun EffortChip(
    current: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val color = MaterialTheme.colorScheme.tertiary
    val label = when (current?.lowercase()) {
        "low" -> "Low"
        "medium" -> "Med"
        "high" -> "High"
        "max" -> "Max"
        null -> "—"
        else -> current.replaceFirstChar { it.uppercase() }
    }
    Box(modifier = Modifier.padding(end = 2.dp)) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(color.copy(alpha = 0.14f))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = onToggle,
        ) {
            listOf("low", "medium", "high", "max").forEach { level ->
                val marker = if (level == current?.lowercase()) "• " else "  "
                DropdownMenuItem(
                    text = { Text("$marker$level") },
                    onClick = { onSelect(level) },
                )
            }
        }
    }
}

/**
 * Compact context-window indicator for the TopAppBar actions slot.
 * Color shifts from running-green at low usage, to waiting-amber in the
 * middle, to danger-red as the window fills up.
 */
@Composable
private fun ContextChip(pct: Int) {
    val color = when {
        pct >= 80 -> Color(0xFFFF6B6B)
        pct >= 50 -> StatusColors.Waiting
        else -> StatusColors.Running
    }
    Row(
        modifier = Modifier
            .padding(end = 2.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$pct%",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun ApprovalDialog(
    approval: ApprovalDto,
    onAllow: () -> Unit,
    onDeny: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Tool-specific shaping: extract the most readable body from
    // `tool_input` so ExitPlanMode shows the full plan markdown, Bash
    // shows the command, Edit shows old/new diff, etc. Falls through to
    // pretty JSON for anything else so you always see *something*.
    val body = extractApprovalBody(approval)
    val isPlanApproval = approval.toolName == "ExitPlanMode"
    val confirmLabel = if (isPlanApproval) "Approve plan" else "Allow"
    val denyLabel = if (isPlanApproval) "Keep planning" else "Deny"

    // For ExitPlanMode, "Keep planning" is benign — Claude just stays
    // in planning mode. For everything else, Deny actually stops the
    // tool call, which is hard to undo, so we ask for a second tap.
    var confirmDeny by remember(approval.id) { mutableStateOf(false) }

    if (confirmDeny) {
        AlertDialog(
            onDismissRequest = { confirmDeny = false },
            title = { Text("Deny this request?") },
            text = {
                Text(
                    "Claude will be told the action was denied. The current tool call stops; Claude may pick a different approach or ask again.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = onDeny,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Yes, deny") }
            },
            dismissButton = {
                TextButton(onClick = { confirmDeny = false }) { Text("Cancel") }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (isPlanApproval) "Claude: plan ready for approval"
                else "Claude: ${approval.title}",
            )
        },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (!approval.toolName.isNullOrBlank() && !isPlanApproval) {
                    Text(
                        "Tool: ${approval.toolName}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (body.isNotBlank()) {
                    Surface(
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            body,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(10.dp),
                        )
                    }
                } else if (approval.message.isNotBlank()) {
                    Text(
                        approval.message,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                } else {
                    Text(
                        "(no details supplied by Claude Code)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onAllow) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (isPlanApproval) onDeny() else confirmDeny = true
                },
            ) { Text(denyLabel) }
        },
    )
}

/**
 * Build the most-readable body for an approval dialog based on the tool
 * that triggered it. ExitPlanMode returns the raw `plan` markdown;
 * Bash returns the command; Edit returns a before/after diff; others
 * fall through to pretty JSON of the tool_input.
 */
private fun extractApprovalBody(approval: ApprovalDto): String {
    val input = approval.toolInput as? kotlinx.serialization.json.JsonObject
    fun str(key: String): String? {
        val el = input?.get(key) ?: return null
        if (el is kotlinx.serialization.json.JsonNull) return null
        val prim = el as? kotlinx.serialization.json.JsonPrimitive ?: return null
        return prim.content
    }
    return when (approval.toolName) {
        "ExitPlanMode" -> str("plan") ?: ""
        "Bash" -> {
            val cmd = str("command") ?: return approval.message
            val desc = str("description")
            if (desc != null) "# $desc\n\n$ $cmd" else "$ $cmd"
        }
        "Edit", "MultiEdit" -> {
            val path = str("file_path") ?: ""
            val oldStr = str("old_string") ?: ""
            val newStr = str("new_string") ?: ""
            if (oldStr.isNotBlank() || newStr.isNotBlank())
                "$path\n\n--- before ---\n$oldStr\n\n+++ after +++\n$newStr"
            else path
        }
        "Write" -> {
            val path = str("file_path") ?: ""
            val content = str("content") ?: ""
            "$path\n\n$content"
        }
        else -> approval.toolInput?.let { prettyJson(it) } ?: approval.message
    }
}

// ---------------------------------------------------------------------------
// Image attachment chip
// ---------------------------------------------------------------------------

@Composable
private fun AttachmentChip(label: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Image,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        Spacer(Modifier.width(2.dp))
        IconButton(
            onClick = onRemove,
            modifier = Modifier.size(24.dp),
        ) {
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove attachment",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Query the ContentResolver for a Uri's display name. Returns null
 *  when the provider doesn't expose OpenableColumns.DISPLAY_NAME. */
private fun filenameFor(context: android.content.Context, uri: android.net.Uri): String? =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
        }
    }.getOrNull()
