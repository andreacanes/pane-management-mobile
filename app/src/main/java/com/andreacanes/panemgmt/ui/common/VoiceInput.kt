package com.andreacanes.panemgmt.ui.common

import com.andreacanes.panemgmt.voice.VoiceInputController

/**
 * Drive the voice-input state machine against a shared set of callbacks.
 * Both the permission-granted launcher and the mic-button click site
 * call this so the state transitions stay in one place.
 */
suspend fun collectVoiceInput(
    voice: VoiceInputController,
    onPartial: (String) -> Unit,
    onFinal: (String) -> Unit,
    onError: (String) -> Unit,
    onDone: () -> Unit,
) {
    try {
        voice.listen().collect { state ->
            when (state) {
                is VoiceInputController.State.Listening ->
                    onPartial("")
                is VoiceInputController.State.PartialTranscript ->
                    onPartial(state.text)
                is VoiceInputController.State.FinalTranscript -> {
                    onFinal(state.text)
                }
                is VoiceInputController.State.Error ->
                    onError(state.message)
                VoiceInputController.State.Idle -> Unit
            }
        }
    } finally {
        onDone()
    }
}
