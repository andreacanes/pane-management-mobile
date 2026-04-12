package com.andreacanes.panemgmt.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.andreacanes.panemgmt.data.models.PaneState
import com.andreacanes.panemgmt.ui.theme.paneStateColor

/**
 * Unified status chip for a pane state — used on the grid card and the
 * detail header. A small colored dot followed by a semantic label.
 */
@Composable
fun StatusChip(
    state: PaneState,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val color = paneStateColor(state)
    val label = when (state) {
        PaneState.Idle    -> "Idle"
        PaneState.Running -> "Running"
        PaneState.Waiting -> "Waiting"
        PaneState.Done    -> "Done"
    }
    val bg = color.copy(alpha = 0.14f)
    val border = color.copy(alpha = 0.35f)

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .border(1.dp, border, RoundedCornerShape(999.dp))
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 2.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(color = color)
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

@Composable
private fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Box(
        modifier = modifier
            .size(8.dp)
            .clip(RoundedCornerShape(999.dp))
            .background(color),
    )
}
