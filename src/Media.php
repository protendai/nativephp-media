<?php

namespace Protendai\Media;

/**
 * A proper media player for NativePHP Mobile (Android).
 *
 * PHP is the remote control: a native Media3 service owns the player, keeps
 * playing with the app in the background, and drives the notification,
 * lock-screen and Bluetooth controls. Every method here is a thin,
 * synchronous bridge call; changes come back as events under
 * Protendai\Media\Events, and state() reads a snapshot the native side keeps.
 *
 * A queue item is:
 *   ['id' => 'stable-key', 'uri' => '/path/or/content://...', 'title' => ?, 'artist' => ?,
 *    'album' => ?, 'artwork' => ?path|url, 'type' => 'audio'|'video']
 */
class Media
{
    /**
     * The state snapshot keys and their idle values.
     *
     * @var array<string, mixed>
     */
    public const IDLE_STATE = [
        'playing' => false,
        'buffering' => false,
        'index' => -1,
        'count' => 0,
        'id' => null,
        'title' => null,
        'artist' => null,
        'artwork' => null,
        'type' => null,
        'positionMs' => 0,
        'durationMs' => 0,
        'shuffle' => false,
        'repeat' => 'off',
        'speed' => 1.0,
        'error' => null,
    ];

    /**
     * Replace the queue and (by default) start playing.
     *
     * @param  list<array<string, mixed>>  $items
     * @param  int  $startPosition  Milliseconds; -1 resumes the saved position for the start item's id.
     */
    public function setQueue(array $items, int $startIndex = 0, int $startPosition = -1, bool $play = true): bool
    {
        return $this->ok($this->call('Media.SetQueue', [
            'items' => array_values($items),
            'startIndex' => $startIndex,
            'startPosition' => $startPosition,
            'play' => $play,
        ]));
    }

    /**
     * Play a single item (shorthand for a one-item queue).
     *
     * @param  array<string, mixed>  $item
     */
    public function play(?array $item = null): bool
    {
        return $item === null
            ? $this->ok($this->call('Media.Play'))
            : $this->setQueue([$item]);
    }

    public function pause(): bool
    {
        return $this->ok($this->call('Media.Pause'));
    }

    public function toggle(): bool
    {
        return $this->ok($this->call('Media.Toggle'));
    }

    public function next(): bool
    {
        return $this->ok($this->call('Media.Next'));
    }

    public function previous(): bool
    {
        return $this->ok($this->call('Media.Previous'));
    }

    public function skipTo(int $index): bool
    {
        return $this->ok($this->call('Media.SkipTo', ['index' => $index]));
    }

    /**
     * @param  int  $position  Milliseconds.
     */
    public function seek(int $position): bool
    {
        return $this->ok($this->call('Media.Seek', ['position' => max(0, $position)]));
    }

    public function setShuffle(bool $enabled): bool
    {
        return $this->ok($this->call('Media.SetShuffle', ['enabled' => $enabled]));
    }

    /**
     * @param  'off'|'one'|'all'  $mode
     */
    public function setRepeat(string $mode): bool
    {
        return $this->ok($this->call('Media.SetRepeat', ['mode' => in_array($mode, ['off', 'one', 'all'], true) ? $mode : 'off']));
    }

    /**
     * @param  float  $speed  0.25 – 3.0
     */
    public function setSpeed(float $speed): bool
    {
        return $this->ok($this->call('Media.SetSpeed', ['speed' => max(0.25, min(3.0, $speed))]));
    }

    /**
     * Stop playback and clear the queue (removes the notification).
     */
    public function stop(): bool
    {
        return $this->ok($this->call('Media.Stop'));
    }

    /**
     * The current playback snapshot; idle defaults when nothing is loaded
     * (or outside a device build).
     *
     * @return array{playing: bool, buffering: bool, index: int, count: int, id: ?string, title: ?string, artist: ?string, artwork: ?string, type: ?string, positionMs: int, durationMs: int, shuffle: bool, repeat: string, speed: float, error: ?string}
     */
    public function state(): array
    {
        $state = array_merge(self::IDLE_STATE, array_intersect_key($this->decode($this->call('Media.State')), self::IDLE_STATE));

        $state['playing'] = (bool) $state['playing'];
        $state['buffering'] = (bool) $state['buffering'];
        $state['index'] = (int) $state['index'];
        $state['count'] = (int) $state['count'];
        $state['positionMs'] = (int) $state['positionMs'];
        $state['durationMs'] = (int) $state['durationMs'];
        $state['shuffle'] = (bool) $state['shuffle'];
        $state['speed'] = (float) $state['speed'];

        return $state;
    }

