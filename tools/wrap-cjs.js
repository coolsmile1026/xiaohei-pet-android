#!/usr/bin/env node
// 把原版 CommonJS 模块（menu2/model.js）包一层浏览器可用的壳。
// 用法: node tools/wrap-cjs.js app/src/main/assets/web/renderer/menu2/model.js  -> 同目录 model.cjs.js
const fs = require('fs');
const path = require('path');

const src = process.argv[2];
if (!src) { console.error('usage: wrap-cjs.js <file.js>'); process.exit(2); }
const dir = path.dirname(src);
const base = path.basename(src, '.js');
const out = path.join(dir, base + '.cjs.js');

let code = fs.readFileSync(src, 'utf8').replace(/^\uFEFF/, '').replace(/^\/\/[^\n]*\n/, '');

const wrapped = `// 由 tools/wrap-cjs.js 生成，勿手改。
// 原版 ${base}.js 是 CommonJS 模块（require / module.exports），浏览器里没有这两个东西，
// 这里给它一个最小的模块作用域，导出挂到 window.MenuModel 上。
(function () {
  var module = { exports: {} };
  var exports = module.exports;
  var require = window.require;
  var __dirname = '/app/renderer/menu2';
  var __filename = '/app/renderer/menu2/${base}.js';
${code}
  window.MenuModel = module.exports;
})();
`;

fs.writeFileSync(out, wrapped);
console.log('wrote', out, wrapped.length, 'bytes');
