<?php

namespace Protendai\Media\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * Answer to Media::requestPermission($type). $limited: the user allowed only
 * some items (iOS "Selected Photos", Android 14 "Select videos").
 */
class PermissionResult
{
    use Dispatchable;

    public function __construct(
        public bool $granted = false,
        public string $type = 'audio',
        public bool $limited = false,
    ) {}
}
