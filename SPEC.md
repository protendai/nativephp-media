# protendai/nativephp-media — native contract

The contract between the PHP facade, the JavaScript library and the native
implementations. Every name here must match exactly on both platforms.

- Composer: `protendai/nativephp-media`, PHP namespace `Protendai\Media`, facade `Protendai\Media\Facades\Media` (accessor `protendai.media`), provider `Protendai\Media\MediaServiceProvider`, config `nativephp-media`.
- Bridge namespace: `Media.*`.
- Android: Kotlin package `com.protendai.media`, sources in `resources/android/src/`. Bridge classes in `object MediaFunctions`, signature `class X(private val activity: FragmentActivity) : BridgeFunction` returning a flat `Map<String, Any>`. Nested params arrive as `org.json.JSONObject` / `JSONArray`; handle `List`/`Map`/JSON strings too. Media3 **1.5.1** (`media3-exoplayer`, `media3-session`, `media3-ui`), minSdk 29.
- iOS: `enum MediaFunctions` with `class X: BridgeFunction`, sources in `resources/ios/Sources/`, iOS 16+, no pods.
- Events are dispatched as `Protendai\Media\Events\<Name>`; payload keys = PHP constructor parameter names (NativePHP builds them with `new $class(...$payload)`). iOS leaves null values out so PHP defaults apply.

## Queue item

```
{ "id": string (stable key, used for resume), "uri": string,
  "title": ?string, "artist": ?string, "album": ?string, "artwork": ?string (file path or URL),
  "type": "audio"|"video" }
```

`uri`: absolute file path, `file://`, `http(s)://`, `content://` (Android), `ipod-library://` or `ph://<localIdentifier>` (iOS library items). `id` defaults to the uri.

## Bridge functions

| Name | Params | Returns (sync) | Async result |
|---|---|---|---|
| `Media.SetQueue` | `items: [item]`, `startIndex: int=0`, `startPosition: int ms=-1`, `play: bool=true` | `{ok}` (false when no item is usable) | `TrackChanged`, `StateChanged` |
| `Media.Play` | – | `{ok}` | `StateChanged` |
| `Media.Pause` | – | `{ok}` | `StateChanged` |
| `Media.Toggle` | – | `{ok}` | `StateChanged` |
| `Media.Next` / `Media.Previous` | – | `{ok}` | `TrackChanged` |
| `Media.SkipTo` | `index: int` | `{ok}` | `TrackChanged` |
| `Media.Seek` | `position: int ms` | `{ok}` | – |
| `Media.SetShuffle` | `enabled: bool` | `{ok}` | `StateChanged` |
| `Media.SetRepeat` | `mode: "off"|"one"|"all"` | `{ok}` | `StateChanged` |
| `Media.SetSpeed` | `speed: float 0.25–3.0` | `{ok}` | `StateChanged` |
| `Media.Stop` | – | `{ok}` | `StateChanged` (queue cleared) |
| `Media.State` | – | state object, cached natively, safe from any thread | – |
| `Media.OpenVideo` | `items`, `startIndex=0`, `startPosition=-1` | `{ok}` | full-screen video on the same player |
| `Media.Probe` | `uri`, `artworkDest: ?string` | `{ok, title, artist, album, durationMs, width, height, hasArtwork, error?}` | – |
| `Media.Library` | `type: "audio"|"video"`, `limit=500`, `offset=0` | `{ok, total, offset, items:[{id, uri, title, artist, album, durationMs, size, dateAdded, folder, mime}]}` | – |
| `Media.RequestPermission` | `type` | `{ok, granted?, limited?, error?}` (`error: "library_disabled"` when not enabled at build time) | `PermissionResult` |
| `Media.HasPermission` | `type` | `{granted, limited}` | – |

### State object (`Media.State` and `StateChanged`)

```
{ playing: bool, buffering: bool, index: int (-1 none), count: int, id: ?string, title: ?string,
  artist: ?string, artwork: ?string, type: ?"audio"|"video", positionMs: int, durationMs: int,
  shuffle: bool, repeat: "off"|"one"|"all", speed: float, error: ?string }
```

`StateChanged` fires when anything but `positionMs` changes.

## Events

- `StateChanged` — every state key.
- `TrackChanged(?string $id, int $index, ?string $title, ?string $artist, ?string $type)`
- `PlaybackError(?string $id, ?string $message)`
- `QueueEnded()`
- `PermissionResult(bool $granted, string $type, bool $limited = false)`

## Playback rules (identical on both platforms)

- **Resume positions** are stored per item `id` (Android SharedPreferences `protendai_media_positions`, iOS UserDefaults key `protendai_media_positions`): every 5 s while playing, on pause, and for the **outgoing** item whenever the current item changes by hand (next, previous, skipTo, a new queue, notification / lock-screen / Bluetooth controls). Within 5 s of the end counts as finished and clears it; an item that plays to its end is cleared too.
- **Where an item starts**: `setQueue` / `openVideo` with `startPosition = -1` and `skipTo()` resume the saved position. `next`, `previous` and automatic advance start the item from 0.
- `previous` restarts the current item when past 3 s, otherwise goes back one (wrapping with repeat all).
- `skipTo` starts playback. `play` / `toggle` on a finished queue start again from its first item.
- One player per app: video and audio share it, so a new queue replaces the previous one.
- Closing the video player (or dismissing its picture-in-picture window) pauses / stops playback only while the player still holds that video; audio queued meanwhile keeps playing.

