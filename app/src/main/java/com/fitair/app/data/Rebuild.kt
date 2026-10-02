package com.fitair.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RebuildState(val running: Boolean = false, val pct: Int = 0, val detail: String = "", val lastResult: String? = null)

/** STUB (A1). */
object Rebuild {
    private val _state = MutableStateFlow(RebuildState())
    val state: StateFlow<RebuildState> = _state
    fun start(ctx: Context) {}
}
