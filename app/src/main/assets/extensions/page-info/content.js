(function () {
  const port = browser.runtime.connectNative("pageInfo");
  const MAX_URL_LEN = 2048;
  const MAX_URLS = 20;
  const MAX_MANIFEST_TEXT = 1024 * 1024;
  const MAX_ICON_BYTES = 1024 * 1024;
  const FETCH_TIMEOUT_MS = 5000;
  function isValidUrl(url) {
    if (typeof url !== "string" || url.length === 0 || url.length > MAX_URL_LEN) return false;
    return url.startsWith("https://") || url.startsWith("http://");
  }

  function fetchWithTimeout(url, options) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), FETCH_TIMEOUT_MS);
    return fetch(url, { ...options, signal: controller.signal }).finally(() => clearTimeout(timer));
  }

  function fetchIcon(url) {
    return fetchWithTimeout(url, { credentials: "same-origin" })
      .then(r => {
        if (!r.ok) return Promise.reject();
        const type = (r.headers.get("content-type") || "").toLowerCase();
        if (type && !type.startsWith("image/")) return Promise.reject();
        return r.blob();
      })
      .then(blob => {
        if (blob.size > MAX_ICON_BYTES) return Promise.reject();
        return blob;
      })
      .then(blob => new Promise((resolve) => {
        const img = new Image();
        const objectUrl = URL.createObjectURL(blob);
        const done = (value) => {
          URL.revokeObjectURL(objectUrl);
          resolve(value);
        };
        img.onload = () => {
          try {
            const c = document.createElement("canvas");
            c.width = img.naturalWidth;
            c.height = img.naturalHeight;
            c.getContext("2d").drawImage(img, 0, 0);
            done(c.toDataURL("image/png"));
          } catch (_) {
            done("");
          }
        };
        img.onerror = () => done("");
        img.src = objectUrl;
      }))
      .catch(() => "");
  }

  function scrapePage() {
    const iconUrls = [...document.querySelectorAll('link[rel*="icon"]')]
      .filter(l => l.href)
      .map(l => ({
        href: l.href,
        sizes: l.getAttribute("sizes") || "",
        source: l.rel.includes("apple-touch-icon") ? "Apple" : "HTML",
      }));
    const manifestEl = document.querySelector('link[rel="manifest"]');
    const manifestUrl = manifestEl?.href || "";
    const pageTitle = document.title || "";
    return { iconUrls, manifestUrl, pageTitle };
  }

  port.onMessage.addListener((msg) => {
    const requestId = Number.isInteger(msg?.requestId) ? msg.requestId : -1;
    if (requestId < 1) return;

    if (msg.cmd === "scrape-page") {
      const { iconUrls, manifestUrl, pageTitle } = scrapePage();
      port.postMessage({ mode: "page", requestId, iconUrls, manifestUrl, pageTitle });
    } else if (msg.cmd === "fetch-manifest") {
      if (!isValidUrl(msg.url)) {
        port.postMessage({ mode: "manifest", requestId, text: "" });
        return;
      }
      fetchWithTimeout(msg.url, { credentials: "same-origin" })
        .then(r => r.ok ? r.text() : "")
        .then(text => port.postMessage({ mode: "manifest", requestId, text: text.slice(0, MAX_MANIFEST_TEXT) }))
        .catch(() => port.postMessage({ mode: "manifest", requestId, text: "" }));
    } else if (msg.cmd === "fetch-icons") {
      if (!Array.isArray(msg.urls) || msg.urls.length > MAX_URLS) {
        port.postMessage({ mode: "icons", requestId, results: [] });
        return;
      }
      const urls = msg.urls.filter(isValidUrl).slice(0, MAX_URLS);
      Promise.all(urls.map(url => fetchIcon(url).then(dataUrl => ({ url, dataUrl }))))
        .then(results => port.postMessage({ mode: "icons", requestId, results }))
        .catch(() => port.postMessage({ mode: "icons", requestId, results: [] }));
    } else {
      port.postMessage({ mode: "error", requestId });
    }
  });
})();
