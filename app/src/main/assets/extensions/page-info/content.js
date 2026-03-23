(async function() {
  var result = {iconLinks: [], manifestUrl: "", manifest: null};
  var links = document.querySelectorAll('link[rel*="icon"]');
  for (var i = 0; i < links.length; i++) {
    var l = links[i];
    if (l.href) result.iconLinks.push({
      href: l.href,
      rel: l.rel || "",
      sizes: l.getAttribute("sizes") || ""
    });
  }
  var me = document.querySelector('link[rel="manifest"]');
  if (me && me.href) {
    result.manifestUrl = me.href;
    try {
      var re = await fetch(me.href, {credentials: "include"});
      if (re.ok) result.manifest = await re.json();
    } catch(e) {}
  }
  browser.runtime.sendNativeMessage("pageInfo", result);
})();
