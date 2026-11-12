/*
 * 来源：HandyCode（MIT License） https://github.com/xianhe51920/handycode
 * 作用：拼多多官方 H5 把业务请求发往 api.pinduoduo.com，与 m.pinduoduo.net 属于跨站，
 *       登录后容易因 Cookie 作用域被判定未登录并跳回登录页。
 *       这里在原官方脚本执行前包装 XMLHttpRequest / fetch，把指定来源的请求映射回
 *       官方自带的同源 /proxy/api 路由。
 * 说明：不读取、不复制、不记录任何 Cookie 或令牌，认证仍由 WebView 按官方站点规则处理。
 */
;(function () {
 'use strict';
 // 只在拼多多官方身份码页面、且早于官方应用脚本执行
 if (location.origin !== 'https://m.pinduoduo.net' || !location.pathname.startsWith('/mdkd/')) return;
 if (window.__mimiPddSameOrigin) return;
 window.__mimiPddSameOrigin = true;
 function route(input) {
  try {
   var target = new URL(String(input), location.href);
   if (target.origin !== 'https://api.pinduoduo.com' || target.username || target.password || !target.pathname.startsWith('/api/')) return input;
   return location.origin + '/proxy/api' + target.pathname + target.search + target.hash;
  } catch (error) { return input; }
 }
 var originalOpen = XMLHttpRequest.prototype.open;
 XMLHttpRequest.prototype.open = function () {
  var args = Array.prototype.slice.call(arguments);
  if (args.length > 1) args[1] = route(args[1]);
  return originalOpen.apply(this, args);
 };
 if (typeof window.fetch === 'function') {
  var originalFetch = window.fetch;
  window.fetch = function (input, init) {
   if (typeof Request !== 'undefined' && input instanceof Request) {
    var mapped = route(input.url);
    if (mapped !== input.url) input = new Request(mapped, input);
   } else input = route(input);
   return originalFetch.call(this, input, init);
  };
 }
})();
