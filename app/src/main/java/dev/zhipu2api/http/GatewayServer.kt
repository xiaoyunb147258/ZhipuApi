package dev.zhipu2api.http

import dev.zhipu2api.util.Logger
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 手写 HTTP/1.1 服务器。绑定 0.0.0.0:port，固定线程池。
 * 支持普通响应与 chunked（SSE 流式）响应。
 */
class GatewayServer(
    private val port: Int,
    private val handler: RequestHandler
) {
    @Volatile
    private var running = false
    private var serverSocket: ServerSocket? = null

    companion object {
        @Volatile
        var runningPort = 0
            private set

        private val requestCounter = AtomicLong(0)
        fun requestCount(): Long = requestCounter.get()
    }

    private val pool = Executors.newFixedThreadPool(8)

    fun start() {
        if (running) return
        serverSocket = ServerSocket(port)
        runningPort = serverSocket!!.localPort
        running = true
        Logger.log("网关已启动：0.0.0.0:$runningPort")
        val acceptThread = Thread({
            while (running) {
                try {
                    val socket = serverSocket?.accept() ?: break
                    pool.execute { handleClient(socket) }
                } catch (e: Exception) {
                    if (running) Logger.log("accept 异常：${e.message}")
                }
            }
        }, "zhipu-accept")
        acceptThread.isDaemon = true
        acceptThread.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        Logger.log("网关已停止")
    }

    fun isRunning(): Boolean = running

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 300_000
            val input = socket.inputStream
            val output = BufferedOutputStream(socket.outputStream)
            val req = readRequest(input, output) ?: run { socket.close(); return }
            requestCounter.incrementAndGet()
            Logger.log("${req.method} ${req.path}")
            handler.handle(req, output)
            output.flush()
            socket.close()
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun readRequest(input: InputStream, output: OutputStream): Request? {
        val first = readLine(input) ?: return null
        if (first.isBlank()) return null
        val parts = first.split(" ")
        if (parts.size < 2) return null
        val method = parts[0]
        val path = parts[1]
        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: break
            if (line.isEmpty()) break
            val idx = line.indexOf(":")
            if (idx > 0) {
                headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }
        }

        if (headers["expect"]?.lowercase()?.contains("100-continue") == true) {
            try {
                output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
                output.flush()
            } catch (_: Exception) {}
        }

        val body = StringBuilder()
        val len = headers["content-length"]?.toIntOrNull() ?: 0
        if (len > 0) {
            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val r = input.read(buf, read, len - read)
                if (r < 0) break
                read += r
            }
            body.append(String(buf, 0, read, Charsets.UTF_8))
        }
        return Request(method, path, headers, body.toString())
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code && prev == '\r'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            prev = c
        }
    }
}
