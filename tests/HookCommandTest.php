<?php

use Illuminate\Support\Facades\Artisan;
use Protendai\Media\Media;
use Protendai\Media\Support\NativeProjectPatcher;
use Protendai\Media\Tests\TestCase;

uses(TestCase::class);

function buildDir(): string
{
    $dir = sys_get_temp_dir().'/nativephp-media-'.bin2hex(random_bytes(4));
    mkdir($dir.'/app/src/main', 0777, true);
    mkdir($dir.'/NativePHP', 0777, true);
    file_put_contents($dir.'/app/src/main/AndroidManifest.xml', androidManifest());
    file_put_contents($dir.'/NativePHP/Info.plist', infoPlist());

    return $dir;
}

it('registers the facade binding, config defaults and the hook command', function () {
    expect(app('protendai.media'))->toBeInstanceOf(Media::class)
        ->and(config('nativephp-media.library'))->toBeFalse()
        ->and(config('nativephp-media.usage_descriptions.audio'))->toBe('Lets you play the music saved on this device.')
        ->and(array_keys(Artisan::all()))->toContain(manifest()['hooks']['post_compile']);
});

it('enables PiP and leaves library permissions out by default (Android)', function () {
    $dir = buildDir();

    $this->artisan('nativephp:media:configure', ['--platform' => 'android', '--build-path' => $dir])->assertSuccessful();

    $xml = file_get_contents($dir.'/app/src/main/AndroidManifest.xml');
    expect($xml)->toContain('android:supportsPictureInPicture="true"')
        ->not->toContain('READ_MEDIA');
});

it('adds library permissions when the app opts in (Android)', function () {
    config(['nativephp-media.library' => 'audio']);
    $dir = buildDir();

    $this->artisan('nativephp:media:configure', [
        '--platform' => 'android',
        '--build-path' => $dir,
        '--plugins' => json_encode([['name' => 'vendor/other', 'manifest' => ['android' => ['permissions' => ['android.permission.CAMERA']]]]]),
    ])->assertSuccessful();

    expect(file_get_contents($dir.'/app/src/main/AndroidManifest.xml'))
        ->toContain('android.permission.READ_MEDIA_AUDIO" /> '.NativeProjectPatcher::MARKER)
        ->not->toContain('READ_MEDIA_VIDEO');
});

it('adds the usage descriptions only when the app opts in (iOS)', function () {
    $dir = buildDir();

    $this->artisan('nativephp:media:configure', ['--platform' => 'ios', '--build-path' => $dir])->assertSuccessful();
    expect(file_get_contents($dir.'/NativePHP/Info.plist'))->toBe(infoPlist());

    config(['nativephp-media.library' => true, 'nativephp-media.usage_descriptions.video' => 'Watch your clips.']);
    $this->artisan('nativephp:media:configure', ['--platform' => 'ios', '--build-path' => $dir])->assertSuccessful();

    $plist = file_get_contents($dir.'/NativePHP/Info.plist');
    expect(NativeProjectPatcher::plistString($plist, 'NSPhotoLibraryUsageDescription'))->toBe('Watch your clips.')
        ->and(NativeProjectPatcher::plistString($plist, 'NSAppleMusicUsageDescription'))->toBe('Lets you play the music saved on this device.');
});
