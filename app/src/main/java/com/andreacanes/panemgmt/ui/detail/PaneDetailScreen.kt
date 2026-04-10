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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.data.CompanionClient
import com.andreacanes.panemgmt.data.models.ApprovalDto
import com.andreacanes.panemgmt.data.models.EventDto
import com.andreacanes.panemgmt.voice.VoiceInputController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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

    val voice = remember { VoiceInputController(context) }
    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            voiceListening = true
            scope.launch {
                voice.listen().collect { state ->
                    when (state) {
                        is VoiceInputController.State.Listening -> {
                            voiceTranscript = ""
                        }
                        is VoiceInputController.State.PartialTranscript -> {
                            voiceTranscript = state.text
                        }
                        is VoiceInputController.State.FinalTranscript -> {
                            voiceTranscript = state.text
                            voiceListening = false
                            inputText = state.text
                        }
                        is VoiceInputController.State.Error -> {
                            voiceListening = false
                            error = "Voice error: ${state.message}"
                        }
                        VoiceInputController.State.Idle -> Unit
                    }
                }
                voiceListening = false
            }
        } else {
            error = "Microphone permission denied"
        }
    }

    // Initial load + WS for this pane's updates
    LaunchedEffect(config, paneId) {
        val cfg = config ?: return@LaunchedEffect
        val client = CompanionClient(cfg.baseUrl, cfg.bearerToken)
        try {
            val cap = client.capture(paneId, lines = 200)
            lines = cap.lines
            approvals = client.listApprovals().filter { it.paneId == paneId }
            client.events().collect { ev ->
                when (ev) {
                    is EventDto.Snapshot -> {
                        approvals = ev.approvals.filter { it.paneId == paneId }
                    }
                    is EventDto.PaneOutputChanged -> {
                        if (ev.paneId == paneId) {
                            // Append the tail; the companion already sends only the new lines
                            lines = (lines + ev.tail).takeLast(500)
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
        } catch (t: Throwable) {
            error = t.message ?: t::class.simpleName
        } finally {
            client.close()
        }
    }

    // Auto-scroll to bottom on new lines
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) {
            delay(50)
            listState.scrollToItem(lines.lastIndex)
        }
    }

    // If there's any pending approval for this pane, surface the first one.
    LaunchedEffect(approvals) {
        if (currentApproval == null && approvals.isNotEmpty()) {
            currentApproval = approvals.first()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(paneId, fontFamily = FontFamily.Monospace) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scope.launch {
                            val cfg = config ?: return@launch
                            val c = CompanionClient(cfg.baseUrl, cfg.bearerToken)
                            runCatching {
                                val cap = c.capture(paneId, lines = 200)
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
                        items(lines) { line ->
                            Text(
                                text = line,
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall,
                            )
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

            // Input row
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = inputText,
                    onValueChange = { inputText = it },
                    placeholder = { Text("Type or dictate…") },
                    singleLine = false,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = {
                        if (!voice.isAvailable()) {
                            error = "SpeechRecognizer unavailable on this device"
                            return@FilledIconButton
                        }
                        if (!voice.hasMicPermission()) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        } else {
                            voiceListening = true
                            scope.launch {
                                voice.listen().collect { state ->
                                    when (state) {
                                        is VoiceInputController.State.Listening -> {
                                            voiceTranscript = ""
                                        }
                                        is VoiceInputController.State.PartialTranscript -> {
                                            voiceTranscript = state.text
                                        }
                                        is VoiceInputController.State.FinalTranscript -> {
                                            voiceTranscript = state.text
                                            voiceListening = false
                                            inputText = state.text
                                        }
                                        is VoiceInputController.State.Error -> {
                                            voiceListening = false
                                            error = "Voice error: ${state.message}"
                                        }
                                        VoiceInputController.State.Idle -> Unit
                                    }
                                }
                                voiceListening = false
                            }
                        }
                    },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (voiceListening) Color(0xFFE53935)
                        else MaterialTheme.colorScheme.secondaryContainer,
                    ),
                ) { Icon(Icons.Default.Mic, contentDescription = "Voice input") }
                FilledIconButton(
                    onClick = {
                        val text = inputText.trim()
                        if (text.isBlank()) return@FilledIconButton
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
                    enabled = !sending && inputText.isNotBlank(),
                ) { Icon(Icons.Default.Send, contentDescription = "Send") }
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
                    Text(
                        it.toString(),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
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
