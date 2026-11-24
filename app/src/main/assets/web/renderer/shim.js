// Electron 宿主兼容层：把原版依赖的 require / process / ipcRenderer 换成安卓桥。
// 加载顺序必须在所有 renderer 脚本之前。
(function () {
  'use strict';

  var HOST = window.XiaoheiHost || null;
  var listeners = {};

  function log() {
    try {
      var s = Array.prototype.map.call(arguments, function (a) {
        return typeof a === 'string' ? a : JSON.stringify(a);
      }).join(' ');
      if (HOST && HOST.log) HOST.log(s);
      else console.log(s);
    } catch (e) {}
  }

  // ---- 事件分发（Java -> JS）
  var ipcRenderer = {
    send: function (ch) {
      var args = Array.prototype.slice.call(arguments, 1);
      try {
        // 原版这三条是「主进程职责」，这里就地接管，省掉一次跨进程往返
        if (ch === 'menu') { window.__menuOpen(args[0]); return; }
        if (ch === 'menu2-pick') { window.__menuPick(args[0]); return; }
        if (ch === 'menu-close') { window.__menuClose(); return; }
        if (HOST && HOST.ipc) HOST.ipc(ch, JSON.stringify(args));
      } catch (e) { log('send fail ' + ch + ' ' + e); }
    },
    // 原版靠 sendSync 做「关窗前最后一次存盘」，这里走 JS 接口是同步的，语义刚好对上
    sendSync: function (ch, payload) {
      try {
        if (ch === 'save-sync' && HOST && HOST.saveState) HOST.saveState(JSON.stringify(payload));
      } catch (e) {}
      return null;
    },
    invoke: function (ch) {
      if (ch === 'load') {
        var raw = '{}';
        try { if (HOST && HOST.loadState) raw = HOST.loadState(); } catch (e) {}
        try { return Promise.resolve(JSON.parse(raw)); } catch (e) { return Promise.resolve(null); }
      }
      return Promise.resolve(null);
    },
    on: function (ch, fn) { (listeners[ch] = listeners[ch] || []).push(fn); },
    once: function (ch, fn) { (listeners[ch] = listeners[ch] || []).push(fn); },
    removeListener: function () {},
    removeAllListeners: function () {},
    postMessage: function () {}
  };

  window.__emit = function (ch) {
    var args = Array.prototype.slice.call(arguments, 1);
    (listeners[ch] || []).forEach(function (fn) {
      try { fn.apply(null, [{}].concat(args)); } catch (e) { log('emit error ' + ch + ' ' + e); }
    });
  };

  // ---- 合成指针事件
  // menu2 是页面里的 DOM 面板，它同时监听 pointer* 和 mouse* 两套事件，
  // 所以一次触摸要按浏览器的真实顺序补全：pointerdown -> mousedown -> pointerup -> mouseup -> click
  var lastOver = null;

  // menu2 的面板挂在 shadow root 里，document.elementFromPoint 只会返回宿主，
  // 事件打到宿主上不会进影子树，所以这里要一层层往里钻。
  function deepHit(x, y) {
    var el = document.elementFromPoint(x, y);
    var guard = 0;
    while (el && el.shadowRoot && guard++ < 12) {
      var inner = null;
      try { inner = el.shadowRoot.elementFromPoint(x, y); } catch (e) { inner = null; }
      if (!inner || inner === el) break;
      el = inner;
    }
    return el;
  }

  function fire(type, Ctor, x, y, extra) {
    var el = deepHit(x, y) || document.body;
    var opt = {
      clientX: x, clientY: y, screenX: x, screenY: y,
      bubbles: true, cancelable: true, composed: true, view: window, button: 0, buttons: 0
    };
    if (extra) for (var k in extra) opt[k] = extra[k];
    try { el.dispatchEvent(new Ctor(type, opt)); } catch (e) { log('synth ' + type + ' fail ' + e); }
    return el;
  }
  function pe(type, x, y, buttons) {
    fire(type, window.PointerEvent || MouseEvent, x, y,
      { pointerId: 1, pointerType: 'touch', isPrimary: true, buttons: buttons, pressure: buttons ? 0.5 : 0 });
  }
  function me(type, x, y, buttons) { fire(type, MouseEvent, x, y, { buttons: buttons }); }

  window.__synth = function (type, x, y) {
    try {
      if (type === 'down') {
        if (window.__DBG_HIT) {
          var d = deepHit(x, y);
          log('hit@' + x + ',' + y + ' -> ' + (d ? (d.tagName + '.' + (d.className || '')) : 'none'));
        }
        pe('pointerdown', x, y, 1); me('mousedown', x, y, 1);
      } else if (type === 'move') {
        pe('pointermove', x, y, 1); me('mousemove', x, y, 1);
        // 悬停高亮：目标变了才补 over/enter，别每帧都刷
        var el = deepHit(x, y);
        if (el && el !== lastOver) {
          if (lastOver) { pe('pointerout', x, y, 1); me('mouseout', x, y, 1); }
          pe('pointerover', x, y, 1); me('mouseover', x, y, 1);
          lastOver = el;
        }
      } else if (type === 'up') {
        pe('pointerup', x, y, 0); me('mouseup', x, y, 0); me('click', x, y, 0);
      }
    } catch (e) { log('synth fail ' + e); }
  };

  // ---- menu2 协议（原版在主进程里做的三件事）
  var MENU_OPT = {
    dev: false, keys: {}, dblCtrl: true, finderOn: true, update: null, wish: false, trash: []
  };

  function hostCall(ch, payload) {
    try { if (HOST && HOST.ipc) HOST.ipc(ch, payload); } catch (e) {}
  }

  window.__menuOpen = function (st) {
    // 菜单打开时命中区要扩到全屏，否则 DOM 面板点不到
    hostCall('menu-ui', '[1]');
    if (!window.MenuModel || typeof Menu2 === 'undefined' || !Menu2.onMsg) {
      log('menu2 not ready, fallback');
      hostCall('menu-fallback', '[]');
      return;
    }
    try {
      var built = window.MenuModel.build(st || {}, MENU_OPT);
      markNoWin(built.data);
      relabelFind(built.data, st || {});
      relabelHide(built.data);
      window.__menuState = { ids: built.ids, dyn: built.dyn };
      window.__emit('menu-state', true);
      Menu2.onMsg({ t: 'open', data: built.data });
      fitStickers(4);
      log('menu2 open, ids=' + (built.ids && built.ids.size));
      if (DBG_CELLS) {
        var n = 0;
        var t = setInterval(function () {
          dumpCells();
          if (++n >= 6) clearInterval(t);
        }, 3000);
      }
    } catch (e) {
      log('menu2 build fail: ' + e);
      hostCall('menu-fallback', '[]');
    }
  };

  // 原版靠主进程枚举桌面窗口（World.wins）来决定宠物能趴在哪。
  // Android 没有跨应用窗口信息，这个列表永远是空的，于是「上窗口 / 趴窗口」这类动作
  // 找不到落点：指令发出去，页面判定无条件执行，就静默结束了。
  // 与其让人以为点坏了，不如把这几项置灰并写明原因——等以后注入合成窗口再放开。
  var NO_WIN_ACTS = { jump: 1, daze: 1, perchtry: 1, hxguard: 1 };
  function markNoWin(data) {
    try {
      if (typeof World !== 'undefined' && World.wins && World.wins.length) return;
      function tag(c) {
        if (!c || !c.id || !NO_WIN_ACTS[c.id]) return;
        c.dis = true;
        // 二级格子只有 140px*--s 宽，白药丸放 4 个字刚好；原来「手机上没有窗口」6 个字
        // 会把药丸撑出格子、压到隔壁格子上。
        c.sub = '暂无窗口';
      }
      (data.main || []).forEach(tag);
      var subs = data.subs || {};
      Object.keys(subs).forEach(function (k) {
        (subs[k].groups || []).forEach(function (g) { (g.cells || []).forEach(tag); });
      });
    } catch (e) {}
  }

  // 「找文件」是 Windows 资源管理器的搜索窗口，手机上没有任何对应物。
  // 位置保留、手感不变，功能换成「回到主页」（和按 Home 键一个效果）。
  // 图标跟着换：原版图标集里 hxback 就是「回到任务栏」用的那个回退箭头。
  function relabelFind(data, st) {
    try {
      var back = st.form === 'cat' ? 'hxback' : (st.hair === 'w' ? 'hw_hxback' : 'hm_hxback');
      function fix(c) {
        if (!c || c.id !== 'find') return;
        c.nm = '回主页';
        c.icon = back;
        delete c.hint;
      }
      (data.main || []).forEach(fix);
      var subs = data.subs || {};
      Object.keys(subs).forEach(function (k) {
        (subs[k].groups || []).forEach(function (g) { (g.cells || []).forEach(fix); });
      });
    } catch (e) { log('relabel fail ' + e); }
  }

  // 「躲起来」原本是原版 Electron 的「把窗口移出屏幕、托盘召回」，移植时被简化成了退出。
  // 现在这一格改成「大脸猫」：整块屏幕当猫脸，只留两只眼睛（Kotlin 侧 openFace()）。
  // 名字必须 3 字，理由同 relabelFind；图标换成 hxhead（正脸猫头）。
  function relabelHide(data) {
    try {
      function fix(c) {
        if (!c || c.id !== 'hide') return;
        c.nm = '大脸猫';
        c.icon = 'hxhead';
        delete c.hint;
      }
      (data.main || []).forEach(fix);
      var subs = data.subs || {};
      Object.keys(subs).forEach(function (k) {
        (subs[k].groups || []).forEach(function (g) { (g.cells || []).forEach(fix); });
      });
    } catch (e) { log('relabelHide fail ' + e); }
  }

  // 主圈贴纸的胶囊宽度是 menu2 照 Windows 设计稿算死的（按旋转后的包围盒写内联 width），
  // 落在这台机器上是「PingFang SC → 系统黑体」的回落，同样字号排出来比设计稿宽一点，
  // 三字名（变成人、回主页）就会顶出彩色底、贴着白边。不动混淆的 menu2.js：
  // 把这些胶囊的内联 width 改成 auto、左右补 8px 内边距，让每张刚好包住自己的字。
  // 只有「没有原版贴纸素材」的两三项走这条路（.mk.css）；手绘贴纸的文字在 PNG 里，改不了宽度。
  function fitStickers(times) {
    try {
      var host = document.getElementById('xh-menu2');
      var root = host && host.shadowRoot;
      if (!root) {
        if (times > 0) setTimeout(function () { fitStickers(times - 1); }, 120);
        return;
      }
      // 键帽（双击／按住 1 秒／Ctrl Ctrl／中键）是 Windows 版的操作说明，手机上没意义。
      // menu2.css 里已经写了 display:none，但它走 http://pet.local 会被 WebView 缓存，
      // 重新装 APK 也不一定刷新，所以这里往 shadowRoot 末尾补一条 !important 盖住。
      if (!root.querySelector('#xh-fix')) {
        var st = document.createElement('style');
        st.id = 'xh-fix';
        st.textContent = '.mk .chip,.mk .cs .ks,.sheet .it .kcs{display:none!important}' +
          '.sheet .it small{font-size:.74em!important}';
        root.appendChild(st);
      }
      var list = root.querySelectorAll('.mk.css .cs');
      // 面板是打开后才一点点建出来的（还要等 menu2.css 的 link onload 才量尺寸），
      // 这一轮还没见到贴纸就过一会儿再来。
      if (!list.length && times > 0) { setTimeout(function () { fitStickers(times - 1); }, 120); return; }
      var n = 0;
      for (var i = 0; i < list.length; i++) {
        var el = list[i];
        if (el.__fit) continue;
        el.__fit = 1;
        // 不留 min-width：设计稿那个宽度是按 Windows 字体量的，字号一样但字宽不同，
        // 常常是「右边空一大截」。宽度交给内容 + 恒定内边距，才能每张都刚好包住字。
        el.style.width = 'auto';
        el.style.padding = '0 calc(8px*var(--s))';
        n++;
      }
      if (n) {
        log('fit stickers ' + n);
        // 撑长后相邻贴纸可能互相压住一点点，重新排一次圆心
        if (typeof Menu2.relayout === 'function') Menu2.relayout();
      }
    } catch (e) { log('fit stickers fail ' + e); }
  }

  // 调试开关：把菜单里每个格子的屏幕矩形打出来（真机上没有 DOM 检查器，
  // 靠它定位该点哪儿；正常用不到，留 false）。
  var DBG_CELLS = false;
  function dumpCells() {
    try {
      // menu2 的面板不带 class，靠内联 style 摆位；直接把所有有尺寸的元素列出来，
      // IMG 的 src 尾名正好是图标 id（= 格子 id），据此就能反推每个格子该点哪儿。
      var out = [];
      function walk(root, depth) {
        if (depth > 8) return;
        var all = root.querySelectorAll('*');
        for (var i = 0; i < all.length; i++) {
          var e = all[i], r = e.getBoundingClientRect();
          if (r.width >= 4 && r.height >= 4) {
            var src = '';
            if (e.tagName === 'IMG' && e.src) src = e.src.split('/').pop().replace(/\.(png|webp|gif)/, '');
            out.push(e.tagName + (e.className ? '.' + String(e.className).trim().split(/\s+/)[0] : '') +
              '|' + Math.round(r.left + r.width / 2) + ',' + Math.round(r.top + r.height / 2) +
              '|' + Math.round(r.width) + 'x' + Math.round(r.height) + (src ? '|' + src : ''));
          }
          if (e.shadowRoot) walk(e.shadowRoot, depth + 1);
        }
      }
      walk(document.body, 0);
      for (var k = 0; k < out.length; k += 12) {
        log('el' + k + ' ' + out.slice(k, k + 12).join('  '));
      }
    } catch (e) { log('dumpCells fail ' + e); }
  }

  window.__menuPick = function (id) {
    try {
      if (id === 'full') { hostCall('menu-fallback', '[]'); return; }
      var map = (typeof Actions !== 'undefined' && Actions.menuCmds) ? Actions.menuCmds() : {};
      var cmd = map[id];
      if (cmd) {
        log('pick ' + id + ' -> ' + cmd);
        window.__emit('cmd', cmd);
        armRetry(cmd);
      } else {
        // 主进程动作（设置/关于/收起/退出…）交给壳层
        log('pick ' + id + ' -> shell');
        hostCall('shell', JSON.stringify([id]));
      }
      window.__emit('menu-state', false);
      hostCall('menu-ui', '[0]');
    } catch (e) { log('menu pick fail ' + e); }
  };

  // 动作指令会被页面丢进 solo 队列：宠物正在走、正在播别的动作、
  // 或者某个图集还在加载（Android 上单张大图要 2-8 秒），这次就会被判超时丢弃。
  // 原版有主进程兜底重发，这里没有，所以补一层：队列还挂着就再推一次。
  function armRetry(cmd) {
    var tries = 0;
    function check() {
      if (tries >= 3) return;
      tries++;
      setTimeout(function () {
        try {
          if (typeof Pet === 'undefined') return;
          var q = Pet.queued;
          if (q && q.c === cmd) {
            log('retry ' + cmd + ' #' + tries + ' ' + petProbe());
            window.__emit('cmd', cmd);
            check();
          }
        } catch (e) {}
      }, 2500);
    }
    check();
  }

  // 诊断：动作点了没反应时，看宠物到底卡在什么状态
  function petProbe() {
    try {
      if (typeof Pet === 'undefined') return 'Pet=n/a';
      var q = Pet.queued;
      return JSON.stringify({
        mode: Pet.mode,
        state: Pet.state,
        queued: q ? { c: q.c, w: q.w, go: q.go, age: Math.round((Pet.clock - q.at) * 100) / 100 } : null,
        sleeping: !!Pet.sleeping,
        dragging: !!Pet.dragging,
        hidden: document.hidden,
        perch: Pet.perch ? Pet.perch.id : null
      });
    } catch (e) { return 'probe err ' + e; }
  }
  window.__petProbe = petProbe;

  window.__menuClose = function () {
    try { if (typeof Menu2 !== 'undefined' && Menu2.onMsg) Menu2.onMsg({ t: 'close' }); } catch (e) {}
    window.__emit('menu-state', false);
    hostCall('menu-ui', '[0]');
  };

  // 调试入口：给页面发 cmd 时，'@js:<代码>' 直接求值并打回日志。
  // 平时 PetService 是 exported=false 的，adb 进不来。要调试就先在 Manifest 里临时
  // 把 PetService 的 exported 改成 true，编译安装后：
  //   adb shell am start-service -a com.deepfish.xiaohei.CMD \
  //     -n com.deepfish.xiaohei/.PetService --es cmd "@js:JSON.stringify(Pet.state)"
  // 调完记得改回去。
  var rawEmit = window.__emit;
  window.__emit = function (ch) {
    var args = Array.prototype.slice.call(arguments, 1);
    if (ch === 'cmd' && typeof args[0] === 'string' && args[0].indexOf('@js:') === 0) {
      try { log('dbg ' + JSON.stringify(eval(args[0].slice(4)))); }
      catch (e) { log('dbg err ' + e); }
      return;
    }
    return rawEmit.apply(null, arguments);
  };

  // 描述某个屏幕点上的元素，用来排查「菜单点不动」
  window.__dbg = function (x, y) {
    var el = document.elementFromPoint(x, y);
    var out = [];
    var n = el, guard = 0;
    while (n && guard++ < 8) {
      out.push((n.tagName || '?') + (n.id ? '#' + n.id : '') + (n.className && typeof n.className === 'string' ? '.' + n.className.split(' ').join('.') : ''));
      n = n.parentElement;
    }
    return { at: x + ',' + y, chain: out.join(' < '), shadow: !!(el && el.shadowRoot), menu2: typeof Menu2 !== 'undefined' };
  };

  // 让壳层可以在卸载前「立刻存一次」，复用原版的 beforeunload -> sendSync('save-sync') 路径
  window.__saveNow = function () {
    try { window.dispatchEvent(new Event('beforeunload')); } catch (e) {}
  };

  // 页面里若用到 pointer capture，合成事件会抛错，直接降级成空实现
  try {
    Element.prototype.setPointerCapture = function () {};
    Element.prototype.releasePointerCapture = function () {};
  } catch (e) {}

  // ---- Node 兼容
  window.process = {
    platform: 'win32',
    argv: ['xiaohei'],
    env: {},
    versions: { electron: '28.3.3', node: '18.17.0', chrome: '120.0.0.0' },
    cwd: function () { return '/app'; },
    on: function () {},
    listeners: function () { return []; }
  };

  window.__dirname = '/app/renderer';

  window.require = function (name) {
    if (name === 'electron') {
      return {
        ipcRenderer: ipcRenderer,
        ipcMain: { on: function () {}, handle: function () {}, removeHandler: function () {} },
        app: { getPath: function () { return '/app'; }, getVersion: function () { return '1.0.43'; } },
        shell: { openExternal: function () {} },
        nativeImage: {}
      };
    }
    if (name === 'fs') {
      return {
        readFileSync: function (p) {
          var key = normKey(String(p));
          if (window.__FILES && Object.prototype.hasOwnProperty.call(window.__FILES, key)) return window.__FILES[key];
          log('fs miss ' + p + ' -> ' + key);
          throw new Error('ENOENT: no such file, ' + p);
        },
        existsSync: function (p) {
          var key = normKey(String(p));
          return !!(window.__FILES && Object.prototype.hasOwnProperty.call(window.__FILES, key));
        },
        writeFileSync: function () {},
        readdirSync: function () { return []; },
        promises: {}
      };
    }
    if (name === 'path') {
      return {
        sep: '/',
        join: function () { return joinArgs(arguments); },
        resolve: function () { return joinArgs(arguments); },
        dirname: function (p) { var i = String(p).lastIndexOf('/'); return i <= 0 ? '/' : String(p).slice(0, i); },
        basename: function (p) { return String(p).split('/').pop(); },
        extname: function (p) { var b = String(p).split('/').pop(); var i = b.lastIndexOf('.'); return i <= 0 ? '' : b.slice(i); }
      };
    }
    // menu2/model.js 里 require 的是同目录的兄弟模块，页面里它们已经是全局了。
    // 注意 actions.js / keymap.js 用的是顶层 const，不挂到 window 上，只能按名字取。
    if (/\/keymap$/.test(name)) { try { return Keymap; } catch (e) { return {}; } }
    if (/\/actions$/.test(name)) { try { return Actions; } catch (e) { return {}; } }
    return {};
  };

  function joinArgs(args) {
    var parts = Array.prototype.slice.call(args).filter(function (x) { return x !== undefined && x !== null && x !== ''; });
    var out = [];
    parts.forEach(function (seg, idx) {
      String(seg).split('/').forEach(function (piece) {
        if (!piece || piece === '.') return;
        if (piece === '..') { out.pop(); return; }
        out.push(piece);
      });
    });
    return '/' + out.join('/');
  }

  // /app/renderer/../assets/v2/rig.json -> v2/rig.json
  function normKey(p) {
    var s = p.replace(/\\/g, '/');
    var i = s.lastIndexOf('/assets/');
    if (i >= 0) return s.slice(i + 8);
    var parts = s.split('/').filter(Boolean).slice(-2);
    return parts.join('/');
  }

  if (!window.Buffer) {
    window.Buffer = {
      alloc: function () { return new Uint8Array(0); },
      from: function () { return new Uint8Array(0); },
      concat: function () { return new Uint8Array(0); },
      isBuffer: function () { return false; }
    };
  }

  window.addEventListener('error', function (e) {
    log('JS ERROR ' + (e.message || '') + ' @ ' + (e.filename || '') + ':' + (e.lineno || 0));
  });
  window.addEventListener('unhandledrejection', function (e) {
    log('JS REJECT ' + (e.reason && (e.reason.stack || e.reason.message) || e.reason));
  });

  log('shim ready, files=' + (window.__FILES ? Object.keys(window.__FILES).length : 0));
})();
