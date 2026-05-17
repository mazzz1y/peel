"use strict";

// Per-sandbox proxy routing for Peel.
//
// Kotlin assigns each webapp / group a stable contextId (its UUID) and sets
// that as the GeckoSession's contextId. On the extension side, the
// corresponding tab's cookieStoreId is "firefox-container-<contextId>", which
// we use as the lookup key in the routing table that Kotlin pushes over the
// native messaging port at connect time.
//
// FAIL-CLOSED: any "firefox-container-*" storeId without a matching route is
// unreachable. Likewise, while no routes have been received (e.g. the native
// port just disconnected) every sandboxed request is unreachable. Non-sandbox
// requests always go DIRECT.

const NATIVE_APP = "proxyRouter";
const CONTAINER_PREFIX = "firefox-container-";

let port = null;
let routes = new Map();

const DIRECT = { type: "direct" };
// 0.0.0.0:1 is unreachable; produces NS_ERROR_PROXY_CONNECTION_REFUSED.
const FAIL_CLOSED = { type: "http", host: "0.0.0.0", port: 1, failoverTimeout: 5 };

function connect() {
  try { port = browser.runtime.connectNative(NATIVE_APP); }
  catch (_) { setTimeout(connect, 1000); return; }
  port.onMessage.addListener(handleNativeMessage);
  port.onDisconnect.addListener(() => {
    port = null;
    // Invalidate routes so a sandboxed request during the reconnect window
    // fails closed instead of matching a stale (potentially mis-configured)
    // table that may no longer reflect Kotlin's intent.
    routes = new Map();
    setTimeout(connect, 1000);
  });
  try { port.postMessage({ type: "hello" }); } catch (_) { }
}

function handleNativeMessage(msg) {
  if (!msg || typeof msg !== "object") return;
  if (msg.cmd === "set-routes") {
    const next = new Map();
    const r = msg.routes || {};
    for (const k of Object.keys(r)) next.set(k, normalizeConfig(r[k]));
    routes = next;
    const seq = typeof msg.seq === "number" ? msg.seq : -1;
    if (seq >= 0 && port) {
      try { port.postMessage({ type: "routes-ack", seq: seq }); } catch (_) { }
    }
  }
}

function normalizeConfig(cfg) {
  if (!cfg || typeof cfg !== "object") return null;
  if (cfg.type === "direct") return { type: "direct" };
  return {
    type: cfg.type || "http",
    host: cfg.host || "",
    port: cfg.port || 0,
    username: cfg.username || null,
    password: cfg.password || null,
    remoteDns: !!cfg.remoteDns,
    bypass: Array.isArray(cfg.bypass) ? cfg.bypass : [],
  };
}

function safeHostFromUrl(url) {
  if (!url || typeof url !== "string") return "";
  try { return new URL(url).hostname || ""; } catch (_) { return ""; }
}

function ipToBytes(ip) {
  if (ip.indexOf(":") !== -1) {
    const parts = ip.split("::");
    const head = parts[0] ? parts[0].split(":") : [];
    const tail = parts.length > 1 && parts[1] ? parts[1].split(":") : [];
    const fill = 8 - head.length - tail.length;
    const full = head.concat(new Array(fill).fill("0"), tail);
    if (full.length !== 8) return null;
    const bytes = new Array(16);
    for (let i = 0; i < 8; i++) {
      const v = parseInt(full[i] || "0", 16);
      if (isNaN(v) || v < 0 || v > 0xffff) return null;
      bytes[i * 2] = (v >> 8) & 0xff;
      bytes[i * 2 + 1] = v & 0xff;
    }
    return bytes;
  }
  const p = ip.split(".");
  if (p.length !== 4) return null;
  const out = [];
  for (let j = 0; j < 4; j++) {
    const n = parseInt(p[j], 10);
    if (isNaN(n) || n < 0 || n > 255) return null;
    out.push(n);
  }
  return out;
}

function cidrMatch(host, cidr) {
  const slash = cidr.indexOf("/");
  if (slash < 0) return false;
  const net = cidr.substring(0, slash);
  const bits = parseInt(cidr.substring(slash + 1), 10);
  if (isNaN(bits)) return false;
  const hostBytes = ipToBytes(host);
  const netBytes = ipToBytes(net);
  if (!hostBytes || !netBytes) return false;
  if (hostBytes.length !== netBytes.length) return false;
  const full = bits >> 3;
  const rem = bits & 7;
  for (let i = 0; i < full; i++) {
    if (hostBytes[i] !== netBytes[i]) return false;
  }
  if (rem === 0) return true;
  if (full >= hostBytes.length) return true;
  const mask = (0xff << (8 - rem)) & 0xff;
  return (hostBytes[full] & mask) === (netBytes[full] & mask);
}

function matchBypass(host, list) {
  if (!list || list.length === 0) return false;
  const h = host.toLowerCase();
  for (let i = 0; i < list.length; i++) {
    const r = (list[i] || "").trim().toLowerCase();
    if (!r) continue;
    if (r.indexOf("*.") === 0) {
      if (h === r.substring(2) || h.endsWith(r.substring(1))) return true;
    } else if (r.indexOf("/") !== -1) {
      if (cidrMatch(h, r)) return true;
    } else if (h === r) {
      return true;
    }
  }
  return false;
}

