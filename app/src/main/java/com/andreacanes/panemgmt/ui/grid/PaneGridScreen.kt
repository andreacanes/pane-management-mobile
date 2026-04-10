package com.andreacanes.panemgmt.ui.grid

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.andreacanes.panemgmt.data.AuthStore
import kotlinx.coroutines.launch

@Composable
fun PaneGridScreen(
    authStore: AuthStore,
    onLoggedOut: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Pane grid — TODO",
            style = MaterialTheme.typography.headlineSmall,
        )
        OutlinedButton(
            onClick = {
                scope.launch {
                    authStore.clear()
                    onLoggedOut()
                }
            },
        ) {
            Text("Log out")
        }
    }
}
