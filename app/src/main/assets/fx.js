/* ═══════════════════════════════════════════════════
   Hosts Guard · 特效引擎
   独立于 Alpine，纯 DOM 操作
   ═══════════════════════════════════════════════════ */

(function () {
  'use strict';

  /* ───────── 配置 ───────── */
  var CFG = {
    snowCount: 34,      /* 雪花数量 */
    snowMinDur: 7,      /* 下落最快秒数 */
    snowMaxDur: 16,     /* 下落最慢秒数 */
    snowMaxDrift: 60,   /* 横向摆动最大 px */
    ripple: true,
    scan: true,
    bump: true
  };

  /* 根据屏幕面积调整雪花数量 */
  var area = window.innerWidth * window.innerHeight;
  if (area < 300 * 500) {
    CFG.snowCount = 20;
  } else if (area > 420 * 900) {
    CFG.snowCount = 46;
  }

  var reduceMotion = false;
  try {
    reduceMotion = window.matchMedia &&
                   window.matchMedia('(prefers-reduced-motion: reduce)').matches;
  } catch (e) {}

  /* ═══════════════════════════════════════
     1. 雪花
     ═══════════════════════════════════════ */
  function startSnow() {
    if (reduceMotion) return;

    var layer = document.querySelector('.fx-snow');
    if (!layer) {
      layer = document.createElement('div');
      layer.className = 'fx-snow';
      layer.setAttribute('aria-hidden', 'true');
      document.body.appendChild(layer);
    }

    if (layer.childElementCount > 0) return;  /* 已生成过 */

    var frag = document.createDocumentFragment();
    var sizes = ['s', 's', 's', 'm', 'm', 'l'];   /* 小的多，大的少 */

    for (var i = 0; i < CFG.snowCount; i++) {
      var f = document.createElement('i');

      f.className = sizes[(Math.random() * sizes.length) | 0];

      var left = Math.random() * 100;
      var dur = CFG.snowMinDur + Math.random() * (CFG.snowMaxDur - CFG.snowMinDur);
      var delay = -Math.random() * dur;          /* 负数延迟 → 一开始就在不同的高度 */
      var dx = (Math.random() * 2 - 1) * CFG.snowMaxDrift;

      f.style.left = left.toFixed(2) + '%';
      f.style.animationDuration = dur.toFixed(2) + 's';
      f.style.animationDelay = delay.toFixed(2) + 's';
      f.style.setProperty('--dx', dx.toFixed(1) + 'px');

      frag.appendChild(f);
    }

    layer.appendChild(frag);
  }

  /* ═══════════════════════════════════════
     2. 点击涟漪
     ═══════════════════════════════════════ */
  function makeRipple(el, ev) {
    if (!CFG.ripple || reduceMotion) return;

    var rect = el.getBoundingClientRect();
    var size = Math.max(rect.width, rect.height) * 1.1;

    var x, y;
    if (ev && ev.touches && ev.touches[0]) {
      x = ev.touches[0].clientX - rect.left;
      y = ev.touches[0].clientY - rect.top;
    } else if (ev && typeof ev.clientX === 'number') {
      x = ev.clientX - rect.left;
      y = ev.clientY - rect.top;
    } else {
      x = rect.width / 2;
      y = rect.height / 2;
    }

    var r = document.createElement('span');
    r.className = 'fx-ripple';
    r.style.width = size + 'px';
    r.style.height = size + 'px';
    r.style.left = (x - size / 2) + 'px';
    r.style.top = (y - size / 2) + 'px';

    el.appendChild(r);
    setTimeout(function () {
      if (r.parentNode) r.parentNode.removeChild(r);
    }, 600);
  }

  /* ═══════════════════════════════════════
     3. 扫描线
     ═══════════════════════════════════════ */
  function runScan() {
    if (!CFG.scan || reduceMotion) return;

    var host = document.querySelector('.stage') || document.body;
    var line = document.querySelector('.fx-scan');
    if (!line) {
      line = document.createElement('div');
      line.className = 'fx-scan';
      line.setAttribute('aria-hidden', 'true');
      host.appendChild(line);
    }

    line.classList.remove('run');
    /* 强制重排，让动画能重播 */
    void line.offsetWidth;
    line.classList.add('run');
  }

  /* ═══════════════════════════════════════
     4. 数字跳动
     监听 DOM 文本变化（针对 .stat strong）
     ═══════════════════════════════════════ */
  function watchBump() {
    if (!CFG.bump || reduceMotion) return;

    var els = document.querySelectorAll('.stat strong');
    if (!els.length) return;

    for (var i = 0; i < els.length; i++) {
      (function (el) {
        var last = el.textContent;
        var mo = new MutationObserver(function () {
          var now = el.textContent;
          if (now === last) return;
          last = now;
          el.classList.remove('bump');
          void el.offsetWidth;
          el.classList.add('bump');
        });
        mo.observe(el, { childList: true, characterData: true, subtree: true });
      })(els[i]);
    }
  }

  /* ═══════════════════════════════════════
     5. 频道卡光带
     ═══════════════════════════════════════ */
  function sweepChannel(el) {
    if (reduceMotion) return;
    el.classList.remove('sweep');
    void el.offsetWidth;
    el.classList.add('sweep');
  }

  /* ═══════════════════════════════════════
     6. 事件委托
     ═══════════════════════════════════════ */
  function bindEvents() {

    /* 涟漪：开关、小开关、按钮、频道卡 */
    document.addEventListener('pointerdown', function (e) {
      var t = e.target;
      if (!t || !t.closest) return;

      var host = t.closest('.switch, .small-switch, .btn, .mini');
      if (host) makeRipple(host, e);

      var ch = t.closest('.channel');
      if (ch) sweepChannel(ch);
    }, { passive: true });

    /* 页面切换 → 扫描线
       监听底栏点击（在 Alpine 处理完切页之后触发） */
    document.addEventListener('click', function (e) {
      var t = e.target;
      if (!t || !t.closest) return;
      if (t.closest('.bottom .nav')) {
        setTimeout(runScan, 30);
      }
    }, { passive: true });
  }

  /* ═══════════════════════════════════════
     7. 生命周期
     ═══════════════════════════════════════ */
  function boot() {
    /* 加总开关 class */
    var root = document.body;
    if (!root.classList.contains('fx-off') &&
        !root.classList.contains('fx-low')) {
      root.classList.add('fx-glow');   /* 默认开发光 */
    }

    startSnow();
    bindEvents();

    /* Alpine 渲染完 DOM 再去挂 MutationObserver
       最多等 6 秒（50 × 120ms），慢设备也够 */
    var tries = 0;
    var timer = setInterval(function () {
      tries++;
      var els = document.querySelectorAll('.stat strong');
      if (els.length) {
        clearInterval(timer);
        watchBump();
      } else if (tries > 50) {
        clearInterval(timer);
      }
    }, 120);
  }

  /* DOM 就绪即启动 */
  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }

  /* 窗口尺寸变化 → 重新生成雪花密度 */
  var resizeTimer = 0;
  window.addEventListener('resize', function () {
    if (resizeTimer) clearTimeout(resizeTimer);
    resizeTimer = setTimeout(function () {
      var layer = document.querySelector('.fx-snow');
      if (layer) layer.innerHTML = '';
      startSnow();
    }, 400);
  });

  /* ═══════════════════════════════════════
     对外 API（给后端 / 调试用）
     ═══════════════════════════════════════ */
  window.FX = {
    snow: function (on) {
      var layer = document.querySelector('.fx-snow');
      if (on === false) {
        if (layer) layer.style.display = 'none';
      } else {
        if (layer) layer.style.display = '';
        startSnow();
      }
    },

    glow: function (on) {
      document.body.classList.toggle('fx-glow', !!on);
    },

    all: function (on) {
      document.body.classList.toggle('fx-off', !on);
      if (on) {
        document.body.classList.add('fx-glow');
        startSnow();
      }
    },

    low: function (on) {
      document.body.classList.toggle('fx-low', !!on);
    },

    config: function (patch) {
      if (!patch) return CFG;
      for (var k in patch) {
        if (Object.prototype.hasOwnProperty.call(patch, k)) CFG[k] = patch[k];
      }
      return CFG;
    },

    scan: runScan,
    sweep: sweepChannel
  };

})();