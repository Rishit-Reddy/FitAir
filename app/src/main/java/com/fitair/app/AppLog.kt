package com.fitair.app

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/** Small on-device log: last [MAX] lines kept in memory and mirrored to filesDir/fitair.log. */
object AppLog {
    private const val MAX = 400
    private val lines = ArrayDeque<String>()
    private var file: File? = null
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Bumped on every change so Compose can observe the log. */
    var version by mutableIntStateOf(0)
        private set

    @Synchronized
    fun init(ctx: Context) {
        if (file != null) return
        val f = File(ctx.filesDir, "fitair.log")
        file = f
        runCatching { f.readLines().takeLast(MAX).forEach { lines.addLast(it) } }
    }

    @Synchronized
    fun d(msg: String) = add(msg)

    @Synchronized
    fun e(msg: String, t: Throwable?) =
        add("ERROR $msg" + (t?.let { " — ${it.javaClass.simpleName}: ${it.message}" } ?: ""))

    private fun add(msg: String) {
        val line = "${fmt.format(Date())} $msg"
        lines.addLast(line)
        while (lines.size > MAX) lines.removeFirst()
        runCatching { file?.appendText(line + "\n") }
        if (file != null && file!!.length() > 200_000) {
            runCatching { file!!.writeText(lines.joinToString("\n") + "\n") }
        }
        version++
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        runCatching { file?.writeText("") }
        version++
    }
}
