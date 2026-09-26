package dev.zhipu2api.zhipu

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import dev.zhipu2api.util.Logger
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 智谱清言网页引擎（WebView 傀儡法）。
 *
 * 关键设计：
 * - WebView 不挂到 WindowManager（后台运行即可执行 JS），避免"网页感"和悬浮窗权限依赖。
 * - 轮询注入钩子，直到页面 SPA 渲染出输入框，解决时序问题。
 * - 同时 hook fetch 与 XHR，确保能拦到智谱的 SSE 流。
 */
class ZhipuClient private constructor(private val context: Context) {

    interface Listener {
        fun onDelta(delta: String)
        fun onDone(fullText: String, error: String?)
    }

    private val handler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var ready = false

    // 当前待完成的请求
    private var curLatch: CountDownLatch? = null
    private val curFull = StringBuilder()
    private var curError: String? = null
    private var curListener: Listener? = null

    private val bridge = object {
        @JavascriptInterface
        fun onDelta(text: String) {
            if (text.isEmpty()) return
            synchronized(curFull) { curFull.append(text) }
            handler.post { curListener?.onDelta(text) }
        }

        @JavascriptInterface
        fun onDone(err: String?) {
            curError = if (err.isNullOrEmpty()) null else err
            curLatch?.countDown()
        }

        @JavascriptInterface
        fun onReady() {
            ready = true
            Logger.log("智谱页面就绪")
        }

        @JavascriptInterface
        fun onLog(msg: String) {
            Logger.log("[页面] $msg")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun ensureWebView(onReady: () -> Unit) {
        if (webView != null) { onReady(); return }
        handler.post {
            val wv = WebView(context)
            wv.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                userAgentString = "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
                        "AppleWebKit/537.36 (KHTML, like Gecko) " +
                        "Chrome/120.0.0.0 Mobile Safari/537.36"
            }
            wv.addJavascriptInterface(bridge, "ZhipuNative")
            wv.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && url.contains("chatglm.cn")) {
                        // 反复注入，直到钩子真正就位（解决 SPA 时序）
                        val target = view ?: wv
                        startInjectLoop(target)
                    }
                }
            }
            // 不挂 WindowManager：WebView 只要被 new 出来、loadUrl，就能执行 JS
            wv.loadUrl(PAGE_URL)
            webView = wv
            onReady()
        }
    }

    private var injectAttempts = 0
    private fun startInjectLoop(view: WebView) {
        injectAttempts = 0
        val runnable = object : Runnable {
            override fun run() {
                if (ready || injectAttempts > 40) return
                injectAttempts++
                view.evaluateJavascript(INJECT_HOOK) { result ->
                    // 注入成功后页面会回调 onReady()
                }
                if (!ready) handler.postDelayed(this, 1500)
            }
        }
        handler.post(runnable)
    }

    fun isReady(): Boolean = ready

    /**
     * 发送消息（阻塞式），返回完整回答。调用方应在子线程执行。
     */
    fun chat(prompt: String, search: Boolean, listener: Listener?): String {
        val wv = webView ?: throw Exception("网页引擎未初始化")
        synchronized(curFull) { curFull.setLength(0) }
        curError = null
        curListener = listener
        val latch = CountDownLatch(1)
        curLatch = latch

        val q = JSONObject.quote(prompt)
        val js = "(function(){ try { if(!window.__ZP_SEND__){ZhipuNative.onDone('发送函数未就绪，页面可能未登录或未加载完');return;} window.__ZP_SEND__($q, $search); } catch(e){ ZhipuNative.onDone('发送失败:'+e.message); } })()"
        handler.post { wv.evaluateJavascript(js, null) }

        val ok = latch.await(300, TimeUnit.SECONDS)
        if (!ok) throw Exception("等待智谱回复超时")
        curError?.let { throw Exception(it) }
        val r = synchronized(curFull) { curFull.toString() }
        if (r.isBlank()) throw Exception("未收到有效回答（可能未登录或页面结构变化）")
        return r
    }

    fun shutdown() {
        handler.post {
            try { webView?.destroy() } catch (_: Exception) {}
            webView = null
            ready = false
        }
    }

    companion object {
        const val PAGE_URL = "https://chatglm.cn/"

        private const val INJECT_HOOK = """
(function(){
  if (window.__ZP_READY__) { ZhipuNative.onReady(); return; }

  var origFetch = window.fetch;
  var origOpen = XMLHttpRequest.prototype.open;
  var origSend = XMLHttpRequest.prototype.send;

  function extractText(json){
    try {
      var o = JSON.parse(json);
      if (!o || !o.parts) return '';
      var out = '';
      for (var i=0;i<o.parts.length;i++){
        var p = o.parts[i];
        if (p.role !== 'assistant') continue;
        var c = p.content || [];
        for (var j=0;j<c.length;j++){
          if (c[j].type === 'text' && c[j].text) out += c[j].text;
        }
      }
      return out;
    } catch(e){ return ''; }
  }

  function feed(chunk){
    try {
      var parts = chunk.split('\n');
      for (var i=0;i<parts.length;i++){
        var line = parts[i].trim();
        if (line.indexOf('data:') === 0) {
          var t = extractText(line.substring(5).trim());
          if (t) ZhipuNative.onDelta(t);
        }
      }
    } catch(e){}
  }

  // 读 fetch 的流
  function readStream(resp){
    try {
      if (!resp || !resp.body) { ZhipuNative.onDone(''); return; }
      var reader = resp.body.getReader();
      var dec = new TextDecoder();
      var buf = '';
      function pump(){
        reader.read().then(function(x){
          if (x.done) { ZhipuNative.onDone(''); return; }
          buf += dec.decode(x.value, {stream:true});
          var idx = buf.lastIndexOf('\n');
          if (idx >= 0) { feed(buf.substring(0,idx)); buf = buf.substring(idx+1); }
          pump();
        }).catch(function(e){ ZhipuNative.onDone('流读取失败:'+e); });
      }
      pump();
    } catch(e){ ZhipuNative.onDone('流处理失败:'+e); }
  }

  window.fetch = function(){
    try {
      var input = arguments[0], init = arguments[1] || {};
      var url = typeof input === 'string' ? input : (input && input.url);
      ZhipuNative.onLog('fetch -> ' + url);
      if (url && url.indexOf('assistant/stream') >= 0) {
        ZhipuNative.onLog('命中 fetch 流: ' + url);
        var ret = origFetch.apply(this, arguments);
        try { ret.then(function(resp){ ZhipuNative.onLog('fetch 响应 status=' + (resp && resp.status)); readStream(resp); }); } catch(e){}
        return ret;
      }
    } catch(e){}
    return origFetch.apply(this, arguments);
  };

  XMLHttpRequest.prototype.open = function(m,u){
    this.__zp_url = u;
    return origOpen.apply(this, arguments);
  };
  XMLHttpRequest.prototype.send = function(){
    try {
      ZhipuNative.onLog('xhr -> ' + this.__zp_url);
      if (this.__zp_url && this.__zp_url.indexOf('assistant/stream') >= 0) {
        ZhipuNative.onLog('命中 xhr 流');
        var self = this;
        this.addEventListener('progress', function(){
          try { feed(self.responseText.substring(self.__zp_pos||0)); self.__zp_pos = self.responseText.length; } catch(e){}
        });
        this.addEventListener('load', function(){ ZhipuNative.onDone(''); });
      }
    } catch(e){}
    return origSend.apply(this, arguments);
  };

  // React 受控组件取值：骗过 _valueTracker，让 React 认为值真的变了
  function setNativeValue(el, value){
    var proto = (el.tagName === 'TEXTAREA') ? window.HTMLTextAreaElement.prototype
              : window.HTMLInputElement.prototype;
    var desc = Object.getOwnPropertyDescriptor(proto, 'value');
    if (desc && desc.set) desc.set.call(el, value);
    else el.value = value;
    if (el._valueTracker) el._valueTracker.setValue('');
  }

  function fireInput(el, text){
    try {
      el.dispatchEvent(new InputEvent('input', {bubbles:true, cancelable:true, inputType:'insertText', data:text}));
    } catch(e){
      el.dispatchEvent(new Event('input', {bubbles:true}));
    }
    el.dispatchEvent(new Event('change', {bubbles:true}));
  }

  window.__ZP_SEND__ = function(text, search){
    try {
      ZhipuNative.onLog('__ZP_SEND__ 被调用，search=' + search);
      var box = document.querySelector('textarea') ||
                document.querySelector('[contenteditable="true"]');
      if (!box) { ZhipuNative.onDone('找不到输入框，请确认已登录且停留在对话页'); return; }
      ZhipuNative.onLog('找到输入框 tag=' + box.tagName);
      box.focus();
      if (box.tagName === 'TEXTAREA' || box.tagName === 'INPUT') {
        setNativeValue(box, text);
        fireInput(box, text);
      } else {
        box.innerHTML = '';
        try { document.execCommand('insertText', false, text); } catch(e){}
        if (!box.innerText) { box.textContent = text; }
        fireInput(box, text);
      }
      ZhipuNative.onLog('已填值，当前长度=' + (box.value !== undefined ? box.value.length : box.innerText.length));
      setTimeout(function(){
        // 优先回车发送（聊天框最认这个）
        ZhipuNative.onLog('尝试回车发送');
        box.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
        // 兜底：也点一下发送按钮（有些前端只认点击）
        try {
          var scope = box.closest('form') || box.parentElement || document;
          var btn = scope.querySelector('button[type="submit"]') ||
                    document.querySelector('button[aria-label*="发送"]') ||
                    document.querySelector('[class*="send"]');
          if (btn) { ZhipuNative.onLog('同时点击发送按钮'); btn.click(); }
        } catch(e){}
      }, 400);
    } catch(e){ ZhipuNative.onDone('驱动发送失败:'+e.message); }
  };

  window.__ZP_READY__ = true;
  ZhipuNative.onReady();
})();
"""

        @Volatile
        private var instance: ZhipuClient? = null

        fun get(context: Context): ZhipuClient {
            return instance ?: synchronized(this) {
                instance ?: ZhipuClient(context.applicationContext).also { instance = it }
            }
        }
    }
}
