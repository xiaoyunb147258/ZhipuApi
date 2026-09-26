package dev.zhipu2api.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 轻量日志：保留最近 N 条环形缓冲，供 UI 读取，同时输出到 logcat。
 */
object Logger {

    private const val MAX = 200
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val buffer = ArrayDeque<String>()
    private val listeners = mutableListOf<(String) -> Unit>()

    fun log(msg: String) {
        val line = "[${fmt.format(Date())}] $msg"
        synchronized(buffer) {
            buffer.addLast(line)
            while (buffer.size > MAX) buffer.removeFirst()
            val snapshot = listeners.toList()
            snapshot.forEach { it(line) }
        }
    }

    fun recent(): List<String> = synchronized(buffer) { buffer.toList() }

    fun clear() = synchronized(buffer) { buffer.clear() }

    fun addListener(l: (String) -> Unit) = synchronized(buffer) { listeners.add(l) }

    fun removeListener(l: (String) -> Unit) = synchronized(buffer) { listeners.remove(l) }
}
