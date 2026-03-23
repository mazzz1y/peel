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
browser.runtime.sendNativeMessage("themeColor", { color: extractColor() });
