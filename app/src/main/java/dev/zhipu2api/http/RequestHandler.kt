package dev.zhipu2api.http

import dev.zhipu2api.zhipu.ZhipuClient
import dev.zhipu2api.model.Models
import dev.zhipu2api.store.SettingsStore
import dev.zhipu2api.store.TokenStore
import dev.zhipu2api.util.Logger
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID

/**
 * OpenAI 兼容路由：
 *   GET  /v1/models
 *   POST /v1/chat/completions （stream / non-stream）
 */
class RequestHandler(
    private val settings: SettingsStore,
    private val tokenStore: TokenStore,
    private val client: ZhipuClient
) {

    fun handle(req: Request, out: OutputStream) {
        val writer = ResponseWriter(out)
        val path = req.path.substringBefore("?")

        when {
            req.method == "OPTIONS" -> {
                writer.writeJson(200, "{}")
                return
            }
            path == "/v1/models" && req.method == "GET" -> {
                if (!auth(req, writer)) return
                writer.writeJson(200, modelsJson())
                return
            }
            path == "/v1/chat/completions" && req.method == "POST" -> {
                if (!auth(req, writer)) return
                handleChat(req, writer)
                return
            }
            path == "/health" -> {
                writer.writeJson(200, "{\"status\":\"ok\",\"hasToken\":${tokenStore.hasToken()}}")
                return
            }
            else -> {
                writer.writeJson(404, errorJson("Not found: $path", "invalid_request_error"))
                return
            }
        }
    }

    private fun auth(req: Request, writer: ResponseWriter): Boolean {
        val key = settings.apiKey
        if (key.isEmpty()) return true
        val token = req.bearerToken()
        if (token != key) {
            writer.writeJson(401, errorJson("Invalid API key", "invalid_api_key"))
            return false
        }
        return true
    }

    private fun handleChat(req: Request, writer: ResponseWriter) {
        val body = try {
            JSONObject(req.body)
        } catch (e: Exception) {
            writer.writeJson(400, errorJson("Invalid JSON body", "invalid_request_error"))
            return
        }

        val prompt = buildPrompt(body)
        if (prompt.isEmpty()) {
            writer.writeJson(400, errorJson("No user message", "invalid_request_error"))
            return
        }

        val token = tokenStore.token
        if (!client.isReady() && token.isEmpty()) {
            writer.writeJson(401, errorJson("智谱页面未就绪，请先在 App 内登录并启动网关", "invalid_api_key"))
            return
        }

        val rawModel = body.optString("model", settings.defaultModel)
        val parsed = parseModel(rawModel)
        val search = parsed.search

        val stream = body.optBoolean("stream", false)
        val id = "chatcmpl-" + UUID.randomUUID().toString().replace("-", "").take(24)
        val created = System.currentTimeMillis() / 1000
        val outModel = rawModel

        if (!stream) {
            try {
                val reply = client.chat(prompt, search, null)
                writer.writeJson(200, completionJson(id, created, outModel, reply))
            } catch (e: Exception) {
                Logger.log("请求失败：${e.message}")
                val (status, type) = classifyError(e.message ?: "")
                writer.writeJson(status, errorJson(e.message ?: "upstream error", type))
            }
            return
        }

        writer.beginChunked()
        try {
            writer.writeChunk(sseChunk(id, created, outModel, mapOf("role" to "assistant")))
            val reply = client.chat(prompt, search, object : ZhipuClient.Listener {
                override fun onDelta(delta: String) {
                    if (delta.isNotEmpty()) {
                        writer.writeChunk(sseChunk(id, created, outModel, mapOf("content" to delta)))
                    }
                }
                override fun onDone(fullText: String, error: String?) {}
            })
            if (reply.isEmpty()) {
                writer.writeChunk(sseChunk(id, created, outModel, mapOf("content" to "")))
            }
            writer.writeChunk(sseChunk(id, created, outModel, emptyMap(), "stop"))
            writer.writeChunk("data: [DONE]\n\n")
        } catch (e: Exception) {
            Logger.log("流式请求失败：${e.message}")
            writer.writeChunk(sseError(e.message ?: "upstream error"))
        }
        writer.endChunked()
    }

    private data class ParsedModel(val code: String, val think: Boolean, val search: Boolean)

    private fun parseModel(modelId: String): ParsedModel {
        var base = modelId
        var think = false
        var search = false
        if (base.endsWith("-think-search")) {
            base = base.removeSuffix("-think-search"); think = true; search = true
        } else if (base.endsWith("-think")) {
            base = base.removeSuffix("-think"); think = true
        } else if (base.endsWith("-search")) {
            base = base.removeSuffix("-search"); search = true
        }
        val m = Models.byId(base)
        val code = m?.id ?: base
        if (m != null && !m.supportThink) {
            think = false
        } else {
            // 模型支持思考：叠加 App 里"深度思考"开关的全局设置
            if (settings.isThinkOn(base)) think = true
        }
        if (!search) search = settings.useSearch
        return ParsedModel(code, think, search)
    }

    private fun buildPrompt(body: JSONObject): String {
        val messages = body.optJSONArray("messages") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until messages.length()) {
            val m = messages.optJSONObject(i) ?: continue
            val role = m.optString("role", "user")
            val content = m.optString("content")
            if (content.isEmpty()) continue
            when (role) {
                "system" -> sb.append("[system] ").append(content).append("\n")
                "assistant" -> sb.append("[assistant] ").append(content).append("\n")
                else -> sb.append(content).append("\n")
            }
        }
        return sb.toString().trim()
    }

    private fun sseChunk(
        id: String,
        created: Long,
        model: String,
        delta: Map<String, String>,
        finish: String? = null
    ): String {
        val deltaObj = JSONObject()
        for ((k, v) in delta) deltaObj.put(k, v)
        val choice = JSONObject()
            .put("index", 0)
            .put("delta", deltaObj)
            .put("finish_reason", finish ?: JSONObject.NULL)
        val obj = JSONObject()
            .put("id", id)
            .put("object", "chat.completion.chunk")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
        return "data: $obj\n\n"
    }

    private fun sseError(msg: String): String {
        val obj = JSONObject()
            .put("error", JSONObject().put("message", msg).put("type", "api_error"))
        return "data: $obj\n\n"
    }

    private fun completionJson(id: String, created: Long, model: String, content: String): String {
        val message = JSONObject().put("role", "assistant").put("content", content)
        val choice = JSONObject()
            .put("index", 0)
            .put("message", message)
            .put("finish_reason", "stop")
        val usage = JSONObject()
            .put("prompt_tokens", 0)
            .put("completion_tokens", 0)
            .put("total_tokens", 0)
        return JSONObject()
            .put("id", id)
            .put("object", "chat.completion")
            .put("created", created)
            .put("model", model)
            .put("choices", JSONArray().put(choice))
            .put("usage", usage)
            .toString()
    }

    private fun modelsJson(): String {
        val now = System.currentTimeMillis() / 1000
        val list = JSONArray()
        for (id in Models.openAiIds()) {
            val base = id.substringBefore("-think").substringBefore("-search")
            val m = Models.byId(base)
            list.put(
                JSONObject()
                    .put("id", id)
                    .put("object", "model")
                    .put("created", now)
                    .put("owned_by", "zhipu")
                    .put("label", m?.label ?: id)
            )
        }
        return JSONObject().put("object", "list").put("data", list).toString()
    }

    private fun errorJson(msg: String, type: String): String =
        JSONObject().put("error", JSONObject().put("message", msg).put("type", type)).toString()

    /**
     * 把上游错误分类成 OpenAI 风格的 (HTTP 状态码, type)。
     * token 失效 → 401，帮用户定位问题。
     */
    private fun classifyError(msg: String): Pair<Int, String> {
        val m = msg.lowercase()
        return when {
            m.contains("token") || m.contains("未登录") || m.contains("登录") ||
                m.contains("401") || m.contains("unauthorized") || m.contains("失效") ->
                Pair(401, "invalid_api_key")
            m.contains("timeout") || m.contains("超时") ->
                Pair(504, "timeout_error")
            m.contains("未收到") || m.contains("空响应") ->
                Pair(502, "upstream_error")
            else -> Pair(500, "api_error")
        }
    }
}
