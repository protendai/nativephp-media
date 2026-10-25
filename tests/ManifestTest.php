<?php

use Protendai\Media\Facades\Media as MediaFacade;
use Protendai\Media\Media;

const BRIDGE_FUNCTIONS = [
    'SetQueue', 'Play', 'Pause', 'Toggle', 'Next', 'Previous', 'SkipTo', 'Seek', 'SetShuffle',
    'SetRepeat', 'SetSpeed', 'Stop', 'State', 'OpenVideo', 'Probe', 'Library', 'RequestPermission', 'HasPermission',
];

describe('manifest', function () {
    it('carries the marketplace metadata', function () {
        $manifest = manifest();
        $composer = json_decode(file_get_contents(pluginPath('composer.json')), true);

        expect($manifest)->toHaveKeys(['name', 'version', 'description', 'keywords', 'category', 'license', 'author', 'homepage', 'repository', 'platforms'])
            ->and($manifest['name'])->toBe('protendai/nativephp-media')
            ->and($manifest['name'])->toBe($composer['name'])
            ->and($manifest['version'])->toBe('1.0.0')
            ->and($manifest['license'])->toBe('MIT')
            ->and($composer['license'])->toBe('MIT')
            ->and($manifest['platforms'])->toBe(['android', 'ios'])
            ->and($manifest['description'])->toContain('iOS')->toContain('Android')
            ->and($composer['keywords'])->toContain('nativephp-plugin')
            ->and($composer['require'])->toHaveKey('nativephp/mobile')
            ->and($manifest['android']['min_version'])->toBe(29)
            ->and($manifest['ios']['min_version'])->toBe('16.0');
    });

    it('declares every bridge function on both platforms, matching the native classes', function () {
        $kotlin = file_get_contents(pluginPath('resources/android/src/MediaFunctions.kt'));
        $swift = file_get_contents(pluginPath('resources/ios/Sources/MediaFunctions.swift'));

        expect(array_column(manifest()['bridge_functions'], 'name'))->toBe(array_map(fn ($f) => "Media.{$f}", BRIDGE_FUNCTIONS));

        foreach (manifest()['bridge_functions'] as $function) {
            $class = substr($function['name'], strlen('Media.'));

            expect($function['android'])->toBe("com.protendai.media.MediaFunctions.{$class}")
                ->and($function['ios'])->toBe("MediaFunctions.{$class}")
                ->and($function['description'] ?? '')->not->toBeEmpty()
                ->and($kotlin)->toContain("class {$class}(private val activity: FragmentActivity) : BridgeFunction")
                ->and($swift)->toContain("class {$class}: BridgeFunction");
        }

        expect($kotlin)->toContain('object MediaFunctions')
            ->and($swift)->toContain('enum MediaFunctions');
    });

    it('is called by the facade with the declared names only', function () {
        $source = file_get_contents(pluginPath('src/Media.php'));
        preg_match_all("/'(Media\\.[A-Za-z]+)'/", $source, $m);

        expect(array_diff(array_unique($m[1]), array_column(manifest()['bridge_functions'], 'name')))->toBe([]);
    });

    it('documents every public facade method on the facade', function () {
        $doc = (new ReflectionClass(MediaFacade::class))->getDocComment();
        $methods = array_map(fn (ReflectionMethod $m) => $m->getName(), (new ReflectionClass(Media::class))->getMethods(ReflectionMethod::IS_PUBLIC));

        foreach ($methods as $method) {
            expect($doc)->toContain(" {$method}(");
        }
    });

    it('declares the events the native code dispatches', function () {
        $events = ['StateChanged', 'TrackChanged', 'PlaybackError', 'QueueEnded', 'PermissionResult'];

        expect(manifest()['events'])->toBe(array_map(fn ($e) => "Protendai\\Media\\Events\\{$e}", $events));

        foreach ($events as $event) {
            expect(class_exists("Protendai\\Media\\Events\\{$event}"))->toBeTrue()
                ->and(nativeSources('android'))->toContain("\"{$event}\"")
                ->and(nativeSources('ios'))->toContain("\"{$event}\"");
        }

        expect(nativeSources('android'))->toContain('"Protendai\\\\Media\\\\Events\\\\"')
            ->and(nativeSources('ios'))->toContain('"Protendai\\\\Media\\\\Events\\\\"');
    });

    it('keeps every Kotlin file in the com.protendai.media package', function () {
        foreach (glob(pluginPath('resources/android/src/*.kt')) as $file) {
            expect(file_get_contents($file))->toStartWith('package com.protendai.media');
        }
    });

    it('declares the components the Kotlin code implements', function () {
        $android = manifest()['android'];

        expect($android['services'][0]['name'])->toBe('com.protendai.media.PlaybackService')
            ->and($android['activities'][0]['name'])->toBe('com.protendai.media.VideoActivity')
            ->and($android['receivers'][0]['name'])->toBe('androidx.media3.session.MediaButtonReceiver')
            ->and(nativeSources('android'))->toContain('class PlaybackService : MediaSessionService()')
            ->toContain('class VideoActivity : AppCompatActivity()')
            ->toContain('override fun onPlaybackResumption(');
    });

    it('does not force library permissions or usage strings on every app', function () {
        $manifest = manifest();

        expect(implode(' ', $manifest['android']['permissions']))->not->toContain('READ_MEDIA')
            ->not->toContain('READ_EXTERNAL_STORAGE')
            ->and($manifest['ios'])->not->toHaveKey('info_plist')
            ->and($manifest['hooks']['post_compile'])->toBe('nativephp:media:configure');
    });

    it('locks the exported media session down to playback commands for other apps', function () {
        $service = file_get_contents(pluginPath('resources/android/src/PlaybackService.kt'));

        expect($service)->toContain('override fun onConnect(')
            ->toContain('.remove(Player.COMMAND_SET_MEDIA_ITEM)')
            ->toContain('.remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)')
            ->toContain('if (controller.packageName != ownPackage)');
    });

    it('logs no titles, paths or URIs', function () {
        expect(nativeSources('ios'))->not->toContain('NSLog(')->not->toContain('print(');

        preg_match_all('/Log\.[dewiv]\(([^\n]*)/', nativeSources('android'), $logs);
        foreach ($logs[1] as $line) {
            expect($line)->not->toMatch('/\$(lastError|uri|title|path)|\$\{/');
        }
    });

    it('names no app in the plugin', function () {
        $files = [
            ...glob(pluginPath('src/**/*.php')), ...glob(pluginPath('src/*.php')),
            ...glob(pluginPath('resources/android/src/*.kt')), ...glob(pluginPath('resources/ios/Sources/*.swift')),
            pluginPath('nativephp.json'), pluginPath('README.md'), pluginPath('SPEC.md'), pluginPath('config/nativephp-media.php'),
        ];

        foreach ($files as $file) {
            expect(file_get_contents($file))->not->toMatch('/tumira|ftree/i');
        }
    });
});

describe('javascript library', function () {
    it('exports a function for every bridge function and the event names', function () {
        $js = file_get_contents(pluginPath('resources/js/index.js'));
        $types = file_get_contents(pluginPath('resources/js/index.d.ts'));

        foreach (BRIDGE_FUNCTIONS as $function) {
            expect($js)->toContain("export async function {$function}(")
                ->toContain("'Media.{$function}'")
                ->and($types)->toContain("export function {$function}(");
        }

        foreach (manifest()['events'] as $event) {
            expect($js)->toContain(str_replace('\\', '\\\\', $event));
        }
    });

    it('has a package.json', function () {
        $package = json_decode(file_get_contents(pluginPath('resources/js/package.json')), true);

        expect($package['name'])->toBe('@protendai/nativephp-media')
            ->and($package['private'])->toBeFalse()
            ->and($package['type'])->toBe('module');
    });
});
