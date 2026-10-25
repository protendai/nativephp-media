<?php

use Protendai\Media\Support\LibraryAccess;
use Protendai\Media\Support\NativeProjectPatcher as Patcher;

const DESCRIPTIONS = ['audio' => 'Lets you play the music saved on this device.', 'video' => 'Lets you play the videos in your photo library.'];

describe('library access config', function () {
    it('parses the opt-in value', function (mixed $value, bool $audio, bool $video) {
        $access = LibraryAccess::from($value);

        expect($access->audio)->toBe($audio)->and($access->video)->toBe($video);
    })->with([
        'off by default' => [false, false, false],
        'null' => [null, false, false],
        'empty string' => ['', false, false],
        'true' => [true, true, true],
        '"true" from .env' => ['true', true, true],
        '"1"' => ['1', true, true],
        'all' => ['all', true, true],
        'audio' => ['audio', true, false],
        'video' => ['Video', false, true],
        'list string' => ['audio, video', true, true],
        'array' => [['video'], false, true],
        '"false"' => ['false', false, false],
    ]);
});

describe('picture-in-picture', function () {
    it('patches only the plugin VideoActivity, once', function () {
        $patched = Patcher::enablePictureInPicture(androidManifest());

        expect($patched)->toContain('android:name="com.protendai.media.VideoActivity"')
            ->and(substr_count($patched, 'android:supportsPictureInPicture="true"'))->toBe(1)
            ->and(substr_count($patched, 'android:resizeableActivity="true"'))->toBe(1)
            ->and(Patcher::enablePictureInPicture($patched))->toBe($patched);

        // The other app's *VideoActivity is untouched.
        preg_match('/<activity\s+android:name="com\.example\.OtherVideoActivity"[^>]*>/s', $patched, $other);
        expect($other[0])->not->toContain('supportsPictureInPicture');
    });

    it('reports a manifest without the activity', function () {
        expect(Patcher::enablePictureInPicture('<manifest><application /></manifest>'))->toBeNull();
    });
});

describe('android library permissions', function () {
    it('declares nothing when library access is off', function () {
        $xml = Patcher::applyAndroidLibrary(androidManifest(), new LibraryAccess);

        expect($xml)->not->toContain('READ_MEDIA')->not->toContain('READ_EXTERNAL_STORAGE')
            ->and($xml)->toBe(androidManifest());
    });

    it('declares audio only', function () {
        $xml = Patcher::applyAndroidLibrary(androidManifest(), new LibraryAccess(audio: true));

        expect($xml)->toContain('android.permission.READ_MEDIA_AUDIO')
            ->toContain('android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32"')
            ->not->toContain('READ_MEDIA_VIDEO')
            ->not->toContain('READ_MEDIA_VISUAL_USER_SELECTED');
    });

    it('declares video with Android 14 partial access, before <application>, idempotently', function () {
        $xml = Patcher::applyAndroidLibrary(androidManifest(), new LibraryAccess(video: true));

        expect($xml)->toContain('android.permission.READ_MEDIA_VIDEO')
            ->toContain('android.permission.READ_MEDIA_VISUAL_USER_SELECTED')
            ->not->toContain('READ_MEDIA_AUDIO')
            ->and(strpos($xml, 'READ_MEDIA_VIDEO'))->toBeLessThan(strpos($xml, '<application'))
            ->and(Patcher::applyAndroidLibrary($xml, new LibraryAccess(video: true)))->toBe($xml);
    });

    it('removes what it added when switched off again', function () {
        $on = Patcher::applyAndroidLibrary(androidManifest(), new LibraryAccess(true, true));
        $off = Patcher::applyAndroidLibrary($on, new LibraryAccess);

        expect($on)->toContain(Patcher::MARKER)
            ->and($off)->toBe(androidManifest());
    });

    it('never removes permissions the app or other plugins declared', function () {
        $app = '    <uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />';
        $xml = Patcher::applyAndroidLibrary(androidManifest($app), new LibraryAccess(true, true));

        expect(substr_count($xml, 'android.permission.READ_MEDIA_AUDIO'))->toBe(1);

        $off = Patcher::applyAndroidLibrary($xml, new LibraryAccess, keep: ['android.permission.READ_MEDIA_VIDEO']);

        expect($off)->toContain($app)
            ->toContain('<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />')
            ->not->toContain(Patcher::MARKER)
            ->not->toContain('READ_MEDIA_VISUAL_USER_SELECTED');
    });

    it('lists the permissions per access level', function () {
        expect(array_keys(Patcher::androidPermissions(new LibraryAccess(true, true))))->toBe([
            'android.permission.READ_MEDIA_AUDIO',
            'android.permission.READ_MEDIA_VIDEO',
            'android.permission.READ_MEDIA_VISUAL_USER_SELECTED',
            'android.permission.READ_EXTERNAL_STORAGE',
        ])->and(Patcher::androidPermissions(new LibraryAccess))->toBe([]);
    });
});

describe('ios library usage descriptions', function () {
    it('adds nothing when library access is off', function () {
        expect(Patcher::applyIosLibrary(infoPlist(), new LibraryAccess, DESCRIPTIONS))->toBe(infoPlist());
    });

    it('adds the configured strings for the enabled types, escaped', function () {
        $plist = Patcher::applyIosLibrary(infoPlist(), new LibraryAccess(audio: true), ['audio' => 'Play "your" music & more']);

        expect(Patcher::plistString($plist, 'NSAppleMusicUsageDescription'))->toBe('Play "your" music & more')
            ->and($plist)->toContain('&amp;')
            ->not->toContain('NSPhotoLibraryUsageDescription')
            ->toEndWith("</dict>\n</plist>")
            ->and(Patcher::applyIosLibrary($plist, new LibraryAccess(audio: true), ['audio' => 'Play "your" music & more']))->toBe($plist);
    });

    it('keeps a description the app set itself, even when switched off', function () {
        $app = "\t<key>NSPhotoLibraryUsageDescription</key>\n\t<string>Pick a profile photo.</string>\n";

        expect(Patcher::applyIosLibrary(infoPlist($app), new LibraryAccess(true, true), DESCRIPTIONS))
            ->toContain('Pick a profile photo.')
            ->and(Patcher::applyIosLibrary(infoPlist($app), new LibraryAccess, DESCRIPTIONS))->toBe(infoPlist($app));
    });

    it('removes its own strings (including older plugin defaults) when switched off', function () {
        $legacy = "\t<key>NSAppleMusicUsageDescription</key>\n\t<string>Lets you play the music saved on this iPhone.</string>\n";
        $on = Patcher::applyIosLibrary(infoPlist(), new LibraryAccess(true, true), DESCRIPTIONS);

        expect(Patcher::applyIosLibrary($on, new LibraryAccess, DESCRIPTIONS))->toBe(infoPlist())
            ->and(Patcher::applyIosLibrary(infoPlist($legacy), new LibraryAccess, DESCRIPTIONS))->toBe(infoPlist());
    });

    it('updates its own string when the configured text changes', function () {
        $on = Patcher::applyIosLibrary(infoPlist(), new LibraryAccess(audio: true), DESCRIPTIONS);
        $changed = Patcher::applyIosLibrary($on, new LibraryAccess(audio: true), ['audio' => 'Your songs, offline.']);

        expect(Patcher::plistString($changed, 'NSAppleMusicUsageDescription'))->toBe('Your songs, offline.');
    });
});
