# Media for NativePHP Mobile

A proper media player for [NativePHP Mobile](https://nativephp.com) v4 apps on **iOS and Android**: music that keeps playing with the app closed, controls on the lock screen, in the notification shade and on Bluetooth headphones, queues with shuffle / repeat / speed, per-item resume, and a full-screen video player with picture-in-picture. Optionally, list and play the phone's own music and videos.

- **Background playback**: Android Media3 `MediaSessionService` (foreground `mediaPlayback` service); iOS `AVPlayer` with the `audio` background mode
- **Lock screen, notification, Control Center and Bluetooth / headset controls**
- **Queues**: next / previous / skip-to, **shuffle**, **repeat** (off / one / all), **speed** 0.25×–3×
- **Resume where you left off**, per item, automatically
- **Plays nicely with the phone**: pauses for calls and other apps, pauses when headphones are unplugged, hands audio back when stopped
- **Video player** with **picture-in-picture**. Android: gesture player (double-tap ±10 s, drag to scrub, swipe for brightness / volume, speed button, auto-landscape). iOS: the standard iOS player
- **Device media library (opt-in)**: the phone's music and videos with titles, artists, durations, paged
- **Probe**: tags, duration and dimensions; embedded cover art or a video frame as JPEG
- **Playback resumption** on Android 13+: the system's media "resume" card brings back the last queue

## Platform support

| | Android | iOS |
|---|---|---|
| Minimum | Android 10 (API 29) | iOS 16 |
| Engine | Media3 1.5.1 (ExoPlayer + MediaSession) | AVPlayer, MPNowPlayingInfoCenter, MPRemoteCommandCenter |
| Background audio, lock screen, Bluetooth | Yes | Yes |
| Queue, shuffle, repeat, speed, resume | Yes | Yes |
| Video player | Custom gesture player + PiP | `AVPlayerViewController` + PiP |
| Device library: audio | MediaStore music (`content://`) | Music app songs that are downloaded and DRM-free (`ipod-library://`) |
| Device library: video | MediaStore videos (`content://`) | Photos library videos (`ph://`) |
| Partial library access | Android 14 "Select videos" → `limited` | "Selected Photos" → `limited` |
| Playback resumption after restart | Yes (system UI resume card, media buttons) | – |

## Installation

```bash
composer require protendai/nativephp-media
php artisan native:plugin:register protendai/nativephp-media
```

The plugin has native code: rebuild the app afterwards (`php artisan native:run`). `native:jump` is not enough.

What the plugin declares for every app:

- **Android**: the `PlaybackService` media service (`FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`), Media3's `MediaButtonReceiver`, and the `VideoActivity` player with picture-in-picture switched on by the plugin's `post_compile` hook (`nativephp:media:configure`). No notification permission is needed: media-session notifications are exempt.
- **iOS**: the `audio` background mode.

### Device library access (opt-in)

Reading the phone's own music and videos needs `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO` on Android (Google Play asks you to justify these) and the Music / Photos usage descriptions on iOS. They are **off by default**: an app that only plays its own files or URLs never declares them.

Turn them on in `.env` and rebuild:

```dotenv
NATIVEPHP_MEDIA_LIBRARY=true     # audio and video
# NATIVEPHP_MEDIA_LIBRARY=audio  # music only
# NATIVEPHP_MEDIA_LIBRARY=video  # videos only
```

or publish the config (`php artisan vendor:publish --tag=nativephp-media-config`) and set `library` in `config/nativephp-media.php`. The build hook then adds:

| Setting | Android | iOS Info.plist |
|---|---|---|
| `audio` | `READ_MEDIA_AUDIO` | `NSAppleMusicUsageDescription` |
| `video` | `READ_MEDIA_VIDEO`, `READ_MEDIA_VISUAL_USER_SELECTED` (Android 14 partial access) | `NSPhotoLibraryUsageDescription` |
| either | `READ_EXTERNAL_STORAGE` (`maxSdkVersion="32"`, Android 12 and older) | |

Switching it off again removes what the hook added (never permissions the app or another plugin declares). The iOS texts default to generic wording; change them with `NATIVEPHP_MEDIA_AUDIO_USAGE` / `NATIVEPHP_MEDIA_VIDEO_USAGE`, `nativephp-media.usage_descriptions`, or your app's `config('nativephp.permissions')` (which always wins).

Without library access, `requestPermission()` answers `PermissionResult(granted: false)` at once, without a dialog.

## How background playback works

**PHP doesn't run in the background, and it doesn't need to.** PHP is the remote control: it hands a queue to the native player, which keeps playing (and keeps the notification and lock-screen controls in sync) while the app is backgrounded or the screen is off. When the user comes back, `Media::state()` tells you where playback is; events tell you what changed while the app was in front.

## Usage (PHP)

### Music

```php
use Protendai\Media\Facades\Media;

Media::setQueue([
    ['id' => 'song-1', 'uri' => storage_path('app/music/one.mp3'), 'title' => 'One', 'artist' => 'Band', 'type' => 'audio'],
    ['id' => 'song-2', 'uri' => 'https://example.com/two.mp3', 'title' => 'Two', 'type' => 'audio'],
], startIndex: 0);

Media::toggle();        // play / pause
Media::next();
Media::setShuffle(true);
Media::setRepeat('all');
Media::setSpeed(1.25);
Media::seek(90_000);    // milliseconds
```

### Video

```php
Media::openVideo([
    ['id' => 'clip-1', 'uri' => storage_path('app/videos/clip.mp4'), 'title' => 'Holiday'],
]);
```

The video player plays through the same session, so it also gets lock-screen controls and resume.

### A now-playing screen (EDGE component)

```php
use Native\Mobile\Attributes\On;
use Native\Mobile\Attributes\Poll;
use Native\Mobile\Edge\NativeComponent;
use Protendai\Media\Events\StateChanged;
use Protendai\Media\Events\TrackChanged;
use Protendai\Media\Facades\Media;

class NowPlaying extends NativeComponent
{
    public array $player = [];

    public function mount(): void
    {
        $this->player = Media::state();
    }

    // Events for real changes, a 1 s poll for the progress bar.
    #[Poll(1000)]
    #[On(StateChanged::class)]
    #[On(TrackChanged::class)]
    public function refresh(): void
    {
        $this->player = Media::state(); // playing, title, artist, positionMs, durationMs, ...
    }

    public function toggle(): void
    {
        Media::toggle();
    }
}
```

In Livewire components use `#[OnNative(StateChanged::class)]` (`Native\Mobile\Attributes\OnNative`) the same way.

### The phone's own media

Needs [library access](#device-library-access-opt-in).

```php
use Native\Mobile\Attributes\On;
use Protendai\Media\Events\PermissionResult;

public array $songs = [];

public function mount(): void
{
    Media::hasPermission('audio') ? $this->loadMore() : Media::requestPermission('audio');
}

#[On(PermissionResult::class)]
public function onPermission(bool $granted = false, string $type = 'audio', bool $limited = false): void
{
    if ($granted) {
        $this->loadMore();
    }
}

public function loadMore(): void
{
    $page = Media::libraryPage('audio', 60, count($this->songs)); // ['items', 'total', 'offset']
    $this->songs = [...$this->songs, ...$page['items']];
}

public function play(int $index): void
{
    Media::setQueue($this->songs, $index); // library items are valid queue items
}
```

```php
$tags = Media::probe($song['uri'], storage_path('app/covers/1.jpg')); // writes the cover as JPEG
```

## Usage (JavaScript)

For Inertia (Vue / React) and other SPA front ends. Import from the package's `resources/js`, for example with a Vite alias:

```js
// vite.config.js
import path from 'node:path';

export default defineConfig({
    resolve: {
        alias: { '@protendai/nativephp-media': path.resolve(__dirname, 'vendor/protendai/nativephp-media/resources/js/index.js') },
    },
});
```

```js
import { SetQueue, Toggle, Next, OpenVideo, State, Library, HasPermission, RequestPermission,
         OnStateChanged, OnTrackChanged, OnPermissionResult, Events, onMediaEvent } from '@protendai/nativephp-media';

await SetQueue([{ id: 'a', uri: '/path/one.mp3', title: 'One', type: 'audio' }], { startIndex: 0 });
await Toggle();

const state = await State(); // { playing, title, positionMs, ... }

const stop = OnStateChanged((state) => { /* update the UI */ });
OnTrackChanged(({ id, title }) => {});
onMediaEvent(Events.QueueEnded, () => {});
stop(); // stop listening

if (!(await HasPermission('video')).granted) {
    OnPermissionResult(({ granted, limited }) => {});
    await RequestPermission('video');
}
const { items, total } = await Library('video', { limit: 60, offset: 0 });
```

Every bridge function has a same-named export (`SetQueue`, `Play`, `Pause`, `Toggle`, `Next`, `Previous`, `SkipTo`, `Seek`, `SetShuffle`, `SetRepeat`, `SetSpeed`, `Stop`, `State`, `OpenVideo`, `Probe`, `Library`, `RequestPermission`, `HasPermission`); types are in `index.d.ts`. Calls reject with an `Error` when the bridge reports one.

## API

| Method | Returns | Notes |
|---|---|---|
| `setQueue(array $items, int $startIndex = 0, int $startPosition = -1, bool $play = true)` | `bool` | Replaces the queue. `$startPosition` in ms; `-1` resumes the saved position. → `TrackChanged`, `StateChanged` |
| `play(?array $item = null)` | `bool` | Resume (a finished queue starts again), or play one item |
| `pause()` / `toggle()` | `bool` | → `StateChanged` |
| `next()` / `previous()` | `bool` | Next item from its start. Previous restarts the item when past 3 s, otherwise goes back one. → `TrackChanged` |
| `skipTo(int $index)` | `bool` | Plays that item, resuming its saved position. → `TrackChanged` |
| `seek(int $position)` | `bool` | Milliseconds |
| `setShuffle(bool $enabled)` | `bool` | |
| `setRepeat(string $mode)` | `bool` | `'off'`, `'one'`, `'all'` |
| `setSpeed(float $speed)` | `bool` | Clamped to 0.25–3.0 |
| `stop()` | `bool` | Stops, clears the queue, removes the notification |
| `state()` | `array` | Snapshot, see below. Idle defaults when nothing is loaded |
| `isActive()` | `bool` | A queue is loaded (playing or paused) |
| `openVideo(array $items, int $startIndex = 0, int $startPosition = -1)` | `bool` | Full-screen video player |
| `probe(string $uri, ?string $artworkDest = null)` | `array` | `ok, title, artist, album, durationMs, width, height, hasArtwork`. `ok` is false for missing / unreadable files |
| `libraryPage(string $type = 'audio', int $limit = 60, int $offset = 0)` | `array` | `['items', 'total', 'offset']`, newest first |
| `library(string $type = 'audio', int $limit = 500, int $offset = 0)` | `array` | The items of one page |
| `hasPermission(string $type = 'audio')` | `bool` | Limited access counts as granted |
| `permissionStatus(string $type = 'audio')` | `array` | `['granted' => bool, 'limited' => bool]` |
| `requestPermission(string $type = 'audio')` | `void` | → `PermissionResult` |

`bool` results are `false` when the native side refused (for example no usable item) or outside a device build.

### Queue item

| Key | Type | |
|---|---|---|
| `id` | string | Stable key, used for resume. Defaults to the uri |
| `uri` | string | Required. File path, `file://`, `http(s)://`, `content://` (Android), `ipod-library://` / `ph://` (iOS library items) |
| `title`, `artist`, `album` | ?string | Shown in the notification and on the lock screen |
| `artwork` | ?string | File path or URL (iOS lock screen shows local files only) |
| `type` | `'audio'` \| `'video'` | |

### State

`playing` bool · `buffering` bool · `index` int (`-1` = nothing loaded) · `count` int · `id` · `title` · `artist` · `artwork` · `type` · `positionMs` int · `durationMs` int · `shuffle` bool · `repeat` `'off'|'one'|'all'` · `speed` float · `error` ?string

### Library item

`id` (`audio:…` / `video:…`) · `uri` · `title` · `artist` · `album` · `durationMs` · `size` (0 on iOS) · `dateAdded` (Unix seconds) · `folder` (Android relative path; iOS album / "Photos") · `mime`

## Events

All under `Protendai\Media\Events`. Give handler parameters defaults: iOS leaves null values out of payloads.

| Event | Payload | When |
|---|---|---|
| `StateChanged` | every state key | Play/pause, buffering, track, queue, shuffle, repeat, speed, error. Not for position-only changes |
| `TrackChanged` | `?string $id, int $index, ?string $title, ?string $artist, ?string $type` | Another item became current (auto-advance, next/previous/skipTo, remote controls) |
| `PlaybackError` | `?string $id, ?string $message` | The item couldn't be played (unreadable file, unsupported format, network). Also when the Android playback service can't be started |
| `QueueEnded` | none | The last item finished and repeat is off |
| `PermissionResult` | `bool $granted, string $type, bool $limited = false` | Answer to `requestPermission()` |

## Resume rules

Positions are saved per item `id`: every 5 s while playing, on pause, and for the outgoing item whenever the current item changes (next, previous, skipTo, a new queue, or the notification / lock screen / Bluetooth controls). Within 5 s of the end counts as finished and clears it.

`setQueue()` / `openVideo()` with `startPosition = -1` and `skipTo()` resume an item's saved position. `next()`, `previous()` and automatic advance start items from the beginning. Both platforms behave the same.

## Errors

- Commands return `false` when the native side refused them; they never throw.
- `probe()` returns `ok: false` (with an `error` key from the native side: `not_found`, `unreadable`, `invalid_uri`, `timeout`) for files it can't read.
- Playback failures arrive as `PlaybackError`, and `state()['error']` holds the last message until the next track.
- `requestPermission()` without library access enabled answers `PermissionResult(granted: false)` and the native result has `error: library_disabled`.

## Testing

The service provider adds FakeBridge helpers:

```php
Native::fakeBridge()->withMedia(
    state: ['playing' => true, 'index' => 0, 'count' => 3, 'title' => 'One', 'artist' => 'Band'],
    library: [['id' => 'audio:42', 'uri' => 'content://media/external/audio/media/42', 'title' => 'Two']],
    permission: true,
);

Native::visit('/now-playing')
    ->assertSee('One')
    ->tap('Next')
    ->assertNativeCalled('Media.Next');

Native::fakeBridge()->assertMediaQueued(fn (array $p) => $p['startIndex'] === 0);
Native::fakeBridge()->assertVideoOpened();
Native::fakeBridge()->assertMediaPermissionRequested('audio');
```

The plugin's own tests: `composer install && composer test`.

## Limitations

- One player per app: video and music share it, so starting one replaces the other.
- Local files must be readable by the app (`storage_path()`, your own MediaStore entries, or media you have permission for).
- iOS library items (`ipod-library://`, `ph://`) play and probe but are not file paths; Apple Music streaming and DRM-protected songs are not listed. Remote artwork isn't shown on the iOS lock screen.
- Android: events that happen while the app's activity is gone (music playing after the user closed the app) are held and delivered when the app is opened again: all of them up to 16, plus the latest state. PHP itself does not run in the background.
- Android: other apps and system controllers (system UI, Bluetooth, Wear, Auto) can play, pause, seek and skip, but cannot load their own media into your player.
- Android: dismissing the picture-in-picture window, or turning the screen off while in it, pauses the video and closes the window.
- iOS playback resumption from the system (after the app was killed) is not offered.
- Picture-in-picture on Android needs the `post_compile` hook to run (it does on `native:run` / `native:package`).

## Support

Questions and bugs: [GitHub issues](https://github.com/protendai/nativephp-media/issues).

## License

MIT. See [LICENSE](LICENSE).
