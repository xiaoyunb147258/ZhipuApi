package dev.zhipu2api

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 悬浮窗：常驻显示网关地址与运行状态，可拖动。
 * 需要 SYSTEM_ALERT_WINDOW 权限。
 */
class FloatingWindow(private val context: Context) {

    private var view: View? = null
    private var wm: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null

    private fun dp(v: Int): Int = (v * context.resources.displayMetrics.density).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show(text: String) {
        if (view != null) { update(text); return }
        wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#CC0E1116"))
            textSize = 12f
            setPadding(dp(12), dp(8), dp(12), dp(8))
            this.text = text
        }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = dp(16)
        lp.y = dp(120)

        var initX = 0
        var initY = 0
        var touchX = 0f
        var touchY = 0f
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    initX = lp.x; initY = lp.y
                    touchX = e.rawX; touchY = e.rawY
                    false
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = initX + (e.rawX - touchX).toInt()
                    lp.y = initY + (e.rawY - touchY).toInt()
                    try { wm?.updateViewLayout(tv, lp) } catch (_: Exception) {}
                    true
                }
                else -> false
            }
        }

        try {
            wm?.addView(tv, lp)
            view = tv
            params = lp
        } catch (_: Exception) {
        }
    }

    @SuppressLint("SetTextI18n")
    fun update(text: String) {
        (view as? TextView)?.text = text
    }

    fun hide() {
        try {
            view?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        view = null
    }
}
