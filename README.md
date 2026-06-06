<p align="center">
  <img src=".github/icon.svg" width="96" height="96" alt="Peel Logo">
</p>

<div align="center">

# Peel

**Turn any website into a native-like Android app**

Peel is an open-source Android app that turns websites into standalone, app-like experiences. It
lets you create lightweight web apps with custom icons, isolated storage, and fine-grained privacy
controls, separate from your browser.

Powered by GeckoView (Mozilla's browser engine)

<a href="https://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/mazzz1y/peel">
<img src="https://raw.githubusercontent.com/ImranR98/Obtainium/refs/heads/main/assets/graphics/badge_obtainium.png"
alt="Get it on Obtainium" align="center" height="54" /></a>

<a href="https://play.google.com/store/apps/details?id=wtf.mazy.peel">
<img src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png"
alt="Get it on Google Play" align="center" height="80" style="margin: 0 -10px" /></a>

<a href="https://mazzz1y.github.io/fdroid/repo">
<img src="https://raw.githubusercontent.com/mazzz1y/fdroid/refs/heads/main/assets/f-repo.png"
alt="Get it on my F-Droid repo" align="center" height="54" /></a>

</div>

## Features

- Add websites as standalone apps with automatic icon and title fetching
- Launch web apps directly from your home screen with adaptive icons
- App grouping with home screen group shortcuts
- Per-group and per-app optional isolated sandbox with separate cookies and storage
- Per-sandbox HTTP, HTTPS, SOCKS4, and SOCKS5 proxy support
- Offline on-device page translations powered by Mozilla's translation models
- Smart external link routing to other Peel apps or system browser
- Privacy controls: GPC signal, fingerprinting protection, local network blocking, WebRTC IP leak
  prevention
- Firefox extensions support
- Enhanced Tracking Protection, HTTPS-only mode, custom headers
- Lock sensitive web apps behind biometric authentication, block screenshots
- Dynamic status bar color matching web content
- Background media playback with full MediaSession support (notification controls, seek, metadata)
- Set settings globally or override them per app
- Share individual apps and groups, or export and import full backups
- Written in Kotlin with Material 3 interface

## Screenshots

| ![Screenshot 1](metadata/en-US/images/phoneScreenshots/1.png) | ![Screenshot 2](metadata/en-US/images/phoneScreenshots/2.png) | ![Screenshot 3](metadata/en-US/images/phoneScreenshots/3.png) | ![Screenshot 4](metadata/en-US/images/phoneScreenshots/4.png) |
| :----------------------------------------: | :----------------------------------------: | :----------------------------------------: | :----------------------------------------: |

## Acknowledgments

Hard fork of [Native Alpha](https://github.com/cylonid/NativeAlphaForAndroid) with significant
changes including a full migration from WebView to GeckoView, flow refactoring, removal of redundant
options, and new features. Not compatible with the original.

## License

[GPL-3.0](LICENSE)
