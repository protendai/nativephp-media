<?php

namespace Protendai\Media\Events;

use Illuminate\Foundation\Events\Dispatchable;

/**
 * The last item finished and repeat is off.
 */
class QueueEnded
{
    use Dispatchable;

    public function __construct() {}
}
