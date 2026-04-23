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
import com.andreacanes.panemgmt.data.models.ProjectDto
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
    var launching by remember { mutableStateOf(false) }

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

            Button(
                onClick = {
                    val proj = selectedProject ?: return@Button
                    scope.launch {
                        launching = true
                        val config = authStore.configFlow.first() ?: run {
                            launching = false
                            return@launch
                        }
                        val client = CompanionClient(config.baseUrl, config.bearerToken)
                        val result = runCatching {
                            client.createWindow(
                                CreateWindowRequest(
                                    sessionName = defaultSessionName,
                                    projectPath = proj.actualPath,
                                    projectDisplayName = proj.displayName,
                                    account = selectedAccount.key,
                                )
                            )
                        }
                        client.close()
                        launching = false
                        result.onSuccess { response ->
                            onLaunched(response)
                            onDismiss()
                        }.onFailure { t ->
                            error = "Launch failed: ${t.message ?: t::class.simpleName}"
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
