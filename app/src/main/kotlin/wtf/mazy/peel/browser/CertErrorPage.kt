/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package wtf.mazy.peel.browser

import android.net.Uri

internal object CertErrorPage {

    private const val HTML_RESOURCE =
        "resource://android/assets/cert-bypass/index.html"

    /**
     * Build the URL of the privileged cert-error page. The page itself reads
     * `host` and `url` from the query string and picks a localized string
     * bundle based on `navigator.language`. `addCertException` works because
     * `resource://android/` content is treated as a trusted cert-error context
     * by Gecko (`Document::CallerIsTrustedAboutCertError`).
     */
    fun urlFor(originalUri: String): String {
        val host = runCatching { Uri.parse(originalUri).host.orEmpty() }.getOrDefault("")
        val q = "host=" + Uri.encode(host) + "&url=" + Uri.encode(originalUri)
        return "$HTML_RESOURCE?$q"
    }
}
