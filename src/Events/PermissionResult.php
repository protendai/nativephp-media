<?php

namespace Protendai\Media\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * Answer to Media::requestPermission($type).
 */
class PermissionResult
{
    use Dispatchable;

    public function __construct(
        public bool $granted = false,
        public string $type = 'audio',
    ) {}
}
