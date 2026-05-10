(function () {
  const port = browser.runtime.connectNative("pageBridge");
  const MAX_URL_LEN = 2048;
  const MAX_URLS = 20;
  const MAX_MANIFEST_TEXT = 1024 * 1024;
  const MAX_ICON_BYTES = 1024 * 1024;
  const MAX_BINARY_BYTES = 10 * 1024 * 1024;
  const FETCH_TIMEOUT_MS = 5000;
  const BINARY_FETCH_TIMEOUT_MS = 30000;
  function isValidUrl(url) {
    if (typeof url !== "string" || url.length === 0 || url.length > MAX_URL_LEN) return false;
    return url.startsWith("https://") || url.startsWith("http://");
  }

  function fetchWithTimeout(url, options, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs);
    return fetch(url, { ...options, signal: controller.signal }).finally(() => clearTimeout(timer));
  }

  const SVG_RENDER_SIZE = 512;
  function fetchIcon(url) {
    return fetchWithTimeout(url, { credentials: "same-origin" }, FETCH_TIMEOUT_MS)
      .then(r => {
        if (!r.ok) return Promise.reject();
        const type = (r.headers.get("content-type") || "").toLowerCase();
        if (type && !type.startsWith("image/")) return Promise.reject();
        return r.blob().then(blob => ({ blob, type }));
      })
      .then(({ blob, type }) => {
        if (blob.size > MAX_ICON_BYTES) return Promise.reject();
        return { blob, type };
      })
      .then(({ blob, type }) => new Promise((resolve) => {
        const isSvg = type.includes("svg") || /\.svg(\?|#|$)/i.test(url);
        const img = new Image();
        const objectUrl = URL.createObjectURL(blob);
        const done = (value) => {
          URL.revokeObjectURL(objectUrl);
          resolve(value);
        };
        img.onload = () => {
          try {
            let w = img.naturalWidth;
            let h = img.naturalHeight;
            if (isSvg) {
              const aspect = (w && h) ? (w / h) : 1;
              if (aspect >= 1) { w = SVG_RENDER_SIZE; h = Math.round(SVG_RENDER_SIZE / aspect); }
              else { h = SVG_RENDER_SIZE; w = Math.round(SVG_RENDER_SIZE * aspect); }
            } else if (!w || !h) {
              w = SVG_RENDER_SIZE; h = SVG_RENDER_SIZE;
            }
            const c = document.createElement("canvas");
            c.width = w;
            c.height = h;
            c.getContext("2d").drawImage(img, 0, 0, w, h);
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

  function arrayBufferToBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = "";
    const chunkSize = 0x8000;
    for (let i = 0; i < bytes.length; i += chunkSize) {
      const chunk = bytes.subarray(i, Math.min(i + chunkSize, bytes.length));
      binary += String.fromCharCode.apply(null, chunk);
    }
    return btoa(binary);
  }

  function fetchBinaryWithCredentials(url, credentials) {
    return fetchWithTimeout(url, { credentials }, BINARY_FETCH_TIMEOUT_MS)
      .then(r => {
        if (!r.ok) return Promise.reject(new Error("HTTP " + r.status));
        const contentType = r.headers.get("content-type") || "";
        const disposition = r.headers.get("content-disposition") || "";
        return r.arrayBuffer().then(buf => ({ buf, contentType, disposition }));
      });
  }

  function fetchBinary(url) {
    if (!isValidUrl(url)) {
      return Promise.resolve({ ok: false, error: "invalid-url" });
    }
    return fetchBinaryWithCredentials(url, "include")
      .catch(() => fetchBinaryWithCredentials(url, "same-origin"))
      .then(({ buf, contentType, disposition }) => {
        if (buf.byteLength > MAX_BINARY_BYTES) {
          return { ok: false, error: "too-large" };
        }
        return {
          ok: true,
          base64: arrayBufferToBase64(buf),
          contentType,
          disposition,
        };
      })
      .catch(err => ({ ok: false, error: String((err && err.message) || err || "fetch-failed") }));
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
      fetchWithTimeout(msg.url, { credentials: "same-origin" }, FETCH_TIMEOUT_MS)
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
    } else if (msg.cmd === "fetch-binary") {
      fetchBinary(msg.url).then(result => {
        port.postMessage({ mode: "binary", requestId, ...result });
      });
    } else {
      port.postMessage({ mode: "error", requestId });
    }
  });
})();
