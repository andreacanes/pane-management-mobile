package com.andreacanes.panemgmt.ui.detail

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.data.CompanionClient
import com.andreacanes.panemgmt.data.models.ApprovalDto
import com.andreacanes.panemgmt.data.models.EventDto
import com.andreacanes.panemgmt.data.models.PaneDto
import com.andreacanes.panemgmt.ui.common.collectVoiceInput
import com.andreacanes.panemgmt.ui.theme.StatusColors
import com.andreacanes.panemgmt.voice.VoiceInputController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

private const val CAPTURE_LINES = 1000

/**
 * Incremental line-cleaning cache. Keeps a map from raw line → parsed
 * AnnotatedString so that on a 1000-line refetch where only the last
 * few lines changed, we skip the expensive stripAnsi + regex + ANSI-parse
 * work for everything that's unchanged. The cache is keyed on the raw
 * String identity (which the server returns verbatim for unchanged lines),
 * capped loosely at 2× CAPTURE_LINES to avoid unbounded growth.
 */
private class LineCache {
    private val cache = LinkedHashMap<String, AnnotatedString>(512, 0.75f, true)

    fun getOrParse(raw: String, defaultColor: Color): AnnotatedString {
        cache[raw]?.let { return it }
        val parsed = if (raw.isBlank()) AnnotatedString("")
                     else parseAnsiLine(raw, defaultColor)
        cache[raw] = parsed
        if (cache.size > CAPTURE_LINES * 2) {
            val iter = cache.entries.iterator()
            repeat(cache.size - CAPTURE_LINES) { if (iter.hasNext()) { iter.next(); iter.remove() } }
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
) {
    val context = LocalContext.current
    val config by authStore.configFlow.collectAsState(initial = null)
    val scope = rememberCoroutineScope()

    var lines by remember { mutableStateOf<List<String>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var inputText by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var voiceListening by remember { mutableStateOf(false) }
    var voiceTranscript by remember { mutableStateOf("") }
    var approvals by remember { mutableStateOf<List<ApprovalDto>>(emptyList()) }
    var currentApproval by remember { mutableStateOf<ApprovalDto?>(null) }
    var paneInfo by remember { mutableStateOf<PaneDto?>(null) }
    var showKillDialog by remember { mutableStateOf(false) }
    var killing by remember { mutableStateOf(false) }

    val voice = remember { VoiceInputController(context) }
    val clipboard = LocalClipboardManager.current

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
        var refetchJob: kotlinx.coroutines.Job? = null
        var backoff = 1_000L
        while (true) {
            val client = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            suspend fun refetch() {
                runCatching { client.capture(paneId, lines = CAPTURE_LINES) }
                    .onSuccess { lines = it.lines }
                    .onFailure { error = it.message }
            }
            try {
                refetch()
                approvals = client.listApprovals().filter { it.paneId == paneId }
                paneInfo = runCatching { client.listPanes().firstOrNull { it.id == paneId } }.getOrNull()
                error = null
                backoff = 1_000L
                client.events().collect { ev ->
                    when (ev) {
                        is EventDto.Snapshot -> {
                            approvals = ev.approvals.filter { it.paneId == paneId }
                            paneInfo = ev.panes.firstOrNull { it.id == paneId } ?: paneInfo
                        }
                        is EventDto.PaneOutputChanged -> {
                            if (ev.paneId == paneId) {
                                refetchJob?.cancel()
                                refetchJob = scope.launch {
                                    kotlinx.coroutines.delay(250)
                                    refetch()
                                }
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
                refetchJob?.cancel()
                runCatching { client.close() }
                throw t
            } catch (t: Throwable) {
                error = t.message ?: t::class.simpleName
            } finally {
                refetchJob?.cancel()
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
    val cleaned = remember(lines, onSurface) { cleanOutputLines(lines, onSurface, lineCache) }
    val displayLines = cleaned.lines
    val contextPct = cleaned.contextPct
    val claudeMode = remember(lines) { detectClaudeMode(lines) }

    // Auto-scroll to bottom whenever the *visible* line count changes
    // (the LazyColumn renders `displayLines`, not `lines`, so the scroll
    // index has to come from there or it lands past the end).
    val listState = rememberLazyListState()
    LaunchedEffect(displayLines.size) {
        if (displayLines.isNotEmpty()) {
            delay(50)
            listState.scrollToItem(displayLines.lastIndex)
        }
    }

    // Auto-scroll to bottom whenever the keyboard opens or closes. Without
    // this the LazyColumn keeps the same first-visible-item, so opening
    // the keyboard pushes the latest output off the bottom of the now-
    // shorter visible area and the user has to scroll up by hand.
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val imeVisible = WindowInsets.isImeVisible
    LaunchedEffect(imeVisible) {
        if (displayLines.isNotEmpty()) {
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

    fun cycleMode() {
        scope.launch {
            val cfg = config ?: return@launch
            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
            runCatching { c.sendKey(paneId, "S-Tab") }
                .onFailure { error = it.message }
            c.close()
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
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    contextPct?.let { ContextChip(pct = it) }
                    ModeChip(mode = claudeMode, onClick = { cycleMode() })
                    IconButton(onClick = {
                        scope.launch {
                            val cfg = config ?: return@launch
                            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                            runCatching {
                                val cap = c.capture(paneId, lines = CAPTURE_LINES)
                                lines = cap.lines
                            }.onFailure { error = it.message }
                            c.close()
                        }
                    }) { Icon(Icons.Default.Refresh, contentDescription = "Refresh") }
                    IconButton(onClick = {
                        scope.launch {
                            val cfg = config ?: return@launch
                            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                            runCatching { c.cancelPane(paneId) }.onFailure { error = it.message }
                            c.close()
                        }
                    }) { Icon(Icons.Default.Stop, contentDescription = "Cancel (Ctrl-C)") }
                    IconButton(
                        onClick = { showKillDialog = true },
                        enabled = paneInfo != null && !killing,
                    ) {
                        Icon(
                            Icons.Default.DeleteForever,
                            contentDescription = "Kill window",
                            tint = MaterialTheme.colorScheme.error,
                        )
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
            // Output view
            Box(Modifier.weight(1f)) {
                SelectionContainer {
                    LazyColumn(
                        state = listState,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
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

            // Quick-key bar for navigating Claude Code pickers (AskUserQuestion,
            // plan-mode menus, etc) that use arrow keys + Enter, not typed text.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            ) {
                QuickKeyButton(icon = { Icon(Icons.Default.KeyboardArrowUp, "Up") }) { sendKey("Up") }
                QuickKeyButton(icon = { Icon(Icons.Default.KeyboardArrowDown, "Down") }) { sendKey("Down") }
                QuickKeyButton(icon = { Text("↵", style = MaterialTheme.typography.titleMedium) }) { sendKey("Enter") }
                QuickKeyButton(icon = { Text("Esc", style = MaterialTheme.typography.labelSmall) }) { sendKey("Escape") }
                QuickKeyButton(icon = { Text("Tab", style = MaterialTheme.typography.labelSmall) }) { sendKey("Tab") }
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
                        autoCorrect = false,
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
                val canSend = !sending && inputText.isNotBlank()
                IconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isBlank()) return@IconButton
                        sending = true
                        scope.launch {
                            val cfg = config ?: return@launch
                            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                            runCatching {
                                c.sendInput(paneId, text, submit = true)
                                inputText = ""
                                voiceTranscript = ""
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
                    runCatching { c.resolveApproval(approval.id, "allow") }
                        .onFailure { error = it.message }
                    c.close()
                    currentApproval = null
                }
            },
            onDeny = {
                scope.launch {
                    val cfg = config ?: return@launch
                    val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                    runCatching { c.resolveApproval(approval.id, "deny") }
                        .onFailure { error = it.message }
                    c.close()
                    currentApproval = null
                }
            },
            onDismiss = { currentApproval = null },
        )
    }

    // Kill window confirmation dialog
    if (showKillDialog) {
        val info = paneInfo
        AlertDialog(
            onDismissRequest = { if (!killing) showKillDialog = false },
            title = { Text("Kill window?") },
            text = {
                val target = info?.let { "${it.sessionName}:${it.windowIndex}" } ?: paneId
                Text(
                    "This will terminate every pane in $target. The action cannot be undone.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val p = info ?: return@Button
                        scope.launch {
                            killing = true
                            val cfg = config ?: run { killing = false; return@launch }
                            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                            val result = runCatching { c.killWindow(p.sessionName, p.windowIndex) }
                            c.close()
                            killing = false
                            showKillDialog = false
                            result.onSuccess { onBack() }
                                .onFailure { error = "Kill failed: ${it.message ?: it::class.simpleName}" }
                        }
                    },
                    enabled = info != null && !killing,
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
}

/**
 * Claude Code's runtime mode. Cycled via Shift-Tab on the host. Detected
 * by scanning the bottom of Claude's captured terminal output for the
 * mode-indicator strings Claude prints just above its input prompt:
 *   "bypass permissions on (shift+tab to cycle)"
 *   "auto-accept edits on (shift+tab to cycle)"
 *   "plan mode on (shift+tab to cycle)"
 * If none of those appear, the pane is in Normal mode.
 */
internal enum class ClaudeMode(val label: String) {
    Normal("Normal"),
    AutoAccept("Auto-Accept"),
    Plan("Plan"),
    Bypass("Bypass"),
}

internal fun detectClaudeMode(lines: List<String>): ClaudeMode {
    // Scan only the bottom of the buffer — mode indicators are always
    // near the prompt, never in scrollback content.
    val tail = lines.asReversed().take(20).joinToString("\n").lowercase()
    return when {
        "bypass permissions on" in tail -> ClaudeMode.Bypass
        "auto-accept edits on" in tail -> ClaudeMode.AutoAccept
        "plan mode on" in tail -> ClaudeMode.Plan
        else -> ClaudeMode.Normal
    }
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
 * Tappable mode chip — shows the pane's current Claude mode and cycles
 * to the next mode (via Shift-Tab on the host pane) when clicked. The
 * mode label re-derives from the captured output on the next refetch,
 * so the chip updates within a few seconds of the cycle landing.
 */
@Composable
private fun QuickKeyButton(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        icon()
    }
}

@Composable
private fun ModeChip(mode: ClaudeMode, onClick: () -> Unit) {
    val color = when (mode) {
        ClaudeMode.Normal     -> StatusColors.Idle
        ClaudeMode.AutoAccept -> StatusColors.Running
        ClaudeMode.Plan       -> StatusColors.Done
        ClaudeMode.Bypass     -> Color(0xFFFF6B6B)
    }
    Row(
        modifier = Modifier
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = mode.label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
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
            .padding(end = 4.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "ctx $pct%",
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Claude: ${approval.title}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (!approval.toolName.isNullOrBlank()) {
                    Text(
                        "Tool: ${approval.toolName}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Spacer(Modifier.height(4.dp))
                }
                if (approval.message.isNotBlank()) {
                    Text(
                        approval.message,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                }
                approval.toolInput?.let {
                    Spacer(Modifier.height(8.dp))
                    Surface(
                        tonalElevation = 2.dp,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            prettyJson(it),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onAllow) { Text("Allow") }
        },
        dismissButton = {
            TextButton(onClick = onDeny) { Text("Deny") }
        },
    )
}
