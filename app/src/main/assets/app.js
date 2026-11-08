/* ═══════════════════════════════════════════
   Hosts Guard
   Alpine.store 全局状态 + Android 桥
   ═══════════════════════════════════════════ */

(function () {
  'use strict';

  /* ── 原生桥 ───────────────────────────── */
  function native(action, data) {
    try {
      if (window.Android && window.Android.post) {
        window.Android.post(JSON.stringify({ action: action, data: data || {} }));
      }
    } catch (e) {}
  }

  function vibe(p) {
    try { if (navigator.vibrate) navigator.vibrate(p); } catch (e) {}
  }

  function nowTime() {
    var d = new Date();
    return ('0' + d.getHours()).slice(-2) + ':' +
           ('0' + d.getMinutes()).slice(-2) + ':' +
           ('0' + d.getSeconds()).slice(-2);
  }

  /* ── 规则分类 ─────────────────────────── */
  function classify(d) {
    d = d.toLowerCase();
    if (d.indexOf('anticheatexpert') >= 0) return 'anticheat';
    if (d.indexOf('gamesafe') >= 0)        return 'gamesafe';
    if (d.indexOf('faceid') >= 0)          return 'faceid';
    if (d.indexOf('beacon') >= 0)          return 'beacon';
    if (d.indexOf('.mdt.') >= 0 || d.indexOf('mdt.qq') >= 0) return 'mdt';
    if (d.indexOf('tga.qq') >= 0)          return 'tga';
    return 'other';
  }

  function parseHosts(text) {
    var out = [], seen = {};
    var lines = String(text || '').split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      var ln = lines[i].trim();
      if (!ln || ln.charAt(0) === '#') continue;
      var p = ln.split(/\s+/);
      if (p.length < 2) continue;
      if (!/^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$/.test(p[0])) continue;
      var dom = p[1].toLowerCase();
      if (!dom || dom.indexOf('.') < 0) continue;
      var tld = dom.split('.').pop();
      if (!/^[a-z]{2,}$/.test(tld)) continue;
      if (seen[dom]) continue;
      seen[dom] = 1;
      out.push({ ip: p[0], dom: dom, cat: classify(dom), on: true });
    }
    return out;
  }

  /* ═══════ Alpine Store ═══════ */
  document.addEventListener('alpine:init', function () {

    Alpine.store('G', {

      /* 状态 */
      tab: 'home',
      running: false,
      busy: false,
      failed: false,
      failMsg: '',
      floatOn: false,
      overlayGranted: false,
      copied: false,
      srcLabel: '内置规则',

      rules: [],
      stats: { block: 0, total: 0 },
      logs: [],

      _logSeq: 0,
      _logCache: [],

      /* ── 派生值（方法，被模板显式调用） ── */
      enabledCount() {
        var n = 0;
        for (var i = 0; i < this.rules.length; i++) if (this.rules[i].on) n++;
        return n;
      },

      stateText() {
        if (this.busy)   return '正在启动';
        if (this.failed) return '启动失败';
        if (this.running) return '防护中';
        return '已关闭';
      },

      stateSub() {
        if (this.busy)   return '建立本地代理…';
        if (this.failed) return this.failMsg || '请检查规则';
        if (this.running) return 'DNS 127.0.0.1:5354';
        return '点按启动本地代理';
      },

      /* 日志：倒序，最多 60 条；用序号判断缓存是否失效 */
      visibleLogs() {
        var seq = this._logSeq;          /* 每加一条就 +1，shift 时也变 */
        if (this._logCache && this._logCache._seq === seq) return this._logCache;

        var len = this.logs.length;
        var n = Math.min(len, 60);
        var arr = [];
        for (var i = len - 1; i >= len - n; i--) arr.push(this.logs[i]);
        arr._seq = seq;
        this._logCache = arr;
        return arr;
      },

      logCountText() {
        var t = this.logs.length;
        if (!t) return '—';
        return t > 60 ? '60/' + t : String(t);
      },

      /* ── 动作 ── */
      setTab(p) {
        if (this.tab === p) return;
        this.tab = p;
        vibe(5);
        native('tab', { page: p });
      },

      toggleProxy() {
        if (this.busy) return;
        this.busy = true;
        this.failed = false;
        vibe(16);
        native(this.running ? 'stop' : 'start');

        var self = this;
        setTimeout(function () {
          if (self.busy) { self.busy = false; }
        }, 8000);
      },

      reload() {
        vibe(8);
        native('reload');
        this.addLog('ok', '正在重新读取规则');
      },

      resetStats() {
        vibe(8);
        this.stats = { block: 0, total: 0 };
        native('resetStats');
      },

      toggleFloat() {
        if (!this.overlayGranted) { native('askOverlay'); return; }
        this.floatOn = !this.floatOn;
        vibe(10);
        native('floatWindow', { on: this.floatOn });
      },

      askOverlay() {
        vibe(6);
        native('askOverlay');
      },

      copyChannel() {
        vibe(8);
        var ok = false;
        try {
          var el = document.createElement('textarea');
          el.value = '@NeonEdgeX';
          el.setAttribute('readonly', '');
          el.style.cssText = 'position:fixed;opacity:0;left:-9999px';
          document.body.appendChild(el);
          el.select();
          el.setSelectionRange(0, 99);
          ok = document.execCommand('copy');
          document.body.removeChild(el);
        } catch (e) {}
        native('copy', { text: '@NeonEdgeX' });

        var self = this;
        this.copied = true;
        setTimeout(function () { self.copied = false; }, 1600);
      },

      addLog(type, msg) {
        this.logs.push({
          id: this._logSeq++,
          t: type || 'info',
          msg: String(msg || ''),
          time: nowTime()
        });
        if (this.logs.length > 300) this.logs.shift();
      }
    });
  });

  /* ═══════ 原生调用接口 ═══════ */
  function S() { return Alpine.store('G'); }

  window.UI = {
    setRules(text, label) {
      var s = S();
      s.rules = parseHosts(text);
      if (label) s.srcLabel = label;
    },

    setSrcLabel(label) { if (label) S().srcLabel = label; },

    setRunning(on) {
      var s = S();
      s.running = !!on;
      s.busy = false;
      s.failed = false;
    },

    setBusy(b) {
      var s = S();
      s.busy = !!b;
      if (b) s.failed = false;
    },

    error(msg) {
      var s = S();
      s.busy = false;
      s.running = false;
      s.failed = true;
      s.failMsg = msg || '';
      s.addLog('bad', msg || '启动失败');
      vibe(12);
    },

    setStats(o) {
      var s = S();
      s.stats = { block: o.block | 0, total: o.total | 0 };
    },

    addLog(type, msg) { S().addLog(type, msg); },

    setFloat(on) { S().floatOn = !!on; },

    setOverlayGranted(g) { S().overlayGranted = !!g; },

    /* 悬浮球回传，主页同步 */
    setRunningFromFloat(on) {
      var s = S();
      s.running = !!on;
      s.busy = false;
      s.failed = false;
    }
  };

  /* Alpine 启动后再通知原生就绪 */
  document.addEventListener('alpine:initialized', function () {
    native('ready');
  });

  /* 兜底：Alpine 未能初始化时，1.2s 后强制显示界面 */
  setTimeout(function () {
    if (!window.Alpine || !document.querySelector('.shell')) return;
    if (!window.Alpine.store('G')) {
      document.body.classList.add('no-alpine');
    }
  }, 1200);

})();