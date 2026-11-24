#!/usr/bin/env node
'use strict';

// 把 assets/ 下所有 JSON 内联成 renderer/preload-data.js 里的 window.__FILES。
//
// 为什么需要：原版渲染层用 require('fs').readFileSync 同步读 rig.json / anim.json 等，
// WebView 里没有同步 IO（也不该有），所以构建期把文本全部塞进一个 JS 对象，
// shim.js 的假 fs 按键名查表返回原文。
//
// 键 = 相对 assets/ 的 posix 路径（与 shim.js normKey 的约定一致），值是文件原文。
//
// 用法：
//   node tools/build-data.js
//   node tools/build-data.js --assets app/src/main/assets/web/assets \
//                            --out app/src/main/assets/web/renderer/preload-data.js

const fs = require('fs');
const path = require('path');

const ROOT = path.resolve(__dirname, '..');

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
const ASSETS = path.resolve(args.assets || path.join(ROOT, 'app/src/main/assets/web/assets'));
const OUT = path.resolve(args.out || path.join(ROOT, 'app/src/main/assets/web/renderer/preload-data.js'));

if (!fs.existsSync(ASSETS)) {
  console.error('assets 目录不存在: ' + ASSETS);
  process.exit(1);
}

function walk(dir, acc) {
  for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, ent.name);
    if (ent.isDirectory()) walk(p, acc);
    else if (ent.isFile()) acc.push(p);
  }
  return acc;
}

const files = walk(ASSETS, [])
  .filter((p) => p.toLowerCase().endsWith('.json'))
  .map((p) => ({ abs: p, key: path.relative(ASSETS, p).split(path.sep).join('/') }))
  .sort((a, b) => (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));

if (!files.length) {
  console.error('没有找到任何 .json，检查 --assets 是否指对');
  process.exit(1);
}

const parts = files.map((f) => {
  const text = fs.readFileSync(f.abs, 'utf8');
  try {
    JSON.parse(text);
  } catch (e) {
    console.error('JSON 解析失败，宁可停下也不生成坏文件: ' + f.key + ' — ' + e.message);
    process.exit(1);
  }
  return JSON.stringify(f.key) + ': ' + JSON.stringify(text);
});

const body =
  '// 由 tools/build-data.js 生成，不要手改。\n' +
  '// 键是相对 assets/ 的路径，值是文件原文；shim.js 的假 fs 按这个表返回。\n' +
  'window.__FILES={' + parts.join(',\n') + '};\n';

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, body, 'utf8');

const kb = (Buffer.byteLength(body) / 1024).toFixed(0);
console.log('内联 ' + files.length + ' 个 JSON -> ' + path.relative(ROOT, OUT) + '（' + kb + 'KB）');