function buildProxyInfo(cfg, url) {
  if (!cfg) return null;
  if (cfg.type === "direct") return DIRECT;
  const host = safeHostFromUrl(url);
  if (host && matchBypass(host, cfg.bypass)) return DIRECT;
  if (!cfg.host || !cfg.port) return null;
  const isSocks = cfg.type === "socks" || cfg.type === "socks4" || cfg.type === "socks5";
  const info = {
    type: cfg.type === "socks5" ? "socks" : cfg.type,
    host: cfg.host,
    port: cfg.port,
    failoverTimeout: 10,
  };
  if (cfg.username) info.username = cfg.username;
  if (cfg.password) info.password = cfg.password;
  if (isSocks) info.proxyDNS = !!cfg.remoteDns;
  return info;
}

// Cache of tab.cookieStoreId, keyed by tabId.
//
// Why we need this: in GeckoView, `details.cookieStoreId` passed to
// proxy.onRequest is almost always "firefox-default" — even for requests
// inside a sandboxed (container) session. Asking the tab directly via
// `browser.tabs.get(tabId)` returns the correct "firefox-container-<id>"
// value, so we cache that and use it as the source of truth.
//
// We pre-populate the cache from tabs.onCreated / onUpdated so that most
// requests are resolved without an extra round-trip; resolveTabStore() below
// handles the first-request-for-a-tab race by falling back to tabs.get().
//
// Upstream bug:  https://bugzilla.mozilla.org/show_bug.cgi?id=1948114
const tabStoreCache = new Map();

function cacheTabStore(tabId, storeId) {
  if (typeof tabId !== "number" || tabId < 0) return;
  if (!storeId) return;
  const existing = tabStoreCache.get(tabId);
  if (existing && existing !== "firefox-default" && storeId === "firefox-default") {
    return;
  }
  tabStoreCache.set(tabId, storeId);
}

if (browser.tabs) {
  if (browser.tabs.onCreated) {
    browser.tabs.onCreated.addListener((tab) => {
      if (tab && typeof tab.id === "number") cacheTabStore(tab.id, tab.cookieStoreId);
    });
  }
  if (browser.tabs.onUpdated) {
    browser.tabs.onUpdated.addListener((tabId, _changeInfo, tab) => {
      if (tab) cacheTabStore(tabId, tab.cookieStoreId);
    });
  }
  if (browser.tabs.onRemoved) {
    browser.tabs.onRemoved.addListener((tabId) => {
      tabStoreCache.delete(tabId);
    });
  }
}

function isSandboxStoreId(storeId) {
  return typeof storeId === "string" &&
    storeId !== "firefox-default" &&
    storeId.indexOf(CONTAINER_PREFIX) === 0;
}

const tabStorePending = new Map();
function resolveTabStore(tabId, fallback) {
  if (typeof tabId !== "number" || tabId < 0) return Promise.resolve(fallback);
  if (tabStoreCache.has(tabId)) return Promise.resolve(tabStoreCache.get(tabId));
  if (tabStorePending.has(tabId)) return tabStorePending.get(tabId);
  if (!browser.tabs || !browser.tabs.get) return Promise.resolve(fallback);
  const p = browser.tabs.get(tabId).then(t => {
    const storeId = (t && typeof t.cookieStoreId === "string") ? t.cookieStoreId : null;
    tabStorePending.delete(tabId);
    if (storeId) {
      cacheTabStore(tabId, storeId);
      return tabStoreCache.get(tabId) || storeId;
    }
    return tabStoreCache.get(tabId) || fallback;
  }).catch(() => {
    tabStorePending.delete(tabId);
    return tabStoreCache.get(tabId) || fallback;
  });
  tabStorePending.set(tabId, p);
  return p;
}

function decide(url, storeId) {
  if (isSandboxStoreId(storeId)) {
    if (routes.size === 0) return FAIL_CLOSED;
    const cfg = routes.get(storeId);
    if (!cfg) return FAIL_CLOSED;
    const info = buildProxyInfo(cfg, url);
    if (info) return info;
    return FAIL_CLOSED;
  }
  return DIRECT;
}

function safeDecide(url, storeId) {
  try {
    const r = decide(url, storeId);
    if (!r || typeof r !== "object" || typeof r.type !== "string") {
      return isSandboxStoreId(storeId) ? FAIL_CLOSED : DIRECT;
    }
    return r;
  } catch (_) {
    return isSandboxStoreId(storeId) ? FAIL_CLOSED : DIRECT;
  }
}

browser.proxy.onRequest.addListener(
  (details) => {
    const url = details.url || "";
    const reportedStore = details.cookieStoreId || null;
    const tabId = typeof details.tabId === "number" ? details.tabId : -1;
    const cachedStore = tabStoreCache.get(tabId);

    if (isSandboxStoreId(cachedStore)) return safeDecide(url, cachedStore);
    if (isSandboxStoreId(reportedStore)) return safeDecide(url, reportedStore);

    if (tabId >= 0) {
      return resolveTabStore(tabId, reportedStore)
        .then(store => safeDecide(url, store));
    }
    return safeDecide(url, reportedStore);
  },
  { urls: ["<all_urls>"] }
);

connect();
