#!/usr/bin/env node
'use strict';

// 从原版安装包里解出被 XHA1 封装的 PNG。
//
// 原版不是「一个写死的密钥」，而是两段式：
//   1) 用 app 代码指纹算出 codeKey —— sha256('xhwrap1\0' + package.json#main + 每个代码文件)
//   2) 用 codeKey 解封 assets/k.bin（AES-256-GCM，12B iv + 16B tag + 32B 密文）得到 master key
//   3) 每个资源 = 'XHA1' + 12B iv + 16B tag + 密文，用 master key 解
// 所以换一版原版安装包，指纹变了仍能自动对上——这也是为什么不能把密钥抄成常量。
//
// 前置：把 resources/app.asar 解包成目录（asar extract），以及保留 app.asar.unpacked。
//
// 用法：
//   node tools/decrypt-assets.js \
//     --app  <解包出来的 app 目录，含 package.json / renderer / license / helper> \
//     --asar <安装目录>/resources/app.asar.unpacked \
//     --out  app/src/main/assets/web/assets
//
// 只解 assets/{v2,v2tv,video,settings} 下的 .png/.webm。
// sound / tutorial / json 本来就没加密，直接拷即可。

const fs = require('fs');
const path = require('path');
const crypto = require('crypto');

const ROOT = path.resolve(__dirname, '..');

const ENC_DIRS = ['v2', 'v2tv', 'video', 'settings'];
const ENC_EXTS = ['.png', '.webm'];

function parseArgs(argv) {
  const o = {};
  for (let i = 2; i < argv.length; i++) {
    if (!argv[i].startsWith('--')) continue;
    const k = argv[i].slice(2);
    const v = argv[i + 1] && !argv[i + 1].startsWith('--') ? argv[++i] : 'true';
    o[k] = v;
  }
  return o;
}

const args = parseArgs(process.argv);
const OUT = path.resolve(args.out || path.join(ROOT, 'app/src/main/assets/web/assets'));

if (!args.app || !args.asar) {
  console.error('用法: node tools/decrypt-assets.js --app <解包的 app 目录> --asar <app.asar.unpacked> [--out <assets>]');
  process.exit(1);
}
const APP = path.resolve(args.app);
const ASAR = path.resolve(args.asar);

if (!fs.existsSync(path.join(APP, 'package.json'))) {
  console.error('--app 下没有 package.json，目录指错了？' + APP);
  process.exit(1);
}
const KSEAL = path.join(ASAR, 'assets', 'k.bin');
if (!fs.existsSync(KSEAL)) {
  console.error('找不到 assets/k.bin，--asar 指错了？' + KSEAL);
  process.exit(1);
}

// 参与指纹的文件清单：顺序必须稳定，原版是按字典序喂进哈希的
function codeFiles(dir) {
  const list = [
    'main.js',
    'updater.js',
    'updroute.js',
    'aboutpos.js',
    'groups.js',
    'assetkey.js',
    'groups.default.json'
  ];
  const scan = (sub, ext) => {
    let ents = [];
    try {
      ents = fs.readdirSync(path.join(dir, sub));
    } catch (e) {
      return;
    }
    for (const n of ents) if (n.endsWith(ext)) list.push(sub + '/' + n);
  };
  scan('license', '.js');
  scan('helper', '.js');
  scan('renderer', '.js');
  scan('renderer', '.html');
  return list.sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
}

function codeKey(dir) {
  const h = crypto.createHash('sha256');
  h.update(Buffer.from('xhwrap1\0', 'binary'));
  let main = '';
  try {
    main = String(JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')).main);
  } catch (e) {
    /* 没有 main 就按空串，和原版一致 */
  }
  h.update(Buffer.from('package.json#main\0' + main + '\0', 'binary'));
  for (const f of codeFiles(dir)) {
    let buf = Buffer.alloc(0);
    try {
      buf = fs.readFileSync(path.join(dir, f));
    } catch (e) {
      /* 缺文件按空 buffer，不能因此让整个指纹崩掉 */
    }
    h.update(Buffer.from(f, 'utf8'));
    h.update(Buffer.from('\0', 'binary'));
    h.update(buf);
    h.update(Buffer.from('\0', 'binary'));
  }
  return h.digest();
}

function unwrap(key, blob) {
  if (!blob || blob.length !== 12 + 16 + 32) return null;
  const d = crypto.createDecipheriv('aes-256-gcm', key, blob.subarray(0, 12));
  d.setAuthTag(blob.subarray(12, 28));
  return Buffer.concat([d.update(blob.subarray(28)), d.final()]);
}

function openFile(key, sealed) {
  // sealed: 'XHA1' + 12B iv + 16B tag + 密文
  const d = crypto.createDecipheriv('aes-256-gcm', key, sealed.subarray(4, 16));
  d.setAuthTag(sealed.subarray(16, 32));
  return Buffer.concat([d.update(sealed.subarray(32)), d.final()]);
}

const ck = codeKey(APP);
console.log('codeKey  = ' + ck.toString('hex'));

let master;
try {
  master = unwrap(ck, fs.readFileSync(KSEAL));
} catch (e) {
  master = null;
}
if (!master) {
  console.error('解封 k.bin 失败：代码指纹对不上。确认 --app 与 --asar 来自同一份安装包。');
  process.exit(1);
}
console.log('master   = ' + master.toString('hex'));

let ok = 0;
let bad = 0;
let skip = 0;
const BASE = path.join(ASAR, 'assets');

function walk(dir, acc) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) walk(p, acc);
    else if (ent.isFile()) acc.push(p);
  }
  return acc;
}

for (const d of ENC_DIRS) {
  const abs = path.join(BASE, d);
  if (!fs.existsSync(abs)) {
    console.log('跳过不存在的目录 ' + d);
    continue;
  }
  // 必须递归：v2tv 的 162 张里大部分在 anim/ stow/ jump/ meal/ 子目录下
  for (const p of walk(abs, [])) {
    if (!ENC_EXTS.some((e) => p.toLowerCase().endsWith(e))) continue;
    const blob = fs.readFileSync(p);
    if (blob.subarray(0, 4).toString() !== 'XHA1') {
      skip++;
      continue;
    }
    try {
      const plain = openFile(master, blob);
      const rel = path.relative(BASE, p); // 保留子目录结构
      const dst = path.join(OUT, rel);
      fs.mkdirSync(path.dirname(dst), { recursive: true });
      fs.writeFileSync(dst, plain);
      ok++;
      const sig = plain.subarray(0, 4).toString('hex');
      if (sig !== '89504e47' && sig !== '1a45dfa3') console.log('  签名可疑 ' + rel + ' -> ' + sig);
    } catch (e) {
      bad++;
      if (bad < 4) console.log('  解密失败 ' + path.relative(BASE, p) + ' — ' + e.message);
    }
  }
}

console.log('解出 ' + ok + ' 个，失败 ' + bad + ' 个，未加密跳过 ' + skip + ' 个');
console.log('输出目录 ' + OUT);
if (bad) process.exit(1);
