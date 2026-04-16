package com.andreacanes.panemgmt.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import com.andreacanes.panemgmt.data.models.PaneState
import com.andreacanes.panemgmt.data.models.WaitingReason

/**
 * Semantic status palette shared with the Tauri desktop app. Values here
 * mirror `--status-idle/running/waiting/done` in `workspace-resume/src/index.css`
 * so both surfaces render the same signal for the same state.
 */
object StatusColors {
    val Idle            = Color(0xFF888888)
    val Running         = Color(0xFF22C55E)
    /** Legacy/default Waiting hue — also used as the Request flavor. */
    val Waiting         = Color(0xFFF59E0B) // amber
    /** Claude has stopped and wants a nudge (Continue flavor). */
    val WaitingContinue = Color(0xFF60A5FA) // soft blue
    val Done            = Color(0xFF3B82F6)
}

@Composable
@ReadOnlyComposable
fun paneStateColor(state: PaneState, reason: WaitingReason? = null): Color = when (state) {
    PaneState.Idle    -> StatusColors.Idle
    PaneState.Running -> StatusColors.Running
    PaneState.Waiting -> when (reason) {
        WaitingReason.Continue -> StatusColors.WaitingContinue
        WaitingReason.Request, null -> StatusColors.Waiting
    }
    PaneState.Done    -> StatusColors.Done
}
