## protendai/nativephp-media

Media player for NativePHP Mobile v4 on iOS and Android: background audio with lock-screen / notification / Bluetooth controls, queues, shuffle, repeat, speed, per-item resume, a full-screen video player with picture-in-picture, and opt-in access to the phone's own music and videos. Media3 on Android, AVPlayer on iOS.

PHP is the remote control: playback runs natively and keeps going with the app in the background. Every method is a quick synchronous bridge call; read `Media::state()` for a snapshot and listen to events for changes. PHP does not run in the background, so re-read `state()` when a screen mounts.

### Methods (facade `Protendai\Media\Facades\Media`)

- `setQueue(array $items, int $startIndex = 0, int $startPosition = -1, bool $play = true): bool` replaces the queue. `$startPosition` in ms; `-1` resumes the start item's saved position.
- `play(?array $item = null): bool` resumes (a finished queue restarts), or plays one item as a one-item queue. `pause()`, `toggle()`, `stop()` (clears the queue and the notification): `bool`.
- `next()`, `previous()` (restarts when past 3 s), `skipTo(int $index)` (plays, resuming that item): `bool`. `next`/`previous`/auto-advance start items from 0.
- `seek(int $ms)`, `setShuffle(bool)`, `setRepeat('off'|'one'|'all')`, `setSpeed(float 0.25–3.0)`: `bool`.
- `state(): array` keys `playing, buffering, index (-1 = nothing loaded), count, id, title, artist, artwork, type, positionMs, durationMs, shuffle, repeat, speed, error`. `isActive(): bool`.
- `openVideo(array $items, int $startIndex = 0, int $startPosition = -1): bool` opens the full-screen video player (PiP) on the same player; items are typed `video`.
- `probe(string $uri, ?string $artworkDest = null): array` → `ok, title, artist, album, durationMs, width, height, hasArtwork`; with `$artworkDest`, embedded cover art or a video frame is written there as JPEG. `ok` is false for missing or unreadable files.
- `libraryPage(string $type = 'audio', int $limit = 60, int $offset = 0): array` → `items, total, offset`; `library(...)` returns just the items. Page through big libraries: never fetch everything.
- `hasPermission(string $type = 'audio'): bool`, `permissionStatus($type): array{granted, limited}`, `requestPermission($type): void` (answers with `PermissionResult`).

Queue item: `['id' => stable key (resume), 'uri' => file path | content:// | ipod-library:// | ph:// | http(s) URL, 'title' => ?, 'artist' => ?, 'album' => ?, 'artwork' => ?path|url, 'type' => 'audio'|'video']`. Always pass a stable `id`.

### Events (`Protendai\Media\Events\*`; `#[On(...)]` from `Native\Mobile\Attributes\On` in EDGE components, `#[OnNative(...)]` in Livewire)

- `StateChanged`: every state key (not fired for position-only changes; poll `state()` for a progress bar).
- `TrackChanged`: `?string $id, int $index, ?string $title, ?string $artist, ?string $type`.
- `PlaybackError`: `?string $id, ?string $message`.
- `QueueEnded`: no payload (last item finished, repeat off).
- `PermissionResult`: `bool $granted, string $type, bool $limited = false`.
- Give handler parameters defaults; iOS leaves null values out of payloads.

### Device library is opt-in

- Off by default, so apps that only play their own files or URLs declare no media-library permissions. Enable with `NATIVEPHP_MEDIA_LIBRARY=true` (or `audio` / `video`) in `.env` or `config/nativephp-media.php` (`php artisan vendor:publish --tag=nativephp-media-config`), then rebuild with `php artisan native:run`.
- Android then declares `READ_MEDIA_AUDIO` / `READ_MEDIA_VIDEO` + `READ_MEDIA_VISUAL_USER_SELECTED` (+ `READ_EXTERNAL_STORAGE` ≤ Android 12); iOS gets `NSAppleMusicUsageDescription` / `NSPhotoLibraryUsageDescription` (texts in `nativephp-media.usage_descriptions`, or `nativephp.permissions` which wins).
- Without it, `requestPermission()` answers `PermissionResult(granted: false)` immediately.
- iOS lists only downloaded DRM-free Music songs (`ipod-library://`) and Photos videos (`ph://`); they play and probe but are not file paths.

### Testing (FakeBridge macros)

`Native::fakeBridge()->withMedia(state: [...], library: [...], permission: true, limited: false)` answers every `Media.*` call. Assert with `assertMediaQueued(fn (array $p) => ...)`, `assertVideoOpened(...)`, `assertMediaPermissionRequested('audio')`, or `assertCalled('Media.Next')`.

@verbatim
<code-snippet name="Now playing screen (EDGE component)" lang="php">
use Native\Mobile\Attributes\On;
use Native\Mobile\Attributes\Poll;
use Protendai\Media\Events\StateChanged;
use Protendai\Media\Events\TrackChanged;
use Protendai\Media\Facades\Media;

public array $player = [];

public function mount(): void
{
    $this->player = Media::state();
}

#[Poll(1000)]
#[On(StateChanged::class)]
#[On(TrackChanged::class)]
public function refresh(): void
{
    $this->player = Media::state();
}

public function playAlbum(array $tracks, int $index): void
{
    Media::setQueue(array_map(fn ($t) => [
        'id' => 'track-'.$t['id'], 'uri' => $t['path'], 'title' => $t['title'], 'artist' => $t['artist'], 'type' => 'audio',
    ], $tracks), $index);
}
</code-snippet>

<code-snippet name="Device library with permission (EDGE component)" lang="php">
use Native\Mobile\Attributes\On;
use Protendai\Media\Events\PermissionResult;
use Protendai\Media\Facades\Media;

public array $songs = [];
public int $total = 0;

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
    $page = Media::libraryPage('audio', 60, count($this->songs));
    $this->songs = [...$this->songs, ...$page['items']];
    $this->total = $page['total'];
}
</code-snippet>

<code-snippet name="JavaScript (Inertia Vue / React)" lang="js">
import { SetQueue, Toggle, OpenVideo, State, OnStateChanged, Events, onMediaEvent } from '@protendai/nativephp-media';

await SetQueue([{ id: 'a', uri: '/path/one.mp3', title: 'One', type: 'audio' }], { startIndex: 0 });
const stop = OnStateChanged((state) => console.log(state.playing, state.title));
onMediaEvent(Events.QueueEnded, () => console.log('done'));
</code-snippet>
@endverbatim
