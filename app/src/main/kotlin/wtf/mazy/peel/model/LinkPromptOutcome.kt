package wtf.mazy.peel.model

/** What a window does with a navigation it asked about, once the link sheet has answered. */
sealed interface LinkPromptOutcome {
    /** Let the navigation load as it was asked for. */
    data object Proceed : LinkPromptOutcome

    /** Refuse the navigation and load [url] in its place. */
    data class LoadHere(val url: String) : LinkPromptOutcome

    /**
     * Refuse the navigation; [elsewhere] opens the link somewhere else. Unless it opens in
     * another web app, a window with no page of its own to return to is left.
     */
    data class Decline(val elsewhere: LinkChoice.Elsewhere?) : LinkPromptOutcome {
        val abandonsWindow: Boolean get() = elsewhere !is LinkChoice.App

        /** Whether the link leaves for another app, so this window's page is only passed through. */
        val leavesPeel: Boolean get() = elsewhere is LinkChoice.System || elsewhere is LinkChoice.Incognito
    }

    companion object {
        /** [mustReload] when the navigation can't simply be allowed, as for a new window's. */
        fun of(promptedUrl: String, mustReload: Boolean, choice: LinkChoice): LinkPromptOutcome = when (choice) {
            is LinkChoice.CurrentWindow ->
                if (mustReload || choice.url != promptedUrl) LoadHere(choice.url) else Proceed

            is LinkChoice.Elsewhere -> Decline(choice)
            LinkChoice.Dismissed,
            is LinkChoice.DownloadImage,
            is LinkChoice.ShareImage -> Decline(null)
        }
    }
}
