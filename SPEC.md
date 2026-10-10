# protendai/nativephp-media — build contract

Shared spec for parallel implementation. Every piece MUST match these names exactly.

- Composer: `protendai/nativephp-media`, PHP namespace `Protendai\Media`, facade `Protendai\Media\Facades\Media` (accessor `protendai.media`), provider `Protendai\Media\MediaServiceProvider`.
- Bridge namespace: `Media.*`. Kotlin package: `com.protendai.media`. Bridge classes live in `object MediaFunctions` (file `resources/android/MediaFunctions.kt`), signature `class X(private val activity: FragmentActivity) : BridgeFunction` returning `Map<String, Any>` (flat map, PHP reads it via `json_decode`), exactly like `tuma/mobile-lan` / `protendai/nativephp-wifi-direct`.
- Events dispatched with `NativeActionCoordinator.dispatchEvent(activity, "Protendai\\Media\\Events\\<Name>", json)` on the UI thread. Event payload keys = PHP constructor parameter names.
- Android: Media3 **1.5.1** (`media3-exoplayer`, `media3-session`, `media3-ui`). App minSdk 33, compileSdk 36, Kotlin 2.0. No Compose in plugin code.
- Nested bridge params arrive as `org.json.JSONObject` / `JSONArray` (NOT Map/List). Always handle both.

## Queue item (PHP array → JSON object)

```
{ "id": string (stable key, used for resume), "uri": string (file path or content:// or http(s)),
  "title": ?string, "artist": ?string, "album": ?string, "artwork": ?string (file path or URL),
  "type": "audio"|"video" }
```

## Bridge functions

| Name | Params | Returns (sync) | Async result |
|---|---|---|---|
| `Media.SetQueue` | `items: [item]`, `startIndex: int=0`, `startPosition: int ms=-1` (-1 = resume saved position for that id, else 0), `play: bool=true` | `{ok}` | `TrackChanged`, `StateChanged` |
| `Media.Play` | – | `{ok}` | `StateChanged` |
| `Media.Pause` | – | `{ok}` | `StateChanged` |
| `Media.Toggle` | – | `{ok}` | `StateChanged` |
| `Media.Next` / `Media.Previous` | – | `{ok}` | `TrackChanged` |
| `Media.SkipTo` | `index: int` | `{ok}` | `TrackChanged` |
| `Media.Seek` | `position: int ms` | `{ok}` | – |
| `Media.SetShuffle` | `enabled: bool` | `{ok}` | `StateChanged` |
| `Media.SetRepeat` | `mode: "off"|"one"|"all"` | `{ok}` | `StateChanged` |
| `Media.SetSpeed` | `speed: float 0.25–3.0` | `{ok}` | `StateChanged` |
| `Media.Stop` | – | `{ok}` | `StateChanged` (playing=false, queue cleared) |
| `Media.State` | – | state object (below), cached on native side, safe from any thread | – |
| `Media.OpenVideo` | `items: [item]`, `startIndex: int=0`, `startPosition: int=-1` | `{ok}` | opens full-screen `VideoActivity` playing through the same session |
| `Media.Probe` | `uri: string`, `artworkDest: ?string` (write embedded art / video frame JPEG there) | `{ok, title, artist, album, durationMs, width, height, hasArtwork}` sync | – |
| `Media.Library` | `type: "audio"|"video"`, `limit: int=500`, `offset: int=0` | `{ok, total, offset, items:[{id, uri (content://), title, artist, album, durationMs, size, dateAdded, folder, mime}]}` sync, MediaStore query, newest first | – |
| `Media.RequestPermission` | `type: "audio"|"video"` | `{ok, granted?}` | `PermissionResult` |
| `Media.HasPermission` | `type` | `{granted: bool}` | – |

### State object (`Media.State` return and `StateChanged` payload)

```
{ playing: bool, buffering: bool, index: int (-1 none), count: int, id: ?string, title: ?string,
  artist: ?string, artwork: ?string, type: ?"audio"|"video", positionMs: int, durationMs: int,
  shuffle: bool, repeat: "off"|"one"|"all", speed: float, error: ?string }
```

## Events (`Protendai\Media\Events\*`)

- `StateChanged` — all state-object keys as ctor params (camelCase as above).
- `TrackChanged(?string $id, int $index, ?string $title, ?string $artist, ?string $type)`
- `PlaybackError(?string $id, ?string $message)`
- `QueueEnded()`
- `PermissionResult(bool $granted, string $type)`

## Android components (manifest via nativephp.json)

- Service `com.protendai.media.PlaybackService` : `MediaSessionService`, `exported: true`, `foregroundServiceType: "mediaPlayback"`, intent-filter action `androidx.media3.session.MediaSessionService`.
- Activity `com.protendai.media.VideoActivity`, theme `@style/Theme.AppCompat.NoActionBar`, `exported: false`, `launchMode: singleTask`, `configChanges: orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden`.
- Permissions: `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`, `<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />`. (`POST_NOTIFICATIONS` not needed: media-session notifications are exempt.)
- PiP: `post_compile` hook command `nativephp:media:enable-pip` (class `Protendai\Media\Commands\EnablePictureInPicture`) patches the built `AndroidManifest.xml` so the `VideoActivity` element gets `android:supportsPictureInPicture="true"` and `android:resizeableActivity="true"`. Idempotent.

