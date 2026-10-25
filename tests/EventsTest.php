<?php

use Protendai\Media\Events\PermissionResult;
use Protendai\Media\Events\PlaybackError;
use Protendai\Media\Events\QueueEnded;
use Protendai\Media\Events\StateChanged;
use Protendai\Media\Events\TrackChanged;
use Protendai\Media\Media;

/*
 * NativePHP builds events with `new $class(...$payload)`, so every key the
 * native side sends must be a constructor parameter.
 */

it('builds StateChanged from a full state payload', function () {
    $payload = array_merge(Media::IDLE_STATE, ['playing' => true, 'index' => 1, 'count' => 3, 'id' => 'a', 'title' => 'One', 'repeat' => 'one', 'speed' => 1.25]);

    $event = new StateChanged(...$payload);

    expect($event->playing)->toBeTrue()
        ->and($event->index)->toBe(1)
        ->and($event->repeat)->toBe('one')
        ->and($event->speed)->toBe(1.25);
});

it('builds StateChanged from an iOS payload with nulls left out', function () {
    $payload = array_filter(Media::IDLE_STATE, fn ($value) => $value !== null);

    expect((new StateChanged(...$payload))->id)->toBeNull();
});

it('builds TrackChanged, PlaybackError, QueueEnded and PermissionResult', function () {
    $track = new TrackChanged(...['id' => 'a', 'index' => 0, 'title' => 'One', 'artist' => 'Band', 'type' => 'audio']);
    $error = new PlaybackError(...['id' => null, 'message' => 'Source error']);
    $ended = new QueueEnded(...[]);
    $permission = new PermissionResult(...['granted' => true, 'type' => 'video', 'limited' => true]);
    $legacy = new PermissionResult(...['granted' => false, 'type' => 'audio']);

    expect($track->artist)->toBe('Band')
        ->and($error->message)->toBe('Source error')
        ->and($ended)->toBeInstanceOf(QueueEnded::class)
        ->and($permission->limited)->toBeTrue()
        ->and($legacy->limited)->toBeFalse();
});

it('has a constructor parameter for every key the native code sends', function (string $event, array $keys) {
    $params = array_map(
        fn (ReflectionParameter $p) => $p->getName(),
        (new ReflectionClass('Protendai\\Media\\Events\\'.$event))->getConstructor()?->getParameters() ?? [],
    );

    expect($params)->toEqualCanonicalizing($keys);
})->with([
    'StateChanged' => ['StateChanged', array_keys(Media::IDLE_STATE)],
    'TrackChanged' => ['TrackChanged', ['id', 'index', 'title', 'artist', 'type']],
    'PlaybackError' => ['PlaybackError', ['id', 'message']],
    'QueueEnded' => ['QueueEnded', []],
    'PermissionResult' => ['PermissionResult', ['granted', 'type', 'limited']],
]);

it('sends those payload keys from both native implementations', function () {
    $kotlin = nativeSources('android');
    $swift = nativeSources('ios');

    foreach (['"granted"', '"limited"', '"type"', '"message"', '"positionMs"', '"durationMs"', '"shuffle"', '"repeat"', '"speed"', '"error"', '"buffering"'] as $key) {
        expect($kotlin)->toContain($key)
            ->and($swift)->toContain($key);
    }
});
