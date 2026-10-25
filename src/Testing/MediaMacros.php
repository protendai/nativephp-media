<?php

namespace Protendai\Media\Testing;

use Native\Mobile\Testing\FakeBridge;
use Protendai\Media\Media;

/**
 * Media test vocabulary, registered as FakeBridge macros:
 *
 *     Native::fakeBridge()->withMedia(['playing' => true, 'index' => 0, 'title' => 'Song']);
 *     Native::fakeBridge()->withMedia(library: [['id' => 'audio:1', 'uri' => 'content://...', 'title' => 'Song']]);
 *
 *     Native::visit('/library')->tap('Song');
 *     Native::fakeBridge()->assertMediaQueued(fn (array $p) => $p['startIndex'] === 0);
 */
class MediaMacros
{
    public const COMMANDS = ['SetQueue', 'Play', 'Pause', 'Toggle', 'Next', 'Previous', 'SkipTo', 'Seek', 'SetShuffle', 'SetRepeat', 'SetSpeed', 'Stop', 'OpenVideo'];

    public static function register(): void
    {
        if (FakeBridge::hasMacro('withMedia')) {
            return;
        }

        /** Answer every Media.* call: a player state, a device library, and permission state. */
        FakeBridge::macro('withMedia', function (array $state = [], array $library = [], bool $permission = true, bool $limited = false): FakeBridge {
            /** @var FakeBridge $this */
            foreach (MediaMacros::COMMANDS as $function) {
                $this->respondTo('Media.'.$function, ['ok' => true]);
            }

            return $this
                ->respondTo('Media.State', array_merge(Media::IDLE_STATE, $state))
                ->respondTo('Media.Library', ['ok' => true, 'items' => array_values($library), 'total' => count($library), 'offset' => 0])
                ->respondTo('Media.HasPermission', ['granted' => $permission, 'limited' => $permission && $limited])
                ->respondTo('Media.RequestPermission', ['ok' => true])
                ->respondTo('Media.Probe', [
                    'ok' => true, 'title' => null, 'artist' => null, 'album' => null,
                    'durationMs' => 0, 'width' => 0, 'height' => 0, 'hasArtwork' => false,
                ]);
        });

        /** Assert Media::setQueue() / play($item) ran, optionally matching its params (items, startIndex, startPosition, play). */
        FakeBridge::macro('assertMediaQueued', function (?callable $filter = null): FakeBridge {
            /** @var FakeBridge $this */
            return $this->assertCalled('Media.SetQueue', $filter);
        });

        /** Assert Media::openVideo() ran, optionally matching its params (items, startIndex, startPosition). */
        FakeBridge::macro('assertVideoOpened', function (?callable $filter = null): FakeBridge {
            /** @var FakeBridge $this */
            return $this->assertCalled('Media.OpenVideo', $filter);
        });

        /** Assert Media::requestPermission() ran, optionally for one type. */
        FakeBridge::macro('assertMediaPermissionRequested', function (?string $type = null): FakeBridge {
            /** @var FakeBridge $this */
            return $this->assertCalled('Media.RequestPermission', $type === null ? null : fn (array $p): bool => ($p['type'] ?? null) === $type);
        });
    }
}
