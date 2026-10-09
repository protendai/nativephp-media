<?php

namespace Protendai\Media\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static bool setQueue(array $items, int $startIndex = 0, int $startPosition = -1, bool $play = true)
 * @method static bool play(?array $item = null)
 * @method static bool pause()
 * @method static bool toggle()
 * @method static bool next()
 * @method static bool previous()
 * @method static bool skipTo(int $index)
 * @method static bool seek(int $position)
 * @method static bool setShuffle(bool $enabled)
 * @method static bool setRepeat(string $mode)
 * @method static bool setSpeed(float $speed)
 * @method static bool stop()
 * @method static array state()
 * @method static bool isActive()
 * @method static bool openVideo(array $items, int $startIndex = 0, int $startPosition = -1)
 * @method static array probe(string $uri, ?string $artworkDest = null)
 * @method static array libraryPage(string $type = 'audio', int $limit = 60, int $offset = 0)
 * @method static array library(string $type = 'audio', int $limit = 500, int $offset = 0)
 * @method static bool hasPermission(string $type = 'audio')
 * @method static void requestPermission(string $type = 'audio')
 *
 * @see \Protendai\Media\Media
 */
class Media extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return 'protendai.media';
    }
}