## Kotlin internals (shared between engine and video)

- `object MediaManager` (file `MediaManager.kt`, engine agent):
  - `fun attach(activity: FragmentActivity)` — remembers activity, connects a `MediaController` to `PlaybackService` (async; queues commands until connected).
  - `fun withController(block: (MediaController) -> Unit)` — runs on main thread once connected.
  - `fun setQueue(items: JSONArray, startIndex: Int, startPositionMs: Long, play: Boolean)` — builds `MediaItem`s (mediaId=id, `MediaMetadata` title/artist/album/artworkUri, extras `type`), resume lookup, prepare, play.
  - `fun stateJson(): JSONObject`, `fun dispatch(event: String, payload: JSONObject)`.
  - Resume: saves position per media id to SharedPreferences `protendai_media_positions` on pause, track change, and every 5 s while playing; clears when within 5 s of the end.
- `PlaybackService` owns the single `ExoPlayer`: `setAudioAttributes(USAGE_MEDIA/CONTENT_TYPE_MUSIC, handleAudioFocus=true)`, `setHandleAudioBecomingNoisy(true)`, `setWakeMode(C.WAKE_MODE_NETWORK)`; `MediaSession` with session activity = app launch intent; `onTaskRemoved` stops if not playing. Default Media3 notification (lock screen, Bluetooth, notification shade).
- `VideoActivity` (video agent): Media3 `PlayerView` bound to `MediaManager`'s controller (does not create its own player). Gestures overlay: double-tap left/right = −/+10 s; horizontal drag = scrub with time bubble; vertical drag left half = window brightness, right half = media volume; single tap toggles controls. Top bar: back, title, speed button (0.5/0.75/1/1.25/1.5/2), PiP button. `onUserLeaveHint` → enter PiP when playing (aspect from video size). Auto landscape when video is wider than tall. Pause when activity stops and not in PiP. Immersive full-screen.

## iOS

Implemented in `resources/ios/` (iOS 18+, no pods): `MediaFunctions.swift` (enum `MediaFunctions`, bridge classes + `ProtendaiMediaLibrary`), `MediaPlayerEngine.swift` (`ProtendaiMediaPlayer`: one `AVPlayer` driving the queue, shuffle order, repeat, speed via `defaultRate`, per-id resume in `UserDefaults` key `protendai_media_positions`, same 5 s save / end-margin rules, `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`, `AVAudioSession` `.playback`, pause on interruption/headphones out), `MediaVideoController.swift` (`AVPlayerViewController` on the shared player, PiP, pauses when closed unless in PiP).

Differences from Android:
- `Media.Library` audio = Music app songs that are downloaded and DRM-free (`MPMediaQuery`, `assetURL` set, not cloud/protected), uri `ipod-library://…`; video = Photos library videos, uri `ph://<localIdentifier>`. `size` is 0, `folder` is the album (audio) / `"Photos"` (video). Without permission: `{ok:true, items:[], total:0}`.
- `ph://` uris play (resolved through `PHImageManager`) and `Media.Probe` reads them (duration, size, Photos thumbnail as artwork), but they are not file paths.
- `Media.RequestPermission` asks for Media Library (audio) or Photos read access (video); limited Photos access counts as granted.
- `Media.OpenVideo` uses iOS's standard player UI (scrub, skip, speed, PiP) instead of the custom gesture overlay.
- Manifest: `background_modes: ["audio"]`, `NSAppleMusicUsageDescription`, `NSPhotoLibraryUsageDescription`.

## Tumira integration (app agent)

- New tab **Library** (`/library`, `App\NativeComponents\Library`) with `tab-row` Music | Videos.
  - Music: received audio (TransferFile, mime audio/*, completed, path) + device audio (`Media.Library`, after permission; a "Show all music on this phone" button requests it). Tap → `Media::setQueue(allVisibleTracks, startIndex)`.
  - Videos: received + device videos with thumbnails (existing `Thumbnails` service for received; `Media.Probe` artworkDest for device items cached in `storage/app/thumbs/media-<hash>.jpg`). Tap → `Media::openVideo(...)`.
- Mini player partial (`native.partials.mini-player`) shown above the tab bar on main tabs whenever `Media::state()['index'] >= 0`: artwork, title/artist, play/pause, next; tap → `/now-playing`.
- `/now-playing` (`App\NativeComponents\NowPlaying`, stack layout): big artwork, title/artist, progress (slider seek), prev/play/next, shuffle, repeat, speed. `#[Poll(1000)]` refresh from `Media::state()`.
- Received audio/video in History detail and Receive "Received" list open in the player instead of the system viewer.
