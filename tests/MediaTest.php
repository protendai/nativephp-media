<?php

use Protendai\Media\Media;

function lastCall(string $method): array
{
    $calls = bridge()->callsTo($method);
    expect($calls)->not->toBeEmpty();

    return end($calls)['params'];
}

describe('playback commands', function () {
    it('sends setQueue with list items, start index, resume marker and play flag', function () {
        bridge()->withMedia();

        $ok = (new Media)->setQueue(['a' => ['id' => 's1', 'uri' => '/m/1.mp3'], 'b' => ['id' => 's2', 'uri' => '/m/2.mp3']], 1);

        expect($ok)->toBeTrue()
            ->and(lastCall('Media.SetQueue'))->toBe([
                'items' => [['id' => 's1', 'uri' => '/m/1.mp3'], ['id' => 's2', 'uri' => '/m/2.mp3']],
                'startIndex' => 1,
                'startPosition' => -1,
                'play' => true,
            ]);
    });

    it('plays a single item as a one-item queue, or resumes without one', function () {
        bridge()->withMedia();
        $media = new Media;

        $media->play(['id' => 'x', 'uri' => 'https://example.com/a.mp3']);
        $media->play();

        expect(lastCall('Media.SetQueue')['items'])->toBe([['id' => 'x', 'uri' => 'https://example.com/a.mp3']])
            ->and(bridge()->callsTo('Media.Play'))->toHaveCount(1);
    });

    it('returns false when the native side refuses or is absent', function () {
        bridge()->respondTo('Media.Next', ['ok' => false]);

        expect((new Media)->next())->toBeFalse()
            ->and((new Media)->pause())->toBeFalse(); // no response scripted
    });

    it('clamps seek, speed and repeat before they cross the bridge', function () {
        bridge()->withMedia();
        $media = new Media;

        $media->seek(-500);
        $media->setSpeed(9.0);
        $media->setRepeat('sometimes');

        expect(lastCall('Media.Seek'))->toBe(['position' => 0])
            ->and(lastCall('Media.SetSpeed'))->toEqual(['speed' => 3])
            ->and(lastCall('Media.SetRepeat'))->toBe(['mode' => 'off']);

        $media->setSpeed(0.1);
        $media->setRepeat('all');

        expect(lastCall('Media.SetSpeed'))->toEqual(['speed' => 0.25])
            ->and(lastCall('Media.SetRepeat'))->toBe(['mode' => 'all']);
    });

    it('sends skipTo, shuffle, toggle, previous and stop', function () {
        bridge()->withMedia();
        $media = new Media;

        expect($media->skipTo(3))->toBeTrue()
            ->and($media->setShuffle(true))->toBeTrue()
            ->and($media->toggle())->toBeTrue()
            ->and($media->previous())->toBeTrue()
            ->and($media->stop())->toBeTrue()
            ->and(lastCall('Media.SkipTo'))->toBe(['index' => 3])
            ->and(lastCall('Media.SetShuffle'))->toBe(['enabled' => true]);
    });

    it('opens video with every item typed as video', function () {
        bridge()->withMedia();

        (new Media)->openVideo([['id' => 'v1', 'uri' => '/v.mp4'], ['id' => 'v2', 'uri' => '/w.mp4', 'type' => 'audio']], 1, 5000);

        expect(lastCall('Media.OpenVideo'))->toBe([
            'items' => [
                ['id' => 'v1', 'uri' => '/v.mp4', 'type' => 'video'],
                ['id' => 'v2', 'uri' => '/w.mp4', 'type' => 'audio'],
            ],
            'startIndex' => 1,
            'startPosition' => 5000,
        ]);
    });
});

describe('state', function () {
    it('is the idle state when nothing answers', function () {
        expect((new Media)->state())->toBe(Media::IDLE_STATE)
            ->and((new Media)->isActive())->toBeFalse();
    });

    it('casts native values and drops unknown keys', function () {
        bridge()->respondTo('Media.State', [
            'playing' => 1, 'buffering' => 0, 'index' => '2', 'count' => '5', 'id' => 'song',
            'positionMs' => 1500.7, 'durationMs' => '90000', 'shuffle' => 1, 'speed' => '1.5',
            'repeat' => 'all', 'surprise' => 'x',
        ]);

        $state = (new Media)->state();

        expect($state)->toHaveKeys(array_keys(Media::IDLE_STATE))
            ->not->toHaveKey('surprise')
            ->and($state['playing'])->toBeTrue()
            ->and($state['index'])->toBe(2)
            ->and($state['count'])->toBe(5)
            ->and($state['positionMs'])->toBe(1500)
            ->and($state['durationMs'])->toBe(90000)
            ->and($state['shuffle'])->toBeTrue()
            ->and($state['speed'])->toBe(1.5)
            ->and($state['repeat'])->toBe('all')
            ->and((new Media)->isActive())->toBeTrue();
    });
});

