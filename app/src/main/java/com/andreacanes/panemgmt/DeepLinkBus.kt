package com.andreacanes.panemgmt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-wide bus for pane-deep-link intents arriving via
 * `panemgmt://pane/<id>`. MainActivity.onNewIntent posts here;
 * PaneMgmtApp collects in a LaunchedEffect and navigates.
 *
 * Deliberately not DI-scoped — MainActivity.onNewIntent runs outside
 * of any Compose composition and needs a static drop point.
 */
object DeepLinkBus {
    private val _paneIdFlow = MutableStateFlow<String?>(null)
    val paneIdFlow: StateFlow<String?> = _paneIdFlow

    fun post(paneId: String) {
        _paneIdFlow.value = paneId
    }

    fun consume() {
        _paneIdFlow.value = null
    }
}
