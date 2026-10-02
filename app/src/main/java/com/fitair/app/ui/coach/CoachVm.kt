package com.fitair.app.ui.coach

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fitair.app.AppLog
import com.fitair.app.ChatMsg
import com.fitair.app.ChatStore
import com.fitair.app.CoachRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** State and actions for the Coach tab (replaces the chat part of MainViewModel). */
class CoachVm(app: Application) : AndroidViewModel(app) {
    private val repo = CoachRepo(app)

    var chat by mutableStateOf(ChatStore.load(app)); private set
    var thinking by mutableStateOf(false); private set
    /** Live tool note while a request runs, e.g. "looked up readiness for Fri 2 Oct". */
    var status by mutableStateOf<String?>(null); private set
    var chatError by mutableStateOf<String?>(null); private set

    private fun update(m: List<ChatMsg>) { chat = m; ChatStore.save(getApplication(), m) }

    fun sendChat(text: String) {
        if (thinking || text.isBlank()) return
        AppLog.d("coach: send (${text.length} chars)")
        update(chat + ChatMsg("user", text.trim()))
        run(long = false, replaceLast = false)
    }

    /** Re-asks the last question with depth=long and replaces the last answer. */
    fun explainMore() {
        if (thinking || chat.lastOrNull()?.role != "assistant") return
        AppLog.d("coach: explain more")
        run(long = true, replaceLast = true)
    }

    /** After a cut-off answer. */
    fun continueChat() = sendChat("Continue")

    fun retryChat() {
        if (thinking) return
        if (chat.lastOrNull()?.role != "user") update(chat.dropLastWhile { it.role != "user" })
        AppLog.d("coach: retry")
        run(long = false, replaceLast = false)
    }

    fun clearChat() { AppLog.d("coach: chat cleared"); ChatStore.clear(getApplication()); chat = emptyList(); chatError = null }

    private fun run(long: Boolean, replaceLast: Boolean) {
        thinking = true; chatError = null; status = null
        val history = if (replaceLast) chat.dropLast(1) else chat
        viewModelScope.launch {
            try {
                val r = repo.answer(history.filter { it.role != "note" }, long) { status = it }
                val msg = ChatMsg("assistant", r.text, r.answerJson, r.trace, r.long, r.truncated)
                update(history + msg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AppLog.d("coach error: $e")
                chatError = e.message ?: e.toString()
                if (replaceLast) update(chat) // keep the short answer visible
            } finally {
                thinking = false; status = null
            }
        }
    }
}
