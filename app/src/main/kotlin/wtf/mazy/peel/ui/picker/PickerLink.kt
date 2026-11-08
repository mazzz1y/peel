package wtf.mazy.peel.ui.picker

import wtf.mazy.peel.model.UnwrappedLink
import wtf.mazy.peel.model.WebApp

/** The link a picker opens, and how its web apps are ordered for a link. */
class PickerLink(val initial: UnwrappedLink, val order: (UnwrappedLink) -> List<WebApp>)

/** What a link from another app can do besides opening in a web app. */
class PickerLinkCallbacks(val onIncognito: (UnwrappedLink) -> Unit, val onShare: (UnwrappedLink) -> Unit)
