(function () {
  var THROTTLE_MS = 250;
  var SPA_POST_NAV_RESAMPLE_MS = 300;

  var colourContext = null;

  function resolveColour(cssText) {
    if (!cssText) return null;
    var text = String(cssText).trim();
    if (!text || /^currentcolor$/i.test(text)) return null;
    if (!CSS.supports("color", text)) return null;
    if (!colourContext) {
      colourContext = document.createElement("canvas").getContext("2d");
      if (!colourContext) return null;
    }
    colourContext.fillStyle = text;
    var serialised = colourContext.fillStyle;
    var hex = serialised.match(/^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i);
    if (hex) {
      return {
        r: parseInt(hex[1], 16),
        g: parseInt(hex[2], 16),
        b: parseInt(hex[3], 16),
        a: 1,
      };
    }
    var rgba = serialised.match(/^rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)\s*(?:,\s*(-?\d+(?:\.\d+)?))?\s*\)$/i);
    if (!rgba) return null;
    return {
      r: parseInt(rgba[1], 10),
      g: parseInt(rgba[2], 10),
      b: parseInt(rgba[3], 10),
      a: rgba[4] === undefined ? 1 : parseFloat(rgba[4]),
    };
  }

  function formatRgb(c) {
    var r = Math.round(c.r);
    var g = Math.round(c.g);
    var b = Math.round(c.b);
    return c.a >= 1 ? "rgb(" + r + ", " + g + ", " + b + ")"
                    : "rgba(" + r + ", " + g + ", " + b + ", " + c.a + ")";
  }

  function getElementColour(element) {
    if (!(element instanceof Element)) return null;
    var style = getComputedStyle(element);
    var opacity = parseFloat(style.opacity);
    if (isNaN(opacity) || opacity === 0) return null;
    var bg = resolveColour(style.backgroundColor);
    if (!bg || bg.a === 0) return null;
    bg.a = bg.a * opacity;
    return formatRgb(bg);
  }

  function spansViewportWidth(element) {
    var rect = element.getBoundingClientRect();
    return rect.left <= 1 && rect.right >= document.documentElement.clientWidth - 1;
  }

  function getPageColoursAt(y) {
    var colours = [];
    if (!document.body) return colours;
    var w = window.innerWidth;
    if (!w) return colours;
    var candidates;
    try {
      candidates = document.elementsFromPoint(w / 2, y);
    } catch (e) {
      candidates = [];
    }
    for (var i = 0; i < candidates.length; i++) {
      var el = candidates[i];
      if (!(el instanceof HTMLElement)) continue;
      if (!spansViewportWidth(el)) continue;
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

  function collectCandidatesAt(ys) {
    for (var i = 0; i < ys.length; i++) {
      var colours = getPageColoursAt(ys[i]);
      if (colours.length > 0) return colours;
    }
    return [];
  }

  function readMetaThemeColor() {
    if (!document.head) return "";
    var nodes = document.querySelectorAll('meta[name="theme-color"]');
    for (var i = 0; i < nodes.length; i++) {
      var media = nodes[i].getAttribute("media");
      if (media && !window.matchMedia(media).matches) continue;
      var resolved = resolveColour(nodes[i].getAttribute("content"));
      if (resolved && resolved.a > 0) return formatRgb(resolved);
    }
    return "";
  }

  function collectColorData() {
    var h = window.innerHeight;
    var topYs = [3, 20, 50, 100];
    var bottomYs = h ? [h - 3, h - 20, h - 50, h - 100] : [];
    return {
      top: collectCandidatesAt(topYs),
      bottom: collectCandidatesAt(bottomYs),
      meta: readMetaThemeColor(),
    };
  }

  var dispatchTimer = null;
  var frameRequest = null;
  var lastSentAt = 0;

  function dispatch() {
    frameRequest = null;
    if (document.visibilityState !== "visible") return;
    if (!document.body) return;
    lastSentAt = Date.now();
    var data = collectColorData();
    try {
      browser.runtime.sendNativeMessage("themeColor", data);
    } catch (e) {}
  }

  function requestFrame() {
    dispatchTimer = null;
    if (frameRequest !== null) return;
    frameRequest = requestAnimationFrame(dispatch);
  }

  function sendColor() {
    if (dispatchTimer !== null) {
      clearTimeout(dispatchTimer);
      dispatchTimer = null;
    }
    var remaining = THROTTLE_MS + lastSentAt - Date.now();
    if (remaining <= 0) {
      requestFrame();
    } else {
      dispatchTimer = setTimeout(requestFrame, remaining);
    }
  }

  function sendColorIfFocused() {
    if (document.hasFocus()) sendColor();
  }

  var styleTagObserver = new MutationObserver(function (mutationList) {
    var changed = mutationList.some(function (m) {
      return [].concat(
        Array.prototype.slice.call(m.addedNodes),
        Array.prototype.slice.call(m.removedNodes)
      ).some(function (n) { return n.nodeName === "STYLE" || n.nodeName === "LINK"; });
    });
    if (changed) sendColor();
  });
  var rootAttrObserver = new MutationObserver(sendColor);
  var metaThemeColourObserver = new MutationObserver(sendColor);
  var metaTagObserver = new MutationObserver(function (mutationList) {
    mutationList.forEach(function (mutation) {
      Array.prototype.slice.call(mutation.addedNodes).forEach(function (node) {
        if (node && node.nodeName === "META" && node.getAttribute &&
            node.getAttribute("name") === "theme-color") {
          sendColor();
          metaThemeColourObserver.observe(node, { attributes: true });
        }
      });
    });
  });

  function enableDynamic() {
    document.addEventListener("click", sendColor, { passive: true });
    document.addEventListener("visibilitychange", sendColor);
    window.addEventListener("scroll", sendColor, { passive: true });
    window.addEventListener("resize", sendColor, { passive: true });

    ["transitionend", "transitioncancel", "animationend", "animationcancel"].forEach(function (ev) {
      document.addEventListener(ev, sendColorIfFocused, { passive: true });
    });

    rootAttrObserver.observe(document.documentElement, {
      attributes: true,
      attributeFilter: ["class", "style", "data-theme", "data-color-mode", "data-darkreader-mode"],
    });
    if (document.body) {
      rootAttrObserver.observe(document.body, {
        attributes: true,
        attributeFilter: ["class", "style"],
      });
    }
    styleTagObserver.observe(document.documentElement, { childList: true });
    if (document.head) {
      styleTagObserver.observe(document.head, { childList: true });
      metaTagObserver.observe(document.head, { childList: true });
      var metas = document.head.querySelectorAll('meta[name="theme-color"]');
      for (var i = 0; i < metas.length; i++) {
        metaThemeColourObserver.observe(metas[i], { attributes: true });
      }
    }
  }

  window.addEventListener("pageshow", sendColor);
  window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", sendColor);

  function scheduleSpaResample() {
    setTimeout(sendColor, SPA_POST_NAV_RESAMPLE_MS);
  }

  var origPush = history.pushState;
  history.pushState = function () {
    origPush.apply(this, arguments);
    scheduleSpaResample();
  };
  var origReplace = history.replaceState;
  history.replaceState = function () {
    origReplace.apply(this, arguments);
    scheduleSpaResample();
  };
  window.addEventListener("popstate", scheduleSpaResample);

  function onDomReady() {
    enableDynamic();
    sendColor();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", onDomReady);
  } else {
    onDomReady();
  }
  window.addEventListener("load", sendColor);
})();
