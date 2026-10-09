<?php

namespace Protendai\Media\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * Playback state changed (play/pause, buffering, shuffle, repeat, speed, queue, error). Same keys as Media::state().
 */
class StateChanged
{
    use Dispatchable;

    public function __construct(
        public bool $playing = false,
        public bool $buffering = false,
        public int $index = -1,
        public int $count = 0,
        public ?string $id = null,
        public ?string $title = null,
        public ?string $artist = null,
        public ?string $artwork = null,
        public ?string $type = null,
        public int $positionMs = 0,
        public int $durationMs = 0,
        public bool $shuffle = false,
        public string $repeat = 'off',
        public float $speed = 1.0,
        public ?string $error = null,
    ) {}
}