    /**
     * Whether a queue is loaded (playing or paused).
     */
    public function isActive(): bool
    {
        return $this->state()['index'] >= 0;
    }

    /**
     * Open the full-screen gesture video player on these items, played
     * through the same session (picture-in-picture, lock-screen controls).
     *
     * @param  list<array<string, mixed>>  $items
     */
    public function openVideo(array $items, int $startIndex = 0, int $startPosition = -1): bool
    {
        return $this->ok($this->call('Media.OpenVideo', [
            'items' => array_values(array_map(fn (array $item): array => $item + ['type' => 'video'], $items)),
            'startIndex' => $startIndex,
            'startPosition' => $startPosition,
        ]));
    }

    /**
     * Read a file's tags. With $artworkDest, the embedded cover (audio) or a
     * frame (video) is written there as a JPEG.
     *
     * @return array{ok: bool, title: ?string, artist: ?string, album: ?string, durationMs: int, width: int, height: int, hasArtwork: bool}
     */
    public function probe(string $uri, ?string $artworkDest = null): array
    {
        $result = $this->decode($this->call('Media.Probe', ['uri' => $uri, 'artworkDest' => $artworkDest]));

        return [
            'ok' => (bool) ($result['ok'] ?? false),
            'title' => $result['title'] ?? null,
            'artist' => $result['artist'] ?? null,
            'album' => $result['album'] ?? null,
            'durationMs' => (int) ($result['durationMs'] ?? 0),
            'width' => (int) ($result['width'] ?? 0),
            'height' => (int) ($result['height'] ?? 0),
            'hasArtwork' => (bool) ($result['hasArtwork'] ?? false),
        ];
    }

    /**
     * The device's audio or video files (MediaStore). Needs permission
     * first: see hasPermission() / requestPermission().
     *
     * @param  'audio'|'video'  $type
     * @return list<array{id: string, uri: string, title: ?string, artist: ?string, album: ?string, durationMs: int, size: int, dateAdded: int, folder: ?string, mime: ?string}>
     */
    public function library(string $type = 'audio', int $limit = 500, int $offset = 0): array
    {
        return $this->libraryPage($type, $limit, $offset)['items'];
    }

    /**
     * One page of the device library plus the total count, for paging
     * through libraries with thousands of files.
     *
     * @param  'audio'|'video'  $type
     * @return array{items: list<array<string, mixed>>, total: int, offset: int}
     */
    public function libraryPage(string $type = 'audio', int $limit = 60, int $offset = 0): array
    {
        $result = $this->decode($this->call('Media.Library', ['type' => $type, 'limit' => $limit, 'offset' => $offset]));
        $items = array_values(array_filter($result['items'] ?? [], 'is_array'));

        return [
            'items' => $items,
            // Older native builds don't report a total; assume no more pages.
            'total' => (int) ($result['total'] ?? ($offset + count($items))),
            'offset' => $offset,
        ];
    }

    /**
     * @param  'audio'|'video'  $type
     */
    public function hasPermission(string $type = 'audio'): bool
    {
        return (bool) ($this->decode($this->call('Media.HasPermission', ['type' => $type]))['granted'] ?? false);
    }

    /**
     * Prompt for READ_MEDIA_AUDIO / READ_MEDIA_VIDEO (or storage on Android 12
     * and older). Answers with a PermissionResult event.
     *
     * @param  'audio'|'video'  $type
     */
    public function requestPermission(string $type = 'audio'): void
    {
        $this->call('Media.RequestPermission', ['type' => $type]);
    }

    private function ok(?string $result): bool
    {
        return (bool) ($this->decode($result)['ok'] ?? false);
    }

    /**
     * @return array<string, mixed>
     */
    private function decode(?string $result): array
    {
        $decoded = $result !== null ? json_decode($result, true) : null;

        return is_array($decoded) ? $decoded : [];
    }

    /**
     * @param  array<string, mixed>  $params
     */
    private function call(string $method, array $params = []): ?string
    {
        if (! function_exists('nativephp_call')) {
            return null;
        }

        return nativephp_call($method, json_encode($params) ?: '{}');
    }
}
