package io.github.kurue.bram.app

import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * The app process is kept alive by the foreground service while a model is loaded, so a reply
 * typed into the completion alert can be handed straight to the running conversation. Replay of
 * one keeps the text if the process was restarted and the ViewModel subscribes later.
 */
object BackgroundTurns {
    private val _incoming = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 4)
    val incoming: SharedFlow<String> = _incoming.asSharedFlow()

    fun submit(text: String) {
        _incoming.tryEmit(text)
    }
}

/**
 * Receives the "Reply" action from the completion alert and starts a turn without bringing the
 * app forward, so a slow run can be followed with further prompts from the shade.
 */
class CompletionReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reply = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(AgentTaskService.KEY_REPLY)
            ?.toString()
            ?.trim()
            .orEmpty()
        if (reply.isNotBlank()) BackgroundTurns.submit(reply)
    }
}