## Device library (opt-in)

Off by default. `config('nativephp-media.library')` (env `NATIVEPHP_MEDIA_LIBRARY`): `false`, `true`/`"all"`, `"audio"`, `"video"`. The `post_compile` hook `nativephp:media:configure` (class `Protendai\Media\Commands\ConfigureNativeProject`) then:

- Android: adds `READ_MEDIA_AUDIO` (audio), `READ_MEDIA_VIDEO` + `READ_MEDIA_VISUAL_USER_SELECTED` (video), `READ_EXTERNAL_STORAGE maxSdkVersion=32` (either), marked with `<!-- nativephp-media:library -->`; removes its marked lines when switched off (never permissions other plugins declare).
- iOS: adds `NSAppleMusicUsageDescription` (audio) / `NSPhotoLibraryUsageDescription` (video) with `config('nativephp-media.usage_descriptions.*')`, to `NativePHP/Info.plist` and `NativePHP-simulator-Info.plist`; never overwrites another value (e.g. from `config('nativephp.permissions')`); removes only its own strings when switched off.

Listing:

- Android: MediaStore, newest first (`DATE_ADDED`). Audio = `IS_MUSIC != 0`; video = all videos. `uri` is `content://…`, `folder` is `RELATIVE_PATH`. Without permission Android still returns the files the app created itself. Android 14 partial video access returns the selected videos and reports `limited`.
- iOS: audio = the Music app's downloaded, DRM-free songs (`MPMediaQuery.songs()` with an `assetURL`, not cloud, not protected), newest first, cached between pages until the library changes; uri `ipod-library://…`, `folder` = album, `size` 0. Video = Photos library videos, newest first by creation date; uri `ph://<localIdentifier>`, `folder` "Photos". Without permission: `{ok: true, items: [], total: 0}`. Limited Photos access counts as granted, `limited: true`.
- Without the usage string / manifest permission, `RequestPermission` answers `granted: false` at once (iOS would otherwise terminate the app).

## Android components

- Service `com.protendai.media.PlaybackService : MediaSessionService`, `exported: true`, `foregroundServiceType: mediaPlayback`, intent-filter `androidx.media3.session.MediaSessionService`. Owns the only `ExoPlayer` (audio focus, becoming-noisy, network wake mode). Session activity = launch intent. `onTaskRemoved` stops unless playing.
- Session policy (`onConnect`): this app's own controllers get every command; other packages (system UI, Bluetooth, Wear/Auto, other apps) get the default player commands minus `COMMAND_SET_MEDIA_ITEM` and `COMMAND_CHANGE_MEDIA_ITEMS`. `onAddMediaItems` restores `requestMetadata.mediaUri` only for this app's controllers and rejects others.
- Playback resumption: the service keeps the last queue (up to 500 items around the current one), index and position in SharedPreferences `protendai_media_resume` and returns it from `onPlaybackResumption` (system UI resume card on Android 13+, media buttons). `androidx.media3.session.MediaButtonReceiver` is declared for the media-button case. `stop()` clears it.
- Activity `com.protendai.media.VideoActivity` (`exported: false`, `singleTask`, handles configuration changes). Binds a `PlayerView` to the shared controller; gestures (double-tap ±10 s, drag to scrub, brightness / volume), speed, PiP. `android:supportsPictureInPicture` / `resizeableActivity` are added by the hook to exactly this activity.
- `MediaManager`: the bridge's `MediaController`, state cache, events. Holds the NativePHP host activity weakly and re-adopts a recreated instance of it (activity lifecycle callbacks). Events raised while no host activity exists are buffered (the latest `StateChanged` plus up to 16 others) and delivered when it is resumed. Commands issued before the controller connects are queued (at most 32); if the service can't be reached they are dropped and a `PlaybackError` is sent.

## iOS components

- `ProtendaiMediaPlayer` (`MediaPlayerEngine.swift`): one `AVPlayer` driving the queue, shuffle order, repeat, speed (`defaultRate`), resume positions keyed by the id of the item actually loaded in the player, `MPNowPlayingInfoCenter` + `MPRemoteCommandCenter`, `AVAudioSession` `.playback` (deactivated with `notifyOthersOnDeactivation` on stop), pause on interruption / headphones out.
- `ProtendaiVideoController` (`MediaVideoController.swift`): `AVPlayerViewController` on the shared player with PiP. A second `OpenVideo` while in PiP reuses the same controller.
- `MediaFunctions.swift`: bridge classes and `ProtendaiMediaLibrary` (library, permissions, probe with the async `AVAsset` loading APIs; `ok: false` for missing, unreadable or non-media files).
- Manifest: `background_modes: ["audio"]`. Usage strings come from the hook (above).
