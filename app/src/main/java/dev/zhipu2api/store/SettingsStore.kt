package dev.zhipu2api.store

import android.content.Context
import android.content.SharedPreferences

class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("zhipu_settings", Context.MODE_PRIVATE)

    var port: Int
        get() = prefs.getInt("port", 9980)
        set(v) = prefs.edit().putInt("port", v).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "sk-zhipu-local") ?: "sk-zhipu-local"
        set(v) = prefs.edit().putString("api_key", v).apply()

    var autoStart: Boolean
        get() = prefs.getBoolean("auto_start", true)
        set(v) = prefs.edit().putBoolean("auto_start", v).apply()

    var showFloat: Boolean
        get() = prefs.getBoolean("show_float", false)
        set(v) = prefs.edit().putBoolean("show_float", v).apply()

    /** 全局默认联网开关 */
    var useSearch: Boolean
        get() = prefs.getBoolean("use_search", false)
        set(v) = prefs.edit().putBoolean("use_search", v).apply()

    /** 默认模型 id（对外） */
    var defaultModel: String
        get() = prefs.getString("default_model", "glm") ?: "glm"
        set(v) = prefs.edit().putString("default_model", v).apply()

    /** 每个模型的"深度思考"开关，JSON 存储：{"glm":true,...} */
    var modelThink: String
        get() = prefs.getString("model_think", "{}") ?: "{}"
        set(v) = prefs.edit().putString("model_think", v).apply()

    /** 查询某模型是否开启深度思考 */
    fun isThinkOn(modelId: String): Boolean {
        return try {
            org.json.JSONObject(modelThink).optBoolean(modelId, false)
        } catch (_: Exception) {
            false
        }
    }

    /** 设置某模型的深度思考开关 */
    fun setThink(modelId: String, on: Boolean) {
        val obj = try { org.json.JSONObject(modelThink) } catch (_: Exception) { org.json.JSONObject() }
        obj.put(modelId, on)
        modelThink = obj.toString()
    }
}
