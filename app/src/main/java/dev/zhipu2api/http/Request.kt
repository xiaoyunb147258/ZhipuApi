package dev.zhipu2api.http

/** 极简 HTTP 请求模型 */
class Request(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String
) {
    fun header(name: String): String? = headers[name.lowercase()]

    fun bearerToken(): String? {
        val auth = header("authorization") ?: return null
        return if (auth.startsWith("Bearer ", ignoreCase = true)) {
            auth.substring(7).trim()
        } else auth.trim()
    }
}
