function extractColor() {
  var isDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
  var metas = document.querySelectorAll('meta[name="theme-color"]');
  for (var i = 0; i < metas.length; i++) {
    var media = metas[i].getAttribute("media");
    if (!media) continue;
    if (isDark && media.indexOf("dark") !== -1) return metas[i].content;
    if (!isDark && media.indexOf("light") !== -1) return metas[i].content;
  }
  function isOpaque(c) {
    return c && c !== "rgba(0, 0, 0, 0)" && c !== "transparent";
  }
  var bodyBg = document.body ? getComputedStyle(document.body).backgroundColor : "";
  if (isOpaque(bodyBg)) return bodyBg;
  var htmlBg = getComputedStyle(document.documentElement).backgroundColor;
  if (isOpaque(htmlBg)) return htmlBg;
  return "";
}

var lastColor = null;
function sendColor() {
  var color = extractColor();
  if (color === lastColor) return;
  lastColor = color;
  browser.runtime.sendNativeMessage("themeColor", { color: color });
}

sendColor();

window.addEventListener("pageshow", function(e) {
  if (e.persisted) {
    lastColor = null;
    sendColor();
  }
});

window.addEventListener("load", function() {
  setTimeout(sendColor, 500);
});

window.matchMedia("(prefers-color-scheme: dark)").addEventListener("change", function() {
  lastColor = null;
  sendColor();
});

var origPush = history.pushState;
history.pushState = function() {
  origPush.apply(this, arguments);
  setTimeout(sendColor, 300);
};
var origReplace = history.replaceState;
history.replaceState = function() {
  origReplace.apply(this, arguments);
  setTimeout(sendColor, 300);
};
window.addEventListener("popstate", function() {
  setTimeout(sendColor, 300);
});

new MutationObserver(sendColor).observe(
  document.head,
  { childList: true, subtree: true, attributes: true, attributeFilter: ["content", "media"] }
);

if (document.body) {
  new MutationObserver(sendColor).observe(
    document.body,
    { attributes: true, attributeFilter: ["style", "class"] }
  );
}

new MutationObserver(sendColor).observe(
  document.documentElement,
  { attributes: true, attributeFilter: ["style", "class"] }
);
