<?php

namespace Protendai\Media\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * The player could not play an item ($id) — unreadable file, unsupported codec, network error.
 */
class PlaybackError
{
    use Dispatchable;

    public function __construct(
        public ?string $id = null,
        public ?string $message = null,
    ) {}
}
