package dev.zhipu2api.http

import java.io.OutputStream

/**
 * HTTP 响应写出器：支持普通 JSON 响应与 chunked（SSE 流式）响应。
 */
class ResponseWriter(private val out: OutputStream) {

    private var headersSent = false
    private var chunked = false

    private val corsHeaders = buildString {
        append("Access-Control-Allow-Origin: *\r\n")
        append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
        append("Access-Control-Allow-Headers: *\r\n")
    }

    fun writeJson(status: Int, json: String) {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 $status ${statusText(status)}\r\n")
            append("Content-Type: application/json; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append(corsHeaders)
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
        headersSent = true
    }

    fun beginChunked() {
        if (headersSent) return
        val head = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("Content-Type: text/event-stream; charset=utf-8\r\n")
            append("Cache-Control: no-cache\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append(corsHeaders)
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.flush()
        chunked = true
        headersSent = true
    }

    /** 写一个 SSE 数据块（chunked 编码） */
    fun writeChunk(data: String) {
        if (!chunked) return
        val bytes = data.toByteArray(Charsets.UTF_8)
        out.write(Integer.toHexString(bytes.size).toByteArray(Charsets.US_ASCII))
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.write("\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
    }

    fun endChunked() {
        if (!chunked) return
        out.write("0\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.flush()
        chunked = false
    }

    private fun statusText(code: Int) = when (code) {
        200 -> "OK"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        404 -> "Not Found"
        500 -> "Internal Server Error"
        else -> "OK"
    }
}
