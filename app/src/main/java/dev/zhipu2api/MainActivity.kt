package dev.zhipu2api

import android.Manifest
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import dev.zhipu2api.store.SettingsStore
import dev.zhipu2api.store.TokenStore
import dev.zhipu2api.util.Logger
import dev.zhipu2api.util.NetUtil
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var settings: SettingsStore
    private lateinit var tokenStore: TokenStore
    private var floating: FloatingWindow? = null

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    private val loginLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                // WebView 傀儡法：token 由页面 Cookie 自动携带，这里只记"已登录"标记
                tokenStore.token = "webview-session"
                tokenStore.savedAt = System.currentTimeMillis()
                Logger.log("登录标记已保存（WebView 会话）")
                webView.post { webView.evaluateJavascript("window.onTokenSaved && window.onTokenSaved()", null) }
            }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        tokenStore = TokenStore.get(this)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            loadWithOverviewMode = true
            useWideViewPort = true
        }
        webView.webViewClient = WebViewClient()

        webView.addJavascriptInterface(Bridge(), "ZhipuNative")
        webView.loadUrl("file:///android_asset/index.html")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        requestNotificationPermission()
    }

    /** 申请悬浮窗权限（跳到系统设置页） */
    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } catch (_: Exception) {}
        }
    }

    /** 申请忽略电池优化（后台保活） */
    @SuppressLint("BatteryLife")
    private fun requestIgnoreBattery() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) {
            Logger.log("已在忽略电池优化白名单")
            return
        }
        // 1) 直接申请忽略
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")))
            return
        } catch (_: Exception) {}
        // 2) 兜底：跳电池优化列表
        try {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            return
        } catch (_: Throwable) {}
        // 3) 再兜底：跳本应用详情页
        try {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))
        } catch (_: Throwable) {}
    }

    /** 同步悬浮窗显示状态 */
    private fun syncFloat() {
        if (settings.showFloat) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
                return  // 没权限，等用户授权后再显示
            }
            if (floating == null) floating = FloatingWindow(this)
            val port = if (GatewayService.running) GatewayService.currentPort else settings.port
            val state = if (GatewayService.running) "运行中" else "已停止"
            floating?.show("智谱网关 · $state\n${NetUtil.getLocalIp()}:$port")
        } else {
            floating?.hide()
            floating = null
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startService(action: String) {
        try {
            val svc = Intent(this, GatewayService::class.java).apply { this.action = action }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(svc)
            } else {
                startService(svc)
            }
            // 服务状态变化后同步悬浮窗
            webView.postDelayed({ syncFloat() }, 800)
        } catch (e: Exception) {
            Logger.log("服务操作失败：${e.message}")
        }
    }

    /**
     * 暴露给前端的 JS 桥。方法在 JS 里通过 window.ZhipuNative.xxx() 调用。
     */
    inner class Bridge {

        @JavascriptInterface
        fun startGateway() = startService(GatewayService.ACTION_START)

        @JavascriptInterface
        fun stopGateway() = startService(GatewayService.ACTION_STOP)

        @JavascriptInterface
        fun restartGateway() = startService(GatewayService.ACTION_RESTART)

        @JavascriptInterface
        fun openLogin() {
            runOnUiThread {
                loginLauncher.launch(Intent(this@MainActivity, LoginActivity::class.java))
            }
        }

        @JavascriptInterface
        fun getStatus(): String {
            val running = GatewayService.running
            val port = if (running) GatewayService.currentPort else settings.port
            val obj = JSONObject().apply {
                put("running", running)
                put("port", port)
                put("ip", NetUtil.getLocalIp())
                put("hasToken", tokenStore.hasToken())
                put("tokenPreview", if (tokenStore.hasToken()) tokenStore.token.take(8) + "…" + tokenStore.token.takeLast(4) else "")
                put("requestCount", dev.zhipu2api.http.GatewayServer.requestCount())
                put("baseUrl", "http://${NetUtil.getLocalIp()}:$port/v1")
            }
            return obj.toString()
        }

        @JavascriptInterface
        fun getSettings(): String {
            val obj = JSONObject().apply {
                put("port", settings.port)
                put("apiKey", settings.apiKey)
                put("autoStart", settings.autoStart)
                put("useSearch", settings.useSearch)
                put("defaultModel", settings.defaultModel)
            }
            return obj.toString()
        }

        @JavascriptInterface
        fun saveSettings(json: String) {
            try {
                val j = JSONObject(json)
                if (j.has("port")) settings.port = j.optInt("port", 9980)
                if (j.has("apiKey")) settings.apiKey = j.optString("apiKey", "")
                if (j.has("autoStart")) settings.autoStart = j.optBoolean("autoStart", true)
                if (j.has("useSearch")) settings.useSearch = j.optBoolean("useSearch", false)
                if (j.has("defaultModel")) settings.defaultModel = j.optString("defaultModel", "glm")
                if (j.has("showFloat")) {
                    settings.showFloat = j.optBoolean("showFloat", false)
                    runOnUiThread { syncFloat() }
                }
                Logger.log("设置已保存")
            } catch (e: Exception) {
                Logger.log("设置保存失败：${e.message}")
            }
        }

        @JavascriptInterface
        fun getModels(): String {
            val arr = JSONArray()
            for (m in dev.zhipu2api.model.Models.ALL) {
                arr.put(JSONObject().apply {
                    put("id", m.id)
                    put("label", m.label)
                    put("think", m.supportThink)
                })
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun getModelThink(): String = settings.modelThink

        @JavascriptInterface
        fun setModelThink(modelId: String, on: Boolean) {
            settings.setThink(modelId, on)
            Logger.log("模型 $modelId 深度思考=${if (on) "开" else "关"}")
        }

        @JavascriptInterface
        fun getLogs(): String {
            val arr = JSONArray()
            Logger.recent().forEach { arr.put(it) }
            return arr.toString()
        }

        @JavascriptInterface
        fun clearLogs() = Logger.clear()

        @JavascriptInterface
        fun clearToken() {
            tokenStore.clear()
            Logger.log("token 已清除")
        }

        @JavascriptInterface
        fun copyToClipboard(text: String) {
            try {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                cm.setPrimaryClip(ClipData.newPlainText("zhipu", text))
            } catch (e: Exception) {
                Logger.log("复制失败：${e.message}")
            }
        }

        @JavascriptInterface
        fun requestIgnoreBattery() {
            runOnUiThread { requestIgnoreBattery() }
        }

        @JavascriptInterface
        fun requestOverlay() {
            runOnUiThread { requestOverlayPermission() }
        }

        @JavascriptInterface
        fun showFloat() {
            settings.showFloat = true
            runOnUiThread { syncFloat() }
            Logger.log("悬浮窗已开启")
        }

        @JavascriptInterface
        fun hideFloat() {
            settings.showFloat = false
            runOnUiThread {
                floating?.hide()
                floating = null
            }
            Logger.log("悬浮窗已关闭")
        }

        @JavascriptInterface
        fun hasFloatPermission(): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                Settings.canDrawOverlays(this@MainActivity)
            else true
        }

        @JavascriptInterface
        fun isIgnoringBattery(): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
            val pm = getSystemService(POWER_SERVICE) as PowerManager
            return pm.isIgnoringBatteryOptimizations(packageName)
        }
    }

    override fun onResume() {
        super.onResume()
        // 从权限设置页返回时，若开了悬浮窗但没显示，补显示
        syncFloat()
    }

    override fun onDestroy() {
        floating?.hide()
        floating = null
        webView.destroy()
        super.onDestroy()
    }
}
