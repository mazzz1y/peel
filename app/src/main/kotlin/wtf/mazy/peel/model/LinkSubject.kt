package wtf.mazy.peel.model

/** What the link sheet is asked about. */
sealed interface LinkSubject {
    /** The image the element shows, which can be downloaded or shared. */
    val image: String?

    data class Web(
        val link: UnwrappedLink,
        val text: String? = null,
        override val image: String? = null,
    ) : LinkSubject

    data class Media(val kind: MediaKind, val address: String, val text: String?) : LinkSubject {
        override val image: String? get() = address.takeIf { kind == MediaKind.IMAGE }
    }
}

enum class MediaKind { IMAGE, VIDEO, AUDIO }

/** Where the link sheet opened, which decides the web apps it lists. */
sealed interface LinkOrigin {
    /** Another app's link. [preferred] narrows the web apps offered, unless it would leave none. */
    class Outside(val preferred: (WebApp) -> Boolean) : LinkOrigin

    /** The page a window shows, to open anywhere but in that window's web app. */
    data class Page(val ownerUuid: String?) : LinkOrigin

    /** A window the link can also open in, offered as the first row. */
    sealed interface Window : LinkOrigin {
        data class App(val app: WebApp) : Window
        data object PrivateSession : Window
        data class Extension(val title: String) : Window
    }
}

/** The link sheet's answer. */
sealed interface LinkChoice {
    /** Choices that open the link outside the window it was asked about in. */
    sealed interface Elsewhere : LinkChoice

    data class CurrentWindow(val url: String) : LinkChoice
    data class App(val app: WebApp, val url: String) : Elsewhere
    data class Incognito(val url: String) : Elsewhere
    data class System(val url: String) : Elsewhere
    data class Share(val url: String, val title: String?) : Elsewhere
    data class Add(val url: String) : Elsewhere
    data class DownloadImage(val url: String) : LinkChoice
    data class ShareImage(val url: String) : LinkChoice
    data object Dismissed : LinkChoice
}
