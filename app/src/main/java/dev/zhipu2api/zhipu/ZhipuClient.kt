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

  // 智谱回复是"全量快照"，需自算增量
  var __zp_rendered = '';

  function computeDelta(prev, cur){
    if (cur === prev) return '';
    if (prev && cur.indexOf(prev) === 0) return cur.substring(prev.length);
    return cur;
  }

  // 从一条 SSE data JSON 里取"可见回答"，返回 {text, done}
  function extractText(json){
    try {
      var o = JSON.parse(json);
      if (!o || !o.parts) {
        return {text:'', done: (o && o.status && (o.status==='finish'||o.status==='finished'))};
      }
      var snapshot = '';
      var done = false;
      for (var i=0;i<o.parts.length;i++){
        var p = o.parts[i];
        var ps = (p.status||'').toLowerCase();
        var c = p.content || [];
        for (var j=0;j<c.length;j++){
          var it = c[j];
          var tp = (it.type||'').toLowerCase();
          if (tp !== 'text') continue;      // 跳过 think / tool_calls
          var t = it.text || '';
          if (t) snapshot = t;
          if (ps==='finish'||ps==='finished') done = true;
        }
      }
      var st = (o.status||'').toLowerCase();
      if (st==='finish'||st==='finished') done = true;
      return {text: snapshot, done: done};
    } catch(e){ return {text:'', done:false}; }
  }

  function feed(chunk){
    try {
      var lines = chunk.split('\n');
      for (var i=0;i<lines.length;i++){
        var line = lines[i].trim();
        if (line.indexOf('data:') !== 0) continue;
        var payload = line.substring(5).trim();
        if (!payload) continue;
        var r = extractText(payload);
        if (r.text) {
          var d = computeDelta(__zp_rendered, r.text);
          __zp_rendered = r.text;
          if (d) ZhipuNative.onDelta(d);
        }
        if (r.done) { ZhipuNative.onDone(''); }
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
      if (url && url.indexOf('assistant/stream') >= 0) {
        ZhipuNative.onLog('命中 fetch 流');
        try { __zp_startDomFallback(); } catch(e){}
        var ret = origFetch.apply(this, arguments);
        try { ret.then(function(resp){ readStream(resp); }); } catch(e){}
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
      if (this.__zp_url && this.__zp_url.indexOf('assistant/stream') >= 0) {
        ZhipuNative.onLog('命中 xhr 流');
        try { __zp_startDomFallback(); } catch(e){}
        var self = this;
        this.addEventListener('progress', function(){
          try { feed(self.responseText.substring(self.__zp_pos||0)); self.__zp_pos = self.responseText.length; } catch(e){}
        });
        this.addEventListener('load', function(){
          try { feed(self.responseText.substring(self.__zp_pos||0)); } catch(e){}
          ZhipuNative.onDone('');
        });
      }
    } catch(e){}
    return origSend.apply(this, arguments);
  };

  // DOM 兜底：网络流若没触发 onDone，就盯着回复区文字，涨到稳定就结束
  var __zp_domTimer = null;
  var __zp_domLast = '';
  var __zp_domStable = 0;
  function __zp_startDomFallback(){
    if (__zp_domTimer) return;
    __zp_domTimer = setInterval(function(){
      try {
        var el = document.querySelector('.answer-content-wrap:not(.text-advance-thinking-content) .markdown-body') ||
                 document.querySelector('.markdown-body');
        if (!el) return;
        var txt = (el.innerText || '').trim();
        if (!txt) return;
        if (txt !== __zp_domLast) {
          __zp_domLast = txt;
          __zp_domStable = 0;
          var d = computeDelta(__zp_rendered, txt);
          __zp_rendered = txt;
          if (d) ZhipuNative.onDelta(d);
        } else {
          __zp_domStable++;
          // 连续 4 次(约2秒)无变化 → 认为结束
          if (__zp_domStable >= 4 && txt.length > 0) {
            clearInterval(__zp_domTimer); __zp_domTimer = null;
            ZhipuNative.onDone('');
          }
        }
      } catch(e){}
    }, 500);
  }
  function __zp_resetDom(){
    if (__zp_domTimer) { clearInterval(__zp_domTimer); __zp_domTimer = null; }
    __zp_domLast = ''; __zp_domStable = 0; __zp_rendered = '';
  }

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

  // 智谱输入框/发送键的精确选择器（源自 universal-web-api 站点配置）
  var SEL_INPUT = '.input-box-inner textarea';
  var SEL_SEND  = '.enter .enter-icon-container, .enter-icon-container, img.enter_icon';

  function $(sel){ try { return document.querySelector(sel); } catch(e){ return null; } }

  window.__ZP_SEND__ = function(text, search){
    try {
      ZhipuNative.onLog('__ZP_SEND__ 被调用，search=' + search);
      __zp_resetDom();
      var box = $(SEL_INPUT);
      if (!box) { ZhipuNative.onDone('找不到输入框(' + SEL_INPUT + ')，请确认已登录停在对话页'); return; }
      ZhipuNative.onLog('找到输入框，填入');
      box.focus();
      setNativeValue(box, text);
      fireInput(box, text);
      ZhipuNative.onLog('已填值，长度=' + box.value.length);

      setTimeout(function(){
        var btn = $(SEL_SEND);
        if (btn) {
          ZhipuNative.onLog('点击发送键');
          try { btn.click(); } catch(e){}
          try { btn.dispatchEvent(new MouseEvent('click', {bubbles:true, cancelable:true, view:window})); } catch(e){}
        } else {
          ZhipuNative.onLog('未找到发送键，改用回车');
          box.dispatchEvent(new KeyboardEvent('keydown', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
          box.dispatchEvent(new KeyboardEvent('keypress', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
          box.dispatchEvent(new KeyboardEvent('keyup', {key:'Enter', code:'Enter', keyCode:13, which:13, bubbles:true, cancelable:true}));
        }
      }, 500);
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
