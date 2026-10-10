# Media for NativePHP Mobile

A **proper media player** for NativePHP Mobile apps (Android and iOS): music that keeps playing with the app closed, with controls on the lock screen, in the notification shade and on Bluetooth headphones, plus a full-screen gesture video player with picture-in-picture. Built on Android's Media3 (the engine behind YouTube Music's and Google's own players).

- **Background playback** in a real Android media service (foreground service, `mediaPlayback` type)
- **Lock screen, notification and Bluetooth/headset controls** for free via a `MediaSession`
- **Queues**: next / previous / skip-to, **shuffle**, **repeat** (off / one / all), **speed** 0.25×–3×
- **Resume where you left off**, per item, automatically
- **Plays nicely with the phone**: pauses for calls and other apps (audio focus), pauses when headphones are unplugged
- **Gesture video player**: double-tap ±10 s, drag to scrub, swipe for brightness (left) and volume (right), speed button, **picture-in-picture**, auto-landscape
- **Device media library**: list the phone's music and videos (MediaStore) with titles, artists, durations
- **Probe**: read tags, duration and dimensions; extract embedded cover art or a video frame

| Platform | Support |
|---|---|
| Android 10+ (API 29) | Full |
| iOS 18+ | Full, with iOS-native pieces: `AVPlayer` queue, background audio, lock screen / Control Center, the standard iOS video player with picture-in-picture. `library()` lists the Music app's downloaded DRM-free songs (`ipod-library://` uris) and Photos videos (`ph://` uris); these play and probe but are not file paths. |

## How background playback works

**PHP doesn't run in the background, and it doesn't need to.** PHP is the remote control: it hands a queue to a native media service, and that service keeps playing (and keeps the notification and lock-screen controls in sync) when your app is backgrounded or the screen is off. When the user comes back, `Media::state()` tells you exactly where playback is; events tell you what changed while the app was in front.

## Install

```bash
composer require protendai/nativephp-media
php artisan native:plugin:register protendai/nativephp-media
```

Rebuild the app (`php artisan native:run`). The plugin declares everything it needs:

