package dev.zhipu2api.store

import android.content.Context

/**
 * 登录凭证存储：保存从智谱网页 Cookie 提取的 token。
 * 单例，进程内共享。
 */
class TokenStore private constructor(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("zhipu_token", Context.MODE_PRIVATE)

    var token: String
        get() = prefs.getString("token", "") ?: ""
        set(v) = prefs.edit().putString("token", v.trim()).apply()

    /** 记录 token 保存时间（用于提示可能过期） */
    var savedAt: Long
        get() = prefs.getLong("saved_at", 0L)
        set(v) = prefs.edit().putLong("saved_at", v).apply()

    var remark: String
        get() = prefs.getString("remark", "") ?: ""
        set(v) = prefs.edit().putString("remark", v.trim()).apply()

    fun hasToken(): Boolean = token.isNotEmpty()

    fun clear() {
        prefs.edit().remove("token").remove("saved_at").apply()
    }

    companion object {
        @Volatile
        private var instance: TokenStore? = null

        fun get(context: Context): TokenStore {
            return instance ?: synchronized(this) {
                instance ?: TokenStore(context).also { instance = it }
            }
        }
    }
}
