(function () {
  "use strict";
  var Native = window.ZhipuNative || window.DangbeiNative || null;

  function call() {
    if (!Native) return null;
    var fn = arguments[0];
    if (typeof Native[fn] !== "function") return null;
    try { return Native[fn].apply(Native, Array.prototype.slice.call(arguments, 1)); }
    catch (e) { return null; }
  }

  var toastBox = document.getElementById("toasts");
  function toast(msg, isErr) {
    var el = document.createElement("div");
    el.className = "toast" + (isErr ? " is-err" : "");
    el.textContent = msg;
    toastBox.appendChild(el);
    setTimeout(function () { el.remove(); }, 2600);
  }

  /* 底部导航切换 */
  var bnaves = document.querySelectorAll(".bnav");
  var views = document.querySelectorAll(".view");
  function showView(name) {
    bnaves.forEach(function (b) { b.classList.toggle("is-active", b.getAttribute("data-view") === name); });
    views.forEach(function (v) { v.classList.toggle("is-active", v.id === "view-" + name); });
    if (name === "settings") refreshLogs();
    if (name === "models") refreshModels();
  }
  bnaves.forEach(function (b) {
    b.addEventListener("click", function () { showView(b.getAttribute("data-view")); });
  });

  /* 元素引用 */
  var el = {
    dot: document.getElementById("appDot"),
    statusChip: document.getElementById("statusChip"),
    tokenChip: document.getElementById("tokenChip"),
    baseUrlBig: document.getElementById("baseUrlBig"),
    statusMeta: document.getElementById("statusMeta"),
    baseUrl: document.getElementById("baseUrl"),
    apiKeyShow: document.getElementById("apiKeyShow"),
    tokenHint: document.getElementById("tokenHint"),
    fab: document.getElementById("fab"),
    fabIcon: document.getElementById("fabIcon"),
    navToggle: document.getElementById("navToggle")
  };

  function refreshStatus() {
    var raw = call("getStatus");
    if (!raw) return;
    var s;
    try { s = JSON.parse(raw); } catch (e) { return; }

    el.statusChip.textContent = s.running ? "运行中" : "未运行";
    el.statusMeta.textContent = "端口 " + s.port + " · 已处理 " + s.requestCount + " 次请求";
    el.dot.classList.toggle("is-on", !!s.running);
    el.baseUrlBig.textContent = s.baseUrl || ("http://" + s.ip + ":" + s.port + "/v1");
    el.baseUrl.textContent = s.baseUrl || "";
    el.fabIcon.textContent = s.running ? "■" : "▶";

    if (s.hasToken) {
      el.tokenChip.textContent = "已登录";
      el.tokenHint.textContent = "已登录智谱清言。网关运行中即可使用。";
    } else {
      el.tokenChip.textContent = "未登录";
      el.tokenHint.textContent = "在受控页面登录智谱清言，保持停留在对话页，然后点「完成」。";
    }
  }

  var logBody = document.getElementById("logBody");
  function refreshLogs() {
    var raw = call("getLogs");
    if (!raw) { logBody.textContent = "—"; return; }
    var arr;
    try { arr = JSON.parse(raw); } catch (e) { return; }
    logBody.textContent = arr.length ? arr.join("\n") : "—";
    logBody.scrollTop = logBody.scrollHeight;
  }

  /* 设置 */
  var setPort = document.getElementById("setPort");
  var setApiKey = document.getElementById("setApiKey");
  var setDefaultModel = document.getElementById("setDefaultModel");
  var setAutoStart = document.getElementById("setAutoStart");
  var setUseSearch = document.getElementById("setUseSearch");
  var setShowFloat = document.getElementById("setShowFloat");
  var setBattery = document.getElementById("setBattery");

  function refreshSettings() {
    var raw = call("getSettings");
    if (!raw) return;
    var s;
    try { s = JSON.parse(raw); } catch (e) { return; }
    setPort.value = s.port;
    setApiKey.value = s.apiKey || "";
    setDefaultModel.value = s.defaultModel || "glm";
    setAutoStart.checked = !!s.autoStart;
    setUseSearch.checked = !!s.useSearch;
    setShowFloat.checked = !!s.showFloat;
    try { setBattery.checked = !!call("isIgnoringBattery"); } catch (e) {}
  }

  document.getElementById("saveSettings").addEventListener("click", function () {
    var payload = JSON.stringify({
      port: parseInt(setPort.value, 10) || 9980,
      apiKey: setApiKey.value.trim(),
      autoStart: setAutoStart.checked,
      useSearch: setUseSearch.checked,
      defaultModel: setDefaultModel.value,
      showFloat: setShowFloat.checked
    });
    call("saveSettings", payload);
    toast("设置已保存");
    refreshStatus();
  });

  setShowFloat.addEventListener("change", function () {
    if (setShowFloat.checked) {
      if (!call("hasFloatPermission")) { call("requestOverlay"); toast("请授予悬浮窗权限"); }
      call("showFloat"); toast("悬浮窗已开启");
    } else { call("hideFloat"); toast("悬浮窗已关闭"); }
  });

  setBattery.addEventListener("change", function () {
    if (setBattery.checked) { call("requestIgnoreBattery"); toast("请允许忽略电池优化"); }
    else { toast("请到系统设置手动恢复"); setBattery.checked = true; }
  });

  /* 模型列表 */
  function refreshModels() {
    var box = document.getElementById("modelList");
    var raw = call("getModels");
    if (!box || !raw) return;
    var arr;
    try { arr = JSON.parse(raw); } catch (e) { return; }
    var thinkMap = {};
    try { thinkMap = JSON.parse(call("getModelThink") || "{}"); } catch (e) {}

    box.innerHTML = "";
    arr.forEach(function (m, idx) {
      if (idx > 0) {
        var d = document.createElement("div"); d.className = "divider"; box.appendChild(d);
      }
      var row = document.createElement("div");
      row.className = "switch-row";
      var text = document.createElement("div");
      text.className = "switch-row__text";
      text.innerHTML = '<div class="switch-row__t">' + (m.label || m.id) + '</div>' +
                       '<div class="switch-row__s">ID: ' + m.id + (m.think ? ' · 支持思考' : '') + '</div>';
      var sw = document.createElement("label");
      sw.className = "switch";
      var cb = document.createElement("input");
      cb.type = "checkbox";
      cb.checked = !!thinkMap[m.id];
      cb.disabled = !m.think;
      cb.addEventListener("change", function () {
        call("setModelThink", m.id, cb.checked);
        toast((cb.checked ? "已开启" : "已关闭") + "深度思考");
      });
      sw.appendChild(cb);
      sw.appendChild(document.createElement("span"));
      row.appendChild(text);
      row.appendChild(sw);
      box.appendChild(row);
    });

    setDefaultModel.innerHTML = "";
    arr.forEach(function (m) {
      var op = document.createElement("option");
      op.value = m.id;
      op.textContent = m.label || m.id;
      setDefaultModel.appendChild(op);
    });
  }

  /* 按钮 */
  function bind(id, fn) {
    var b = document.getElementById(id);
    if (b) b.addEventListener("click", fn);
  }
  bind("runBtn", function () { call("startGateway"); toast("正在启动网关"); setTimeout(refreshStatus, 700); });
  bind("restartBtn", function () { call("restartGateway"); toast("正在重启"); setTimeout(refreshStatus, 700); });
  bind("stopBtn", function () { call("stopGateway"); toast("网关已停止"); setTimeout(refreshStatus, 500); });
  bind("loginBtn", function () { call("openLogin"); });
  bind("clearTokenBtn", function () { call("clearToken"); toast("已清除"); setTimeout(refreshStatus, 300); });
  bind("clearLogs", function () { call("clearLogs"); refreshLogs(); });

  el.navToggle.addEventListener("click", function () {
    var running = false;
    try { running = !!JSON.parse(call("getStatus")).running; } catch (e) {}
    if (running) { call("stopGateway"); toast("网关已停止"); }
    else { call("startGateway"); toast("正在启动网关"); }
    setTimeout(refreshStatus, 700);
  });

  el.fab.addEventListener("click", function () {
    var running = false;
    try { running = !!JSON.parse(call("getStatus")).running; } catch (e) {}
    if (running) { call("stopGateway"); toast("网关已停止"); }
    else { call("startGateway"); toast("正在启动网关"); }
    setTimeout(refreshStatus, 700);
  });

  /* 复制：走原生桥（WebView 里 clipboard API 被禁） */
  document.querySelectorAll("[data-copy]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var t = document.getElementById(btn.getAttribute("data-copy"));
      if (!t) return;
      var text = t.textContent;
      var ok = false;
      try { ok = call("copyToClipboard", text); if (ok === null || ok === undefined) ok = true; } catch (e) { ok = false; }
      if (ok !== false) { toast("已复制"); }
      else { toast("复制失败", true); }
    });
  });

  window.onTokenSaved = function () { toast("登录已保存"); refreshStatus(); };

  /* 初始化 */
  function init() {
    if (!Native) {
      el.statusChip.textContent = "预览模式";
      return;
    }
    refreshModels();
    refreshSettings();
    refreshStatus();
    refreshLogs();
    setInterval(refreshStatus, 2000);
    setInterval(refreshLogs, 3000);
  }
  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else { init(); }
})();
