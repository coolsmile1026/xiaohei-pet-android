// 大脸猫：整块屏幕当猫脸，只画两只眼睛，不要鼻子/嘴/脸轮廓/耳朵。
//
// 全部用 Canvas 路径画，不加载任何图片。眼睛直接按当前屏幕分辨率定尺寸：
// 单眼宽 ≈ 屏宽 38%（高宽比沿用原版 112/92），同时受屏高 74% 约束；
// 两眼中心落在屏幕正中，整张屏幕就是脸，不再照原版 287×330 小框缩放。
//
// 表情做成连续量而不是帧号：openL/R 眼睑开合、arcL/R 笑眼弧、pupil 瞳孔大小、
// slant 眼睑斜度、tilt 两眼高低、biasX/Y 视线偏移、heart/spiral 瞳孔形状权重。
// 当前姿态每帧向目标缓动；形状用权重交叉淡化、睁眼/弧眼也交叉淡化，
// 所以任意两个表情之间都是连贯过渡，不会再有「睁眼↔弧线」「黑瞳↔红心」这类硬切。
(function () {
  'use strict';

  // ---- 帧内坐标（只沿用眼睛比例，整体按屏幕缩放）
  var EYE_L = { x: 89.5, y: 116.5 };
  var EYE_R = { x: 204.0, y: 116.0 };
  var EYE_MID_X = (EYE_L.x + EYE_R.x) / 2;
  var EYE_MID_Y = (EYE_L.y + EYE_R.y) / 2;
  var ERX = 46, ERY = 56;            // 眼白椭圆半径（不含描边）
  var LW = 5.8;                      // 描边粗细（帧内单位）

  var WHITE = '#fbf9ef';
  var BROWN = '#2e1c12';
  var PUPIL = '#0b0908';
  var HEART_RED = '#e11d2a';         // 爱心眼：红色瞳孔（黑底上才够醒目）
  var ARC_FULL = 3.2;                // arc 到这个强度算「完全弧眼」，用于睁眼/弧眼交叉淡化归一化

  var c = document.getElementById('c');
  var g = c.getContext('2d');
  var W = 0, H = 0, dpr = 1, S = 1, DX = 0, DY = 0;

  function log(s) { try { console.log(s); } catch (e) {} }
  function clamp01(v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
  function px(fx) { return DX + fx * S; }
  function py(fy) { return DY + fy * S; }

  function resize() {
    // 至少 2x 超采样：真机 dpr 常是 1，1:1 绘制时椭圆边缘只有 1px 过渡像素，
    // 叠到深色背景上就成了可见锯齿；canvas 内部多画一倍像素再交给浏览器缩小，边缘更平滑。
    dpr = Math.max(2, Math.min(window.devicePixelRatio || 1, 3));
    W = window.innerWidth;
    H = window.innerHeight;
    c.width = Math.round(W * dpr);
    c.height = Math.round(H * dpr);
    c.style.width = W + 'px';
    c.style.height = H + 'px';
    g.setTransform(dpr, 0, 0, dpr, 0, 0);

    // 眼睛按整屏定尺寸：单眼宽 38% 屏宽，高不超过 74% 屏高；
    // 两眼整体居中，屏幕就是脸，不再受原版小框和鼻子的位置牵制。
    S = Math.min(W * 0.38 / (ERX * 2), H * 0.74 / (ERY * 2));
    DX = W / 2 - EYE_MID_X * S;
    DY = H / 2 - EYE_MID_Y * S;
  }
  window.addEventListener('resize', resize);
  resize();

  // ---- 表情目标：缺的字段一律补默认值，POSE 表只写差异
  function P(o) {
    var open = o.open != null ? o.open : 1;
    var arc = o.arc != null ? o.arc : 0;
    return {
      openL: o.openL != null ? o.openL : open,
      openR: o.openR != null ? o.openR : open,
      arcL: o.arcL != null ? o.arcL : arc,
      arcR: o.arcR != null ? o.arcR : arc,
      pupil: o.pupil != null ? o.pupil : 1,
      slant: o.slant || 0,
      tilt: o.tilt || 0,
      biasX: o.biasX || 0,
      biasY: o.biasY || 0,
      heart: o.heart || 0,           // 红心权重 0~1（与圆瞳交叉淡化）
      spiral: o.spiral || 0,         // 蚊香权重 0~1（与圆瞳交叉淡化）
      ease: o.ease || 0
    };
  }

  // ease 越大切得越快：眨眼 0.55，困/睡 0.06~0.10，日常表情 0.14~0.30。
  var POSE = {
    surprised: P({ open: 1.00, pupil: 1.08 }),
    half:      P({ open: 0.56, pupil: 1.00 }),
    meh:       P({ open: 0.36, pupil: 0.94, slant: -0.10 }),
    curious:   P({ open: 1.00, pupil: 1.04, tilt: 0.18, biasX: 0.32, ease: 0.22 }),
    angry:     P({ open: 0.86, pupil: 0.70, slant: 0.32, biasX: 0.12, ease: 0.24 }),
    sad:       P({ open: 0.58, pupil: 0.92, slant: -0.34, biasY: 0.18, ease: 0.18 }),
    sleepy:    P({ open: 0.24, pupil: 0.78, slant: -0.16, tilt: -0.10, ease: 0.08 }),
    yawn:      P({ open: 0.16, pupil: 0.72, slant: -0.22, ease: 0.10 }),
    dizzy:     P({ open: 1.00, pupil: 0.90, spiral: 1, ease: 0.18 }),
    love:      P({ open: 1.00, pupil: 0.96, heart: 1, ease: 0.30 }),
    smile:     P({ open: 0.10, arc: 5.0, ease: 0.18 }),  // 温柔微笑（闭眼弧，比 happy 浅）
    petted:    P({ open: 0.26, pupil: 0.88, ease: 0.30 }),
    blinkDown: P({ open: 0.08, pupil: 1.00, ease: 0.55 }),
    blink:     P({ open: 0.10, arc: 3.2, ease: 0.55 }),
    wink:      P({ arcL: 6.2, openL: 0.10, openR: 1.00, tilt: -0.06, ease: 0.35 }),
    happy:     P({ open: 0.10, arc: 7.5, ease: 0.26 }),
    closed:    P({ open: 0.10, arc: 4.0, ease: 0.06 })
  };

  var LERP_KEYS = ['openL', 'openR', 'arcL', 'arcR', 'pupil', 'slant', 'tilt', 'biasX', 'biasY', 'heart', 'spiral'];
  var cur = P({});

  // ---- 瞳孔跟随手指；没人碰时自己慢慢游移，别像两颗钉死的洞
  var gazeX = 0, gazeY = 0, gazeTX = 0, gazeTY = 0;
  var sleepK = 0;                       // 睡眠程度 0~1，用于呼吸幅度连续过渡

  // tilt：一只眼高、一只眼低；左 -1 / 右 +1
  function eyeY(e) { return e.y + cur.tilt * (e === EYE_L ? -14 : 14); }
  function dirOf(e) { return e === EYE_L ? 1 : -1; }

  // 心形瞳孔：x 是上沿中心，y+h 是尖底，画完由调用方 clip 到眼白里
  function heartPath(x, y, w, h) {
    g.beginPath();
    g.moveTo(x, y + h * 0.30);
    g.bezierCurveTo(x - w * 0.06, y + h * 0.04, x - w * 0.58, y - h * 0.06, x - w * 0.58, y + h * 0.34);
    g.bezierCurveTo(x - w * 0.58, y + h * 0.76, x - w * 0.14, y + h * 0.94, x, y + h);
    g.bezierCurveTo(x + w * 0.14, y + h * 0.94, x + w * 0.58, y + h * 0.76, x + w * 0.58, y + h * 0.34);
    g.bezierCurveTo(x + w * 0.58, y - h * 0.06, x + w * 0.06, y + h * 0.04, x, y + h * 0.30);
    g.closePath();
  }

  // 心跳节奏：一个周期里「咚—哒——」两下，然后停一拍。
  // 返回 1 附近的缩放系数，峰值约 1.16，爱心随之放大回弹。
  function heartbeat(ts) {
    var period = 900;                       // 一次心跳 ~0.9 秒
    var p = (ts % period) / period;         // 0..1
    function bump(at, w, amp) {
      var d = (p - at) / w;
      return amp * Math.exp(-d * d);
    }
    return 1 + bump(0.10, 0.055, 0.16) + bump(0.27, 0.070, 0.10);
  }

  // 蚊香眼：2.4 圈，随时间转；只描边，黑猫脸上才看得清
  function spiralPupil(x, y, r, ts) {
    if (r < 2) return;
    var steps = 64, turns = 2.4, rot = ts * 0.0035;
    g.beginPath();
    for (var i = 0; i <= steps; i++) {
      var t = i / steps;
      var a = rot + t * turns * Math.PI * 2;
      var rr = r * t;
      var xx = x + Math.cos(a) * rr;
      var yy = y + Math.sin(a) * rr;
      if (i === 0) g.moveTo(xx, yy); else g.lineTo(xx, yy);
    }
    g.lineWidth = Math.max(2, LW * S * 0.92);
    g.lineCap = 'round';
    g.strokeStyle = PUPIL;
    g.stroke();
  }

  // ---------------------------------------------------------------- 画眼睛
  // open 是 1 = 全睁、0 = 全闭。只画眼睑线以下的区域，
  // 「半睁」露出下半截眼睛，不用黑色去盖；slant 让眼睑斜着压下来。
  function drawEye(e, open, pupilK, ts) {
    var cx = px(e.x), cy = py(eyeY(e));
    var rx = ERX * S, ry = ERY * S;
    var lw = LW * S;
    var slope = dirOf(e) * cur.slant * ry * 0.5 / (rx || 1);
    var lidBase = cy + ry - 2 * ry * open;
    function lidAt(x) { return lidBase + (x - cx) * slope; }

    var left = cx - rx - lw * 3, right = cx + rx + lw * 3;
    g.save();
    g.beginPath();
    g.moveTo(left, lidAt(left));
    g.lineTo(right, lidAt(right));
    g.lineTo(right, cy + ry + lw * 6);
    g.lineTo(left, cy + ry + lw * 6);
    g.closePath();
    g.clip();

    // 眼白 + 棕色外圈：两层同心 fill，不再用 stroke。
    // 旧画法里白色 fill 到 rx、棕色 stroke 的外缘也正好到 rx，两条 AA 边完全重合，
    // 白色的半透明边缘像素会从棕色外缘渗出来，在黑底上形成一圈断续白点/锯齿。
    // 现在棕色整块打底、白色只填到内圈 (rx - lw)，
    // 棕色外缘只剩「棕→黑」的普通 AA，白点就没了；环宽仍是 lw，视觉尺寸不变。
    g.beginPath();
    g.ellipse(cx, cy, rx, ry, 0, 0, Math.PI * 2);
    g.fillStyle = BROWN;
    g.fill();

    g.beginPath();
    g.ellipse(cx, cy, rx - lw, ry - lw, 0, 0, Math.PI * 2);
    g.fillStyle = WHITE;
    g.fill();

    // 瞳孔：圆 / 红心 / 蚊香按权重交叉淡化，任意两形状之间都是渐变而非硬切
    if (pupilK > 0) {
      g.save();
      g.beginPath();
      g.ellipse(cx, cy, rx - lw * 0.5, ry - lw * 0.5, 0, 0, Math.PI * 2);
      g.clip();
      var gx = cx + (gazeX + cur.biasX) * rx * 0.12;
      var gy = cy + ry * 0.02 + (gazeY + cur.biasY) * ry * 0.10;
      var prx = rx * 0.78 * pupilK, pry = ry * 0.79 * pupilK;

      var hw = cur.heart, sw = cur.spiral;
      var tot = hw + sw;
      if (tot > 1) { hw /= tot; sw /= tot; tot = 1; }
      var rw = Math.max(0, 1 - tot);       // 圆瞳权重 = 剩下那份

      if (rw > 0.001) {
        g.globalAlpha = rw;
        g.beginPath();
        g.ellipse(gx, gy, prx, pry, 0, 0, Math.PI * 2);
        g.fillStyle = PUPIL;
        g.fill();
      }
      if (hw > 0.001) {
        g.globalAlpha = hw;
        // 红色爱心 + 心跳缩放：以瞳孔中心为轴「咚—哒」放大回弹
        var hb = heartbeat(ts);
        var hwid = prx * 0.92 * hb, hhei = pry * 1.08 * hb;
        g.beginPath();
        heartPath(gx, gy - hhei * 0.55, hwid, hhei);
        g.fillStyle = HEART_RED;
        g.fill();
      }
      if (sw > 0.001) {
        g.globalAlpha = sw;
        spiralPupil(gx, gy, Math.min(prx, pry) * 0.92, ts);
      }
      // 高光：圆瞳和红心都有，蚊香不画（描边螺旋上点高光会脏）
      var glow = rw + hw;
      if (glow > 0.001) {
        g.globalAlpha = Math.min(1, glow);
        g.beginPath();
        g.ellipse(gx - prx * 0.28, gy - pry * 0.26, prx * 0.072, pry * 0.052, -0.35, 0, Math.PI * 2);
        g.fillStyle = 'rgba(255,255,255,.82)';
        g.fill();
      }
      g.restore();
    }

    g.restore();   // 对应外层「眼睑线以下」的 clip

    // 眼睑线：随压眼皮 / 皱眉程度连续渐显，不再到阈值才突然冒出来。
    // 用 *= 叠在当前 globalAlpha 上，保留弧眼过渡时整只眼的淡出。
    var lineA = Math.max(
      clamp01((0.62 - open) / 0.30),
      clamp01((Math.abs(cur.slant) - 0.015) / 0.12)
    );
    if (lineA > 0.005) {
      g.save();
      g.globalAlpha *= lineA;
      var sag = ry * 0.10;
      g.beginPath();
      g.moveTo(cx - rx * 1.01, lidAt(cx - rx * 1.01) - sag);
      g.quadraticCurveTo(cx, lidAt(cx) + sag * 1.6, cx + rx * 1.01, lidAt(cx + rx * 1.01) - sag);
      g.lineWidth = lw * 1.08;
      g.lineCap = 'round';
      g.strokeStyle = BROWN;
      g.stroke();
      g.restore();
    }
  }

  // 闭眼 / 眯眼笑：一条向上凸的粗弧；slant 让它带点情绪
  function drawArcEye(e, curve) {
    var cx = px(e.x), cy = py(eyeY(e));
    var half = ERX * S * 1.02;
    var lw = LW * S;
    var lift = dirOf(e) * cur.slant * half * 0.32;
    var sag = curve * S;
    g.beginPath();
    g.moveTo(cx - half, cy + sag * 0.5 + lift);
    g.quadraticCurveTo(cx, cy - sag * 1.4, cx + half, cy + sag * 0.5 - lift);
    g.lineWidth = lw * 1.12;
    g.lineCap = 'round';
    g.strokeStyle = BROWN;
    g.stroke();
  }

  // 睁眼 ↔ 弧眼交叉淡化：arc 越强弧眼越实、睁眼越淡，两者连续互替。
  // arc=0 时只有睁眼；arc>=ARC_FULL 时只有弧眼；中间两个都半透明叠着，
  // 配合 arc 类表情把 open 也压到 0.10（眼睛先闭上），过渡就显得连贯。
  function drawMorphEye(e, arc, open, ts) {
    var mix = Math.min(1, arc / ARC_FULL);
    if (mix < 0.995) {
      g.save();
      g.globalAlpha = 1 - mix;
      drawEye(e, open, cur.pupil, ts);
      g.restore();
    }
    if (mix > 0.005) {
      g.save();
      g.globalAlpha = mix;
      drawArcEye(e, Math.max(arc, 2.0));
      g.restore();
    }
  }

  // ---------------------------------------------------------------- 表情机
  var base = 'surprised';
  var seq = null, seqAt = 0, seqIdx = 0;
  var lastTouch = 0;
  var now = function () { return performance.now(); };
  var t0 = now();
  var rnd = function (a, b) { return a + Math.random() * (b - a); };
  var pick = function (a) { return a[(Math.random() * a.length) | 0]; };

  var nextBlink = t0 + rnd(2400, 5200);
  var nextIdle = t0 + rnd(4200, 8600);

  function play(list) { seq = list; seqIdx = 0; seqAt = now(); }
  function mood() { return seq ? seq[seqIdx].m : base; }

  // 被摸反馈：点一下切到一个表情并停住，不再连 flash（旧五连切里
  // wink/happy/smile 都是闭眼弧，连着闪就是「眯眯眼又换成别的」）。
  //
  // 点击池现在是 POSE 里所有能独立展示的表情，不再固定那三四个：
  // 排除 blinkDown / blink（眨眼序列的两个过渡帧，停住像卡在眨眼）和
  // closed（90 秒没人碰才进的睡眠态，不属于点击可切的表情）。
  //
  // 用「洗牌袋」发牌：一轮打乱后每个表情各出现一次，发完再洗，
  // 所以短期内绝不会连着抽到同一个；另外按视觉分组把相邻两次错开，
  // 避免 smile→happy 这种两个弧眼连着出、看着像没换表情。
  var TOUCH_MOODS = [
    'surprised', 'half', 'meh', 'curious', 'angry', 'sad',
    'sleepy', 'yawn', 'dizzy', 'love', 'smile', 'petted',
    'wink', 'happy'
  ];
  var MOOD_GROUP = {
    surprised: 0, curious: 0, angry: 0, sad: 0,           // 睁眼普通
    half: 1, meh: 1, sleepy: 1, yawn: 1, petted: 1,       // 半睁 / 眯
    love: 2, dizzy: 2,                                    // 特殊瞳孔
    smile: 3, happy: 3, wink: 3                            // 闭眼弧
  };
  var moodBag = [];
  var lastPicked = '';
  function nextTouchMood() {
    if (!moodBag.length) {
      // 先洗牌，再按「发出顺序」贪心重排：一轮里 14 个表情各出现一次，
      // 且相邻两张（连上一轮最后一张也算）尽量不同组，避免 half→sleepy
      // 这种两个半睁眯眼连着出、看着像没换表情。
      var pool = TOUCH_MOODS.slice();
      for (var i = pool.length - 1; i > 0; i--) {          // Fisher-Yates
        var j = (Math.random() * (i + 1)) | 0;
        var t = pool[i]; pool[i] = pool[j]; pool[j] = t;
      }
      var order = [];
      var prev = lastPicked ? MOOD_GROUP[lastPicked] : -1;
      while (pool.length) {
        var at = -1;
        for (var k = pool.length - 1; k >= 0; k--) {
          if (MOOD_GROUP[pool[k]] !== prev) { at = k; break; }
        }
        if (at < 0) at = pool.length - 1;                  // 只剩同组时兜底
        var m = pool.splice(at, 1)[0];
        order.push(m);
        prev = MOOD_GROUP[m];
      }
      moodBag = order.reverse();       // pop 从末尾取 = 按 order 顺序发
    }
    lastPicked = moodBag.pop();
    return lastPicked;
  }


  function tick(t) {
    // 当前姿态向目标缓动；heart/spiral 也是连续量，跟着一起缓动。
    var target = POSE[mood()] || POSE.surprised;
    var ease = target.ease || (seq ? 0.30 : 0.14);
    for (var i = 0; i < LERP_KEYS.length; i++) {
      var k = LERP_KEYS[i];
      cur[k] += (target[k] - cur[k]) * ease;
    }

    // 没人碰的时候眼神自己漂，避免一直盯屏幕中心
    if (t - lastTouch > 2600) {
      gazeTX = Math.sin(t / 1700) * 0.22 + Math.sin(t / 3100) * 0.10;
      gazeTY = Math.cos(t / 2300) * 0.14;
    }
    gazeX += (gazeTX - gazeX) * 0.07;
    gazeY += (gazeTY - gazeY) * 0.07;
    sleepK += ((mood() === 'closed' ? 1 : 0) - sleepK) * 0.05;

    if (seq) {
      if (t - seqAt >= seq[seqIdx].d) {
        seqIdx++;
        seqAt = t;
        if (seqIdx >= seq.length) { seq = null; seqIdx = 0; }
      }
      return;
    }
    if (base !== 'closed' && t - Math.max(lastTouch, t0) > 90000) {
      base = 'closed';
      return;
    }
    if (base === 'closed') return;

    if (t > nextBlink) {
      // 先压眼皮再补一条闭眼弧，比直接跳成弧线自然
      play([{ m: 'blinkDown', d: 70 }, { m: 'blink', d: 100 }]);
      nextBlink = t + rnd(2200, 5600);
      return;
    }
    if (t > nextIdle) {
      var r = Math.random();
      if (r < 0.12) {
        // 好奇：歪一下眼、瞄一眼，再眨回去
        base = 'surprised';
        play([{ m: 'curious', d: 900 }, { m: 'blinkDown', d: 70 }, { m: 'blink', d: 100 }, { m: 'surprised', d: 300 }]);
      } else if (r < 0.22) {
        // 困：眯一会儿，打个哈欠再惊醒
        base = 'surprised';
        play([{ m: 'sleepy', d: 1600 }, { m: 'yawn', d: 500 }, { m: 'surprised', d: 400 }]);
      } else if (r < 0.32) {
        base = 'meh';
        play([{ m: 'angry', d: 1100 }, { m: 'meh', d: 500 }]);
      } else if (r < 0.40) {
        base = 'meh';
        play([{ m: 'dizzy', d: 1400 }, { m: 'meh', d: 400 }]);
      } else if (r < 0.48) {
        base = 'half';
        play([{ m: 'sad', d: 1200 }, { m: 'half', d: 400 }]);
      } else if (r < 0.60) {
        // 微笑：温柔地眯一下再睁开
        base = 'surprised';
        play([{ m: 'smile', d: 2200 }, { m: 'surprised', d: 400 }]);
      } else {
        base = pick(['surprised', 'surprised', 'half', 'meh', 'curious', 'love', 'smile']);
      }
      nextIdle = t + rnd(5200, 11000);
    }
  }

  // ---------------------------------------------------------------- 绘制
  var lastDraw = 0;
  var logged = false;

  function draw(ts) {
    requestAnimationFrame(draw);
    if (ts - lastDraw < 30) return;   // ~30fps 足够，省点电
    lastDraw = ts;

    // WebView 首次布局前 innerWidth 可能是 0，且某些 ROM 不补发 resize 事件。
    if (W !== window.innerWidth || H !== window.innerHeight) resize();

    tick(ts);

    g.setTransform(dpr, 0, 0, dpr, 0, 0);
    g.fillStyle = '#0b0b10';          // 屏幕就是黑猫的脸
    g.fillRect(0, 0, W, H);

    if (!logged && W > 0) {
      logged = true;
      log('face vp ' + W + 'x' + H + ' dpr=' + dpr + ' s=' + S.toFixed(2) +
        ' eye=' + Math.round(ERX * 2 * S) + 'x' + Math.round(ERY * 2 * S));
    }

    // 呼吸：睡着时几乎不动（sleepK 连续过渡，不再入睡瞬间幅度跳变）
    var amp = 0.006 - sleepK * 0.004;
    var k = 1 + Math.sin((ts - t0) / 1500) * amp;

    g.save();
    g.translate(W / 2, H / 2);
    g.scale(k, k);
    g.translate(-W / 2, -H / 2);

    drawMorphEye(EYE_L, cur.arcL, cur.openL, ts);
    drawMorphEye(EYE_R, cur.arcR, cur.openR, ts);

    g.restore();
  }

  requestAnimationFrame(draw);

  // ---------------------------------------------------------------- 交互
  document.addEventListener('pointerdown', function (e) {
    if (e.target && e.target.id === 'x') return;
    lastTouch = now();
    // 眼珠看手指：屏幕中心为 0，边缘饱和到 ±1
    gazeTX = Math.max(-1, Math.min(1, (e.clientX - W / 2) / (W * 0.35)));
    gazeTY = Math.max(-1, Math.min(1, (e.clientY - H / 2) / (H * 0.35)));
    // 摸一下：先惊一下（短到几乎察觉不到，只是个"被碰到"的起手），
    // 然后从全部表情里发一个并停住——不再连 flash 一串眯眼。
    var targetMood = nextTouchMood();
    base = targetMood;
    play([{ m: 'surprised', d: 130 }, { m: targetMood, d: 900 }]);
    nextIdle = now() + 9000;
    nextBlink = now() + 5000;
  }, { passive: true });

  var xb = document.getElementById('x');
  if (xb) {
    xb.addEventListener('click', function (e) {
      e.stopPropagation();
      lastTouch = now();
      try { AndroidPage.post(JSON.stringify({ k: 'close' })); } catch (err) {
        try { AndroidPage.post('{"k":"close"}'); } catch (e2) { log('close fail'); }
      }
    });
  }
})();
