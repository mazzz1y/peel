(function () {
  const iconUrls = [...document.querySelectorAll('link[rel*="icon"]')]
    .filter(l => l.href)
    .map(l => ({
      href: l.href,
      sizes: l.getAttribute("sizes") || "",
      source: l.rel.includes("apple-touch-icon") ? "Apple" : "HTML",
    }));

  const manifestEl = document.querySelector('link[rel="manifest"]');
  const manifestUrl = manifestEl?.href || "";

  browser.runtime.sendNativeMessage("pageInfo", { iconUrls, manifestUrl });
})();
