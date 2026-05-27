(function () {
  var THROTTLE_MS = 250;
  var SPA_POST_NAV_RESAMPLE_MS = 300;

  function parseRgb(input) {
    if (!input) return null;
    var m = input.match(/^rgba?\(\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:,\s*(-?\d+(?:\.\d+)?))?\s*\)$/i);
    if (!m) return null;
    return {
      r: parseFloat(m[1]),
      g: parseFloat(m[2]),
      b: parseFloat(m[3]),
      a: m[4] === undefined ? 1 : parseFloat(m[4]),
    };
  }

  function formatRgb(c) {
    var r = Math.round(c.r);
    var g = Math.round(c.g);
    var b = Math.round(c.b);
    return c.a >= 1 ? "rgb(" + r + ", " + g + ", " + b + ")"
                    : "rgba(" + r + ", " + g + ", " + b + ", " + c.a + ")";
  }

  function isOpaqueColor(c) {
    return c && c.a >= 1;
  }

  function mixOver(top, bottom) {
    if (!bottom) return top;
    if (!top || top.a <= 0) return bottom;
    if (top.a >= 1) return top;
    var a = top.a + bottom.a * (1 - top.a);
    if (a <= 0) return { r: 0, g: 0, b: 0, a: 0 };
    return {
      r: (top.r * top.a + bottom.r * bottom.a * (1 - top.a)) / a,
      g: (top.g * top.a + bottom.g * bottom.a * (1 - top.a)) / a,
      b: (top.b * top.a + bottom.b * bottom.a * (1 - top.a)) / a,
      a: a,
    };
  }

  function getElementColour(element) {
    if (!(element instanceof Element)) return null;
    var style = getComputedStyle(element);
    var opacity = parseFloat(style.opacity);
    if (isNaN(opacity) || opacity === 0) return null;
    var bg = parseRgb(style.backgroundColor);
    if (!bg || bg.a === 0) return null;
    bg.a = bg.a * opacity;
    return bg;
  }

  function getPageColours() {
    var colours = [];
    if (!document.body) return colours;
    var w = window.innerWidth;
    if (!w) return colours;
    var candidates;
    try {
      candidates = document.elementsFromPoint(w / 2, 3);
    } catch (e) {
      candidates = [];
    }
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (!(el instanceof HTMLElement)) continue;
      if (el.offsetWidth < w * 0.9) continue;
      if (el.offsetHeight < 20) continue;
      var c = getElementColour(el);
      if (c) colours.push(c);
    }
    var bodyC = getElementColour(document.body);
    if (bodyC) colours.push(bodyC);
    var htmlC = getElementColour(document.documentElement);
    if (htmlC) colours.push(htmlC);
    return colours;
  }

  function extractColor() {
    var pageColours = getPageColours();
    var mixed = null;
    for (var i = 0; i < pageColours.length; i++) {
      mixed = mixOver(mixed, pageColours[i]);
      if (isOpaqueColor(mixed)) return formatRgb(mixed);
    }
    return mixed ? formatRgb(mixed) : "";
  }

  var lastColor = null;
  var lastSentAt = 0;
  var dispatchTimer = null;

  function dispatch() {
    dispatchTimer = null;
    if (document.visibilityState !== "visible") return;
    var color = extractColor();
    if (!color) return;
    if (color === lastColor) return;
    lastColor = color;
    lastSentAt = Date.now();
    try {
      browser.runtime.sendNativeMessage("themeColor", { color: color });
    } catch (e) {}
  }

  function sendColor() {
    if (dispatchTimer !== null) {
      clearTimeout(dispatchTimer);
    }
    var remaining = THROTTLE_MS + lastSentAt - Date.now();
    if (remaining <= 0) {
      dispatch();
    } else {
      dispatchTimer = setTimeout(dispatch, remaining);
    }
  }

  function sendColorIfFocused() {
    if (document.hasFocus()) sendColor();
  }

  var darkReaderObserver = new MutationObserver(sendColor);
  var styleTagObserver = new MutationObserver(function (mutationList) {
    var changed = mutationList.some(function (m) {
      return [].concat(
        Array.prototype.slice.call(m.addedNodes),
        Array.prototype.slice.call(m.removedNodes)
      ).some(function (n) { return n.nodeName === "STYLE"; });
    });
    if (changed) sendColor();
  });

  function enableDynamic() {
    document.addEventListener("click", sendColor, { passive: true });
    document.addEventListener("visibilitychange", sendColor);
    window.addEventListener("scroll", sendColor, { passive: true });
    window.addEventListener("resize", sendColor, { passive: true });

    ["transitionend", "transitioncancel", "animationend", "animationcancel"].forEach(function (ev) {
      document.addEventListener(ev, sendColorIfFocused, { passive: true });
    });

    darkReaderObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["data-darkreader-mode"],
    });
    styleTagObserver.observe(document.documentElement, { childList: true });
    if (document.head) styleTagObserver.observe(document.head, { childList: true });
  }

  window.addEventListener("pageshow", function (e) {
    if (e.persisted) {
      lastColor = null;
      sendColor();
    }
  });

  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function () {
    lastColor = null;
    sendColor();
  });

  var origPush = history.pushState;
  history.pushState = function () {
    origPush.apply(this, arguments);
    setTimeout(sendColor, SPA_POST_NAV_RESAMPLE_MS);
  };
  var origReplace = history.replaceState;
  history.replaceState = function () {
    origReplace.apply(this, arguments);
    setTimeout(sendColor, SPA_POST_NAV_RESAMPLE_MS);
  };
  window.addEventListener("popstate", function () {
    setTimeout(sendColor, SPA_POST_NAV_RESAMPLE_MS);
  });

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", sendColor);
  }
  window.addEventListener("load", sendColor);

  enableDynamic();
  sendColor();
})();
