package com.andreacanes.panemgmt.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.andreacanes.panemgmt.data.AuthStore
import com.andreacanes.panemgmt.data.CompanionClient
import kotlinx.coroutines.launch

@Composable
fun SetupScreen(
    authStore: AuthStore,
    onContinue: () -> Unit,
) {
    var baseUrl by remember { mutableStateOf("http://localhost:8833") }
    var token by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Text(
            text = "Pane Management",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Configure your companion backend",
            style = MaterialTheme.typography.bodyMedium,
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Backend URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            value = token,
            onValueChange = { token = it },
            label = { Text("Bearer token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedButton(
            onClick = {
                status = "Testing..."
                busy = true
                scope.launch {
                    val client = CompanionClient(baseUrl.trim(), token.trim())
                    status = try {
                        val h = client.health()
                        "OK: ${h.status}"
                    } catch (t: Throwable) {
                        "Failed: ${t.message ?: t::class.simpleName}"
                    } finally {
                        client.close()
                        busy = false
                    }
                }
            },
            enabled = !busy && baseUrl.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Test connection")
        }

        Button(
            onClick = {
                busy = true
                scope.launch {
                    authStore.save(baseUrl.trim(), token.trim())
                    busy = false
                    onContinue()
                }
            },
            enabled = !busy && baseUrl.isNotBlank() && token.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save & Continue")
        }

        status?.let {
            Spacer(Modifier.height(8.dp))
            Text(text = it, style = MaterialTheme.typography.bodySmall)
        }
    }
}