describe('probe', function () {
    it('normalises the probe result and passes the artwork destination', function () {
        bridge()->respondTo('Media.Probe', ['ok' => true, 'title' => 'One', 'durationMs' => '1000', 'width' => 640, 'hasArtwork' => 1]);

        $tags = (new Media)->probe('/m/1.mp3', '/tmp/art.jpg');

        expect($tags)->toBe([
            'ok' => true, 'title' => 'One', 'artist' => null, 'album' => null,
            'durationMs' => 1000, 'width' => 640, 'height' => 0, 'hasArtwork' => true,
        ])->and(lastCall('Media.Probe'))->toBe(['uri' => '/m/1.mp3', 'artworkDest' => '/tmp/art.jpg']);
    });

    it('reports ok false for a missing file', function () {
        bridge()->respondTo('Media.Probe', ['ok' => false, 'error' => 'not_found']);

        expect((new Media)->probe('/nope.mp3')['ok'])->toBeFalse();
    });
});

describe('library', function () {
    it('pages through the library with total and offset', function () {
        bridge()->withMedia(library: [['id' => 'audio:1', 'uri' => 'content://media/external/audio/media/1'], 'junk']);

        $page = (new Media)->libraryPage('audio', 60, 0);

        expect($page['items'])->toBe([['id' => 'audio:1', 'uri' => 'content://media/external/audio/media/1']])
            ->and($page['total'])->toBe(2)
            ->and($page['offset'])->toBe(0)
            ->and(lastCall('Media.Library'))->toBe(['type' => 'audio', 'limit' => 60, 'offset' => 0]);
    });

    it('normalises type, limit and offset', function () {
        (new Media)->libraryPage('pictures', 0, -4);

        expect(lastCall('Media.Library'))->toBe(['type' => 'audio', 'limit' => 1, 'offset' => 0]);
    });

    it('assumes no more pages when an older native build sends no total', function () {
        bridge()->respondTo('Media.Library', ['ok' => true, 'items' => [['id' => 'a'], ['id' => 'b']]]);

        expect((new Media)->libraryPage('video', 10, 20)['total'])->toBe(22)
            ->and((new Media)->library('video'))->toHaveCount(2);
    });
});

describe('permissions', function () {
    it('reports granted and limited access', function () {
        bridge()->withMedia(permission: true, limited: true);

        expect((new Media)->hasPermission('video'))->toBeTrue()
            ->and((new Media)->permissionStatus('video'))->toBe(['granted' => true, 'limited' => true])
            ->and(lastCall('Media.HasPermission'))->toBe(['type' => 'video']);
    });

    it('is not limited when not granted', function () {
        bridge()->respondTo('Media.HasPermission', ['granted' => false, 'limited' => true]);

        expect((new Media)->permissionStatus('audio'))->toBe(['granted' => false, 'limited' => false])
            ->and((new Media)->hasPermission())->toBeFalse();
    });

    it('requests permission for a normalised type', function () {
        bridge()->withMedia();

        (new Media)->requestPermission('video');
        (new Media)->requestPermission('everything');

        bridge()->assertMediaPermissionRequested('video')->assertMediaPermissionRequested('audio');
    });
});

describe('test macros', function () {
    it('answers every bridge function and exposes assertions', function () {
        bridge()->withMedia(state: ['playing' => true, 'index' => 0, 'title' => 'One']);

        $media = new Media;
        $media->setQueue([['id' => 'a', 'uri' => '/a.mp3']]);
        $media->openVideo([['id' => 'v', 'uri' => '/v.mp4']]);

        expect($media->state()['title'])->toBe('One');
        bridge()->assertMediaQueued(fn (array $p): bool => $p['items'][0]['id'] === 'a')
            ->assertVideoOpened(fn (array $p): bool => $p['items'][0]['type'] === 'video');
    });
});
