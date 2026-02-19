package wtf.mazy.peel.media

import android.webkit.JavascriptInterface

@Suppress("unused")
class MediaJsBridge(private val listener: Listener) {

    interface Listener {
        fun onMediaStarted()
        fun onMediaPaused()
        fun onMediaStopped()
        fun onMetadataChanged(title: String?, artist: String?, artworkUrl: String?)
        fun onActionsChanged(hasPrevious: Boolean, hasNext: Boolean)
        fun onPositionChanged(durationMs: Long, positionMs: Long, playbackRate: Float)
    }

    @JavascriptInterface
    fun onPlay() = listener.onMediaStarted()

    @JavascriptInterface
    fun onPause() = listener.onMediaPaused()

    @JavascriptInterface
    fun onEnded() = listener.onMediaStopped()

    @JavascriptInterface
    fun onMetadata(title: String?, artist: String?, artworkUrl: String?) =
        listener.onMetadataChanged(
            title?.takeIf { it.isNotEmpty() },
            artist?.takeIf { it.isNotEmpty() },
            artworkUrl?.takeIf { it.isNotEmpty() },
        )

    @JavascriptInterface
    fun onActions(hasPrevious: Boolean, hasNext: Boolean) =
        listener.onActionsChanged(hasPrevious, hasNext)

    @JavascriptInterface
    fun onPositionState(duration: Double, position: Double, playbackRate: Double) =
        listener.onPositionChanged(
            (duration * 1000).toLong(),
            (position * 1000).toLong(),
            playbackRate.toFloat(),
        )

    companion object {
        const val JS_INTERFACE_NAME = "_PeelMedia"

        val POLYFILL_JS = """
            (function() {
                if (window._peelPolyfillActive) return;
                window._peelPolyfillActive = true;
                var B = $JS_INTERFACE_NAME;
                var handlers = {};
                var currentMetadata = null;

                if (typeof MediaMetadata === 'undefined') {
                    window.MediaMetadata = function(init) {
                        this.title = (init && init.title) || '';
                        this.artist = (init && init.artist) || '';
                        this.album = (init && init.album) || '';
                        this.artwork = (init && init.artwork) || [];
                    };
                }

                function resolveArtwork(md) {
                    if (!md || !md.artwork || md.artwork.length === 0) return '';
                    var src = md.artwork[md.artwork.length - 1].src || '';
                    if (!src) return '';
                    try { return new URL(src, location.href).href; } catch(e) { return src; }
                }

                if (!navigator.mediaSession || !navigator.mediaSession.setActionHandler) {
                    var session = {
                        playbackState: 'none',
                        setActionHandler: function(action, handler) {
                            handlers[action] = handler;
                            B.onActions(!!handlers.previoustrack, !!handlers.nexttrack);
                        },
                        setPositionState: function(state) {
                            if (state) {
                                B.onPositionState(
                                    state.duration || 0,
                                    state.position || 0,
                                    state.playbackRate || 1
                                );
                            }
                        },
                        setCameraActive: function() {},
                        setMicrophoneActive: function() {}
                    };

                    Object.defineProperty(session, 'metadata', {
                        get: function() { return currentMetadata; },
                        set: function(md) {
                            currentMetadata = md;
                            if (md) {
                                B.onMetadata(md.title || '', md.artist || '', resolveArtwork(md));
                            }
                        }
                    });

                    Object.defineProperty(navigator, 'mediaSession', {
                        value: session, writable: false, configurable: true
                    });
                } else {
                    var orig = navigator.mediaSession.setActionHandler.bind(navigator.mediaSession);
                    navigator.mediaSession.setActionHandler = function(action, handler) {
                        handlers[action] = handler;
                        B.onActions(!!handlers.previoustrack, !!handlers.nexttrack);
                        return orig(action, handler);
                    };
                }

                window._peelActionHandlers = handlers;
            })();
        """.trimIndent()

        val OBSERVER_JS = """
            (function() {
                if (window._peelObserverActive) return;
                window._peelObserverActive = true;
                var B = $JS_INTERFACE_NAME;
                window._peelActiveElement = window._peelActiveElement || null;
                var active = window._peelActiveElement;

                var lastDur = -1;
                var lastPos = -1;

                function setActive(el) { active = el; window._peelActiveElement = el; }

                function hookElement(el) {
                    if (el._peelHooked) return;
                    el._peelHooked = true;

                    el.addEventListener('pause', function(e) {
                        if (window._peelBackground && !window._peelUserPause && el === active) {
                            e.stopImmediatePropagation();
                            try { el.play(); } catch(_) {}
                            return;
                        }
                        if (el === active) B.onPause();
                        window._peelUserPause = false;
                    }, true);

                    el.addEventListener('play', function() {
                        setActive(el);
                        lastDur = -1;
                        lastPos = -1;
                        B.onPlay();
                        if (isFinite(el.duration) && el.duration > 0) {
                            lastDur = Math.round(el.duration);
                            lastPos = Math.round(el.currentTime);
                            B.onPositionState(el.duration, el.currentTime, el.playbackRate || 1);
                        }
                    });
                    el.addEventListener('ended', function() {
                        if (el === active) { setActive(null); B.onEnded(); }
                    });
                }

                document.querySelectorAll('audio, video').forEach(hookElement);

                new MutationObserver(function(mutations) {
                    mutations.forEach(function(m) {
                        m.addedNodes.forEach(function(node) {
                            if (node.tagName === 'AUDIO' || node.tagName === 'VIDEO') hookElement(node);
                            if (node.querySelectorAll) {
                                node.querySelectorAll('audio, video').forEach(hookElement);
                            }
                        });
                    });
                }).observe(document.documentElement, { childList: true, subtree: true });

                setInterval(function() {
                    if (active && !document.contains(active)) {
                        setActive(null);
                        lastDur = -1;
                        lastPos = -1;
                        B.onEnded();
                    } else if (active && !active.paused && isFinite(active.duration) && active.duration > 0) {
                        var dur = Math.round(active.duration);
                        var pos = Math.round(active.currentTime);
                        if (dur !== lastDur || Math.abs(pos - lastPos - 1) > 1) {
                            lastDur = dur;
                            lastPos = pos;
                            B.onPositionState(active.duration, active.currentTime, active.playbackRate || 1);
                        } else {
                            lastPos = pos;
                        }
                    }
                }, 1000);
            })();
        """.trimIndent()

        const val PREVIOUS_TRACK_JS =
            "var h = window._peelActionHandlers && window._peelActionHandlers.previoustrack; if (h) h({action:'previoustrack'});"

        const val NEXT_TRACK_JS =
            "var h = window._peelActionHandlers && window._peelActionHandlers.nexttrack; if (h) h({action:'nexttrack'});"

        const val SEEK_TO_JS =
            "var h = window._peelActionHandlers && window._peelActionHandlers.seekto; if (h) h({action:'seekto',seekTime:%f});"

        const val PAUSE_ALL_JS =
            "window._peelUserPause = true; document.querySelectorAll('audio, video').forEach(function(el) { el.pause(); });"

        const val RESUME_JS =
            "(function() { var a = window._peelActiveElement; if (a && a.paused) a.play(); })();"
    }
}