- the `PlaybackService` media service (`FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `WAKE_LOCK`)
- the `VideoActivity` player, with picture-in-picture switched on by a `post_compile` hook
- `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO` (storage on Android 12 and older), requested only when you call `requestPermission()`

No notification permission is needed: media-session notifications are exempt.

## Quick start

### Music

```php
use Protendai\Media\Facades\Media;

Media::setQueue([
    ['id' => 'song-1', 'uri' => storage_path('app/music/one.mp3'), 'title' => 'One', 'artist' => 'Band', 'type' => 'audio'],
    ['id' => 'song-2', 'uri' => 'content://media/external/audio/media/42', 'title' => 'Two', 'type' => 'audio'],
], startIndex: 0);

Media::toggle();        // play / pause
Media::next();
Media::setShuffle(true);
Media::setRepeat('all');
Media::setSpeed(1.25);
Media::seek(90_000);    // milliseconds
```

`startPosition` defaults to `-1`, which resumes the start item where it was last left (positions are saved natively per `id`).

### Video

```php
Media::openVideo([
    ['id' => 'clip-1', 'uri' => storage_path('app/videos/clip.mp4'), 'title' => 'Holiday'],
]);
```

The full-screen player plays through the same session, so it also gets lock-screen controls and resume.

### A mini player / Now Playing screen

```php
use Native\Mobile\Attributes\OnNative;
use Native\Mobile\Attributes\Poll;
use Protendai\Media\Events\TrackChanged;

public array $player = [];

#[Poll(1000)]
public function refreshPlayer(): void
{
    $this->player = Media::state(); // playing, title, artist, positionMs, durationMs, ...
}

#[OnNative(TrackChanged::class)]
public function onTrackChanged(?string $id, int $index, ?string $title): void
{
    $this->refreshPlayer();
}
```

### The phone's own media

```php
if (! Media::hasPermission('audio')) {
    Media::requestPermission('audio'); // → PermissionResult event
}

$songs = Media::library('audio');   // [['id', 'uri' => 'content://…', 'title', 'artist', 'album', 'durationMs', 'size', 'dateAdded', 'folder', 'mime'], …]
$tags = Media::probe($songs[0]['uri'], storage_path('app/covers/1.jpg'));
```

## API

| Method | Returns | Notes |
|---|---|---|
| `setQueue(array $items, int $startIndex = 0, int $startPosition = -1, bool $play = true)` | `bool` | Replaces the queue. `-1` resumes the saved position. → `TrackChanged`, `StateChanged` |
| `play(?array $item = null)` | `bool` | Resume, or play one item |
| `pause()` / `toggle()` | `bool` | → `StateChanged` |
| `next()` / `previous()` / `skipTo(int $index)` | `bool` | → `TrackChanged` |
| `seek(int $position)` | `bool` | Milliseconds |
| `setShuffle(bool $enabled)` | `bool` | |
| `setRepeat(string $mode)` | `bool` | `'off'`, `'one'`, `'all'` |
| `setSpeed(float $speed)` | `bool` | Clamped to 0.25–3.0 |
| `stop()` | `bool` | Stops, clears the queue, removes the notification |
| `state()` | `array` | Snapshot, see below. Idle defaults when nothing is loaded |
| `isActive()` | `bool` | A queue is loaded (playing or paused) |
| `openVideo(array $items, int $startIndex = 0, int $startPosition = -1)` | `bool` | Full-screen gesture player |
| `probe(string $uri, ?string $artworkDest = null)` | `array` | `ok, title, artist, album, durationMs, width, height, hasArtwork` |
| `library(string $type = 'audio', int $limit = 500, int $offset = 0)` | `array` | Device media from MediaStore, newest first (needs permission) |
| `libraryPage(string $type = 'audio', int $limit = 60, int $offset = 0)` | `array` | `['items', 'total', 'offset']`: page through big libraries without crossing thousands of rows over the bridge |
| `hasPermission(string $type = 'audio')` | `bool` | |
| `requestPermission(string $type = 'audio')` | `void` | → `PermissionResult` |

### Queue item

| Key | Type | |
|---|---|---|
| `id` | string | Stable key, used for resume. Required |
| `uri` | string | File path, `content://` URI or `http(s)` URL. Required |
| `title`, `artist`, `album` | ?string | Shown in the notification and on the lock screen |
| `artwork` | ?string | File path or URL |
| `type` | `'audio'` \| `'video'` | |

### State

`playing` bool · `buffering` bool · `index` int (`-1` = nothing loaded) · `count` int · `id` · `title` · `artist` · `artwork` · `type` · `positionMs` int · `durationMs` int · `shuffle` bool · `repeat` `'off'|'one'|'all'` · `speed` float · `error` ?string

## Events

All under `Protendai\Media\Events`:

| Event | Properties |
|---|---|
| `StateChanged` | every state key above |
| `TrackChanged` | `?string $id, int $index, ?string $title, ?string $artist, ?string $type` |
| `PlaybackError` | `?string $id, ?string $message` |
| `QueueEnded` | none |
| `PermissionResult` | `bool $granted, string $type` |

## Testing

The service provider adds a `FakeBridge` helper that answers every `Media.*` call:

```php
Native::fakeBridge()->withMedia(
    state: ['playing' => true, 'index' => 0, 'count' => 3, 'title' => 'One', 'artist' => 'Band'],
    library: [['id' => '42', 'uri' => 'content://media/external/audio/media/42', 'title' => 'Two']],
);

Native::visit('/now-playing')
    ->assertSee('One')
    ->tap('Next')
    ->assertNativeCalled('Media.Next');
```

## Notes

- One player per app: video and music share the same session, so starting one replaces the other.
- Local files must be readable by the app (`storage_path()`, your own MediaStore entries, or media you have permission for).
- Positions are saved per `id` while playing, on pause and on track change, and are cleared when an item finishes.
- Picture-in-picture needs the `post_compile` hook to run (it does automatically on `native:run` / `native:package`).
