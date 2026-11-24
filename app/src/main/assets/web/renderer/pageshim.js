/*
 * 小窗口（about.html / howto.html）的页面侧垫片。
 *
 * 原版这几个页面都是 Electron 的独立 BrowserWindow：页面只拿到 ipcRenderer，
 * 数据（版本、群二维码、怎么玩文案）由主进程通过 about-state / groups-state 喂进来，
 * 关窗口是 send('about-do','close')。
 *
 * 安卓这边没有主进程，于是把「主进程那半边」搬进页面：
 *   - require('electron') 返回一个假 ipcRenderer；
 *   - 版本 / 更新进度 / 自动更新开关 / 更新说明由 AndroidPage.aboutState() 从原生 OTA
 *     状态机同步取；状态变化由原生推 'about' 事件，和本地这份合并后再渲染；
 *   - check / install / auto / unskip 这些动作通过 post('ota', ...) 转给原生；
 *   - 关窗口 / 改高度 / 复制群号走 AndroidPage 的另外三个通道；
 *   - 群数据从 assets 里的 groups.default.json 现取，visible 按原版规则算（!full）。
 *
 * 页面本身不改动状态判断逻辑，只多引这一个脚本。
 */
(function () {
  'use strict';

  var Q = new URLSearchParams(location.search);
  var VERSION = Q.get('v') || '0.1.0';

  function bridge() {
    try { return window.AndroidPage || null; } catch (e) { return null; }
  }

  /** 通知安卓侧：close / size / copy */
  function post(k, v) {
    var b = bridge();
    if (!b) { console.log('pageshim: no bridge ' + k); return; }
    try { b.post(JSON.stringify({ k: k, v: v })); } catch (e) { console.log('pageshim post ' + e); }
  }

  // ---- 群数据：assets/web/groups.default.json ------------------------------

  var groupsCache = null;

  function loadGroups() {
    if (groupsCache) return Promise.resolve(groupsCache);
    return fetch('../groups.default.json', { cache: 'no-store' })
      .then(function (r) { return r.json(); })
      .then(function (j) {
        // 原版 groups.js 的 visible()：groups.filter(g => !g.full)
        j.visible = (j.groups || []).filter(function (g) { return g && !g.full; });
        groupsCache = j;
        return j;
      })
      .catch(function (e) {
        console.log('pageshim groups fail ' + e);
        return { v: -1, author: null, groups: [], visible: [], notice: '' };
      });
  }

  // ---- 手机版「怎么玩」：原版是 8 段鼠标演示视频，手机上没有鼠标也没有视频 --

  var HOWTO = [
    '单击：摸一下，影子会跟着晃',
    '双击：猫和人互相变（跟电脑版同一个快捷键）',
    '长按：弹出气泡菜单，动作／摸摸头／回主页／躲起来都在这儿',
    '按住拖动：拎起来，松手会自己落到地上；甩一把他会飞出去',
    '在小黑身上来回蹭：摸头，蹭久了会眯眼',
    '菜单里「更多」：设置、怎么玩、进群、关于小黑、退出小黑',
    '找不到小黑：点桌面上的图标，或者下拉通知栏里的「小黑」'
  ];

  var lastState = null;

  function aboutState() {
    return loadGroups().then(function (g) {
      var s = {};
      try {
        var b = bridge();
        s = JSON.parse((b && b.aboutState && b.aboutState()) || '{}');
      } catch (e) { console.log('pageshim aboutState ' + e); }
      // 版本/更新状态由原生 OTA 状态机给，群和怎么玩文案还是本地这份
      var out = {
        version: s.version || VERSION,
        tag: s.tag || '',
        demo: false,
        hasUpdater: true,
        auto: !!s.auto,
        update: s.update || { kind: 'latest' },
        notes: s.notes || '',
        howto: HOWTO,
        groups: g
      };
      lastState = out;
      return out;
    });
  }

  /** 「看看更新了什么」：原版由主进程开一张卡片，手机版直接在页面里叠一层 */
  function showNotes() {
    var old = document.getElementById('__otanotes');
    if (old) { old.parentNode.removeChild(old); return; }
    var box = document.createElement('div');
    box.id = '__otanotes';
    box.style.cssText = 'position:fixed;left:8px;right:8px;top:8px;bottom:8px;z-index:99;' +
      'background:#FFFDF7;border:2px solid #4A3424;border-radius:14px;padding:12px 16px;' +
      'overflow:auto;font:13px/1.7 "Microsoft YaHei UI","Microsoft YaHei",sans-serif;color:#3B2A1E;';
    var close = document.createElement('button');
    close.textContent = '关闭';
    close.style.cssText = 'float:right;border:1.5px solid #4A3424;border-radius:14px;' +
      'background:#fff;color:#3B2A1E;padding:2px 12px;font:13px inherit;cursor:pointer;';
    close.onclick = function () { box.parentNode.removeChild(box); };
    var pre = document.createElement('div');
    pre.style.cssText = 'white-space:pre-wrap;clear:both;padding-top:8px;';
    pre.textContent = (lastState && lastState.notes) || '这个版本没有写更新说明。';
    box.appendChild(close);
    box.appendChild(pre);
    document.body.appendChild(box);
  }

  // ---- 假 ipcRenderer ------------------------------------------------------

  var listeners = {};

  var ipcRenderer = {
    invoke: function (ch) {
      switch (ch) {
        case 'about-state': return aboutState();
        case 'groups-state': return loadGroups();
        case 'welcome-login-state': return Promise.resolve({ checked: false, pending: false, notice: '' });
        default: return Promise.resolve(null);
      }
    },
    send: function (ch, a, b) {
      switch (ch) {
        case 'about-do':
          if (a === 'close') post('close', 1);
          else if (a === 'show') showNotes();
          // check / install / unskip / auto-on / auto-off → 原生 OTA 状态机
          else post('ota', String(a));
          break;
        case 'welcome-do':
          if (a === 'close') post('close', 1);
          break;
        case 'about-size':
          console.log('pageshim about-size ' + a + ' inner=' + window.innerHeight);
          post('size', Math.ceil(Number(a) || 0));
          break;
        case 'welcome-size':
          console.log('pageshim welcome-size ' + a + ' inner=' + window.innerHeight);
          post('size', Math.ceil(Number(a) || 0));
          break;
        case 'welcome-login':
          break;
        case 'copy-text':
          post('copy', String(a == null ? '' : a));
          break;
        default:
          console.log('pageshim send ' + ch);
      }
    },
    on: function (ch, fn) { (listeners[ch] = listeners[ch] || []).push(fn); },
    once: function (ch, fn) { ipcRenderer.on(ch, fn); },
    removeAllListeners: function (ch) { delete listeners[ch]; }
  };

  // 原生 OTA 状态变化时推 'about'；只带 update/auto 等字段，这里和上次完整状态合并，
  // 免得群/怎么玩这些本地数据被推空的 payload 冲掉
  window.__pageEmit = function (ch, payload) {
    var p = payload;
    if (ch === 'about' && lastState) {
      p = Object.assign({}, lastState, payload);
      lastState = p;
    }
    (listeners[ch] || []).forEach(function (fn) {
      try { fn(null, p); } catch (e) { console.log('page handler ' + e); }
    });
  };

  window.require = function (name) {
    if (name === 'electron') return { ipcRenderer: ipcRenderer, contextBridge: null, shell: null };
    return {};
  };

  // 让没有桥的浏览器里也能跑（桌面调试用）
  if (!bridge()) console.log('pageshim: running without Android bridge');
})();
