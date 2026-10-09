<?php

namespace Protendai\Media\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * A different queue item became current (auto-advance, next/previous, skipTo, or a remote control).
 */
class TrackChanged
{
    use Dispatchable;

    public function __construct(
        public ?string $id = null,
        public int $index = -1,
        public ?string $title = null,
        public ?string $artist = null,
        public ?string $type = null,
    ) {}
}
