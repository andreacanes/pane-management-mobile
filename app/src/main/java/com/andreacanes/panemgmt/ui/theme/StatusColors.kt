package com.andreacanes.panemgmt.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.andreacanes.panemgmt.data.models.PaneState

/**
 * Semantic status palette shared with the Tauri desktop app. Values here
 * mirror `--status-idle/running/waiting/done` in `workspace-resume/src/index.css`
 * so both surfaces render the same signal for the same state.
 */
object StatusColors {
    val Idle    = Color(0xFF888888)
    val Running = Color(0xFF22C55E)
    val Waiting = Color(0xFFF59E0B)
    val Done    = Color(0xFF3B82F6)
}

@Composable
@ReadOnlyComposable
fun paneStateColor(state: PaneState): Color = when (state) {
    PaneState.Idle    -> StatusColors.Idle
    PaneState.Running -> StatusColors.Running
    PaneState.Waiting -> StatusColors.Waiting
    PaneState.Done    -> StatusColors.Done
}
