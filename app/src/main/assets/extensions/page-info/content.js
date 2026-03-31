(async () => {
  const MAX_BYTES = 1024 * 1024;
  const MAX_ICONS = 20;

  async function fetchAsDataUrl(url) {
    try {
      const res = await fetch(url, { credentials: "include" });
      if (!res.ok) return null;
      const blob = await res.blob();
      if (!blob.type.startsWith("image/") || blob.size > MAX_BYTES) return null;
      return new Promise(resolve => {
        const reader = new FileReader();
        reader.onload = () => resolve(reader.result);
        reader.onerror = () => resolve(null);
        reader.readAsDataURL(blob);
      });
    } catch (_) { return null; }
  }

  const iconLinks = [...document.querySelectorAll('link[rel*="icon"]')]
    .filter(l => l.href)
    .map(l => ({
      href: l.href,
      rel: l.rel || "",
      sizes: l.getAttribute("sizes") || "",
    }));

  const manifestLink = document.querySelector('link[rel="manifest"]');
  let manifestUrl = "";
  let manifest = null;
  if (manifestLink?.href) {
    manifestUrl = manifestLink.href;
    try {
      const res = await fetch(manifestLink.href, { credentials: "include" });
      if (res.ok) {
        const text = await res.text();
        if (text.length <= MAX_BYTES) {
          try { manifest = JSON.parse(text); } catch (_) {}
        }
      }
    } catch (_) {}
  }

  const iconData = [];
  let iconCount = 0;

  if (manifest?.icons) {
    for (const icon of manifest.icons) {
      if (iconCount >= MAX_ICONS) break;
      if (!icon.src || icon.src.endsWith(".svg")) continue;
      const resolved = new URL(icon.src, manifestUrl || location.href).href;
      const dataUrl = await fetchAsDataUrl(resolved);
      if (dataUrl) {
        iconData.push({ dataUrl, sizes: icon.sizes || "", source: "PWA" });
        iconCount++;
      }
    }
  }

  for (const link of iconLinks) {
    if (iconCount >= MAX_ICONS) break;
    if (link.href.endsWith(".svg")) continue;
    const dataUrl = await fetchAsDataUrl(link.href);
    if (dataUrl) {
      iconData.push({
        dataUrl,
        sizes: link.sizes,
        source: link.rel.includes("apple-touch-icon") ? "Apple" : "HTML",
      });
      iconCount++;
    }
  }

  if (iconCount < MAX_ICONS) {
    const faviconUrl = new URL("/favicon.ico", location.href).href;
    if (!iconLinks.some(l => l.href === faviconUrl)) {
      const faviconData = await fetchAsDataUrl(faviconUrl);
      if (faviconData) {
        iconData.push({ dataUrl: faviconData, sizes: "", source: "Favicon" });
      }
    }
  }

  browser.runtime.sendNativeMessage("pageInfo", { iconData, manifestUrl, manifest });
})();
