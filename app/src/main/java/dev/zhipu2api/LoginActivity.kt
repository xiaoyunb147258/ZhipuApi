package dev.zhipu2api

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * 智谱清言登录页。用户在 WebView 里登录 chatglm.cn 并保持在对话页，
 * 然后点右上角「完成」返回。登录态由浏览器 Cookie 保存，网关的隐藏 WebView
 * 会复用同一份 Cookie（同一进程共享）。
 */
class LoginActivity : AppCompatActivity() {

    private lateinit var webView: WebView

    companion object {
        const val EXTRA_TOKEN = "zhipu_ok"
        const val LOGIN_URL = "https://chatglm.cn/"
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0E1116"))

        webView = WebView(this)
        val mobileUa = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/120.0.0.0 Mobile Safari/537.36"
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            userAgentString = mobileUa
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        webView.webViewClient = WebViewClient()

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        lp.topMargin = dp(56)
        root.addView(webView, lp)

        val bar = FrameLayout(this)
        bar.setBackgroundColor(Color.parseColor("#0E1116"))

        val title = TextView(this).apply {
            text = "登录智谱清言"
            setTextColor(Color.parseColor("#E6EAF2"))
            textSize = 15f
            gravity = Gravity.CENTER_VERTICAL
        }
        val tlp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        tlp.leftMargin = dp(16)
        bar.addView(title, tlp)

        val saveBtn = Button(this).apply {
            text = "完成"
            setTextColor(Color.parseColor("#0E1116"))
            setBackgroundColor(Color.parseColor("#8AAE5B"))
            setOnClickListener {
                Toast.makeText(this@LoginActivity, "登录已保存", Toast.LENGTH_SHORT).show()
                setResult(RESULT_OK)
                finish()
            }
        }
        val blp = FrameLayout.LayoutParams(dp(80), dp(40))
        blp.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        blp.rightMargin = dp(12)
        bar.addView(saveBtn, blp)

        root.addView(bar, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(56)
        ))

        setContentView(root)
        webView.loadUrl(LOGIN_URL)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
