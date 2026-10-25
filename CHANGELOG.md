# Changelog

All notable changes to `protendai/nativephp-media` are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-10-25

First public release.

### Added
- JavaScript library (`resources/js`, `@protendai/nativephp-media`) with a function per bridge function, event constants and listeners, and TypeScript types.
- `Media::permissionStatus()` and `PermissionResult::$limited` for partial library access (iOS "Selected Photos", Android 14 "Select videos").
- Android 14 partial video access (`READ_MEDIA_VISUAL_USER_SELECTED`).
- Android playback resumption: the last queue comes back from the system UI resume card and media buttons (`onPlaybackResumption`, `MediaButtonReceiver`).
- `config/nativephp-media.php` (`library`, `usage_descriptions`), publishable with the `nativephp-media-config` tag.
- FakeBridge test helpers: `withMedia()`, `assertMediaQueued()`, `assertVideoOpened()`, `assertMediaPermissionRequested()`.
- Boost guidelines, Pest test suite, LICENSE.

### Changed
- **Device library access is opt-in.** `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO` (and legacy storage) and the iOS Music / Photos usage strings are only declared when `NATIVEPHP_MEDIA_LIBRARY` is `true`, `audio` or `video`. Apps that only play their own files or URLs no longer need a Google Play photo/video permission declaration.
- iOS usage strings are generic and configurable; an app's own `nativephp.permissions` value always wins.
- One `post_compile` hook, `nativephp:media:configure`, replaces `nativephp:media:enable-pip`; the picture-in-picture patch is anchored to `com.protendai.media.VideoActivity`.
- Resume behaviour is the same on both platforms: `setQueue`/`openVideo` (with `startPosition = -1`) and `skipTo` resume an item's saved position; `next`, `previous` and auto-advance start from 0. `skipTo` starts playback on Android as it already did on iOS.
- iOS minimum version lowered to 16.0.
- Native sources moved to `resources/android/src` and `resources/ios/Sources`.
- MIT licence.

### Fixed
- iOS saved the previous track's position under the new track's id on every track change, so resuming could seek past the end. Positions are now saved for the outgoing item before the queue moves, keyed by the item actually loaded in the player.
- Android did not save the outgoing item's position on manual changes (next/previous/skipTo, notification and Bluetooth controls, a new queue).
- The exported Android media session let any app queue arbitrary URIs (including app-private files). Other packages now get playback commands only, and `onAddMediaItems` trusts `requestMetadata.mediaUri` from this app only.
- Closing the Android video player (or its PiP window) stopped whatever the shared player was playing, including music started afterwards. It now only stops its own video; dismissing the PiP window is detected however the platform reports it.
- Android events were sent through a strongly held, possibly destroyed activity: dropped (or crashing) once the app's activity finished while music played on. The host activity is now held weakly, re-adopted when recreated, and events are buffered until it is back.
- Double progress tick after the controller reconnected; queued commands replayed long after a failed service connection.
- A permission request lost to activity recreation never answered; it now always sends `PermissionResult` with the current state.
- iOS `probe()` used deprecated synchronous AVAsset APIs and reported `ok: true` for missing files.
- iOS `stop()` never deactivated the audio session, so other apps' audio did not resume.
- iOS re-read and re-sorted the whole Music library for every page.
- iOS requesting library permission without the usage string would terminate the app; it now answers `granted: false`.
- A second iOS `openVideo()` while a video was in picture-in-picture stacked another player.
- Native logs no longer contain titles, file paths or URIs.
