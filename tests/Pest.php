<?php

use Native\Mobile\Testing\FakeBridge;
use Protendai\Media\Testing\MediaMacros;

/*
 * Plain Pest tests, no Laravel app: the facade's bridge calls go to a
 * FakeBridge through this stand-in for the device's nativephp_call().
 */

if (! function_exists('nativephp_call')) {
    function nativephp_call(string $method, string $params = '{}'): ?string
    {
        return bridge()->call($method, $params);
    }
}

function bridge(?FakeBridge $replace = null): FakeBridge
{
    static $bridge = null;

    if ($replace !== null || $bridge === null) {
        $bridge = $replace ?? new FakeBridge;
    }

    return $bridge;
}

function pluginPath(string $path = ''): string
{
    return dirname(__DIR__).($path === '' ? '' : '/'.ltrim($path, '/'));
}

function manifest(): array
{
    return json_decode(file_get_contents(pluginPath('nativephp.json')), true);
}

/** All Kotlin or Swift sources of the plugin, concatenated. */
function nativeSources(string $platform): string
{
    $dir = $platform === 'android' ? pluginPath('resources/android/src') : pluginPath('resources/ios/Sources');
    $ext = $platform === 'android' ? 'kt' : 'swift';

    return implode("\n", array_map('file_get_contents', glob("{$dir}/*.{$ext}")));
}

function androidManifest(string $permissions = ''): string
{
    return <<<XML
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
{$permissions}
    <application
        android:label="App">
        <activity
            android:name="com.example.OtherVideoActivity"
            android:exported="false" />
        <activity
            android:name="com.protendai.media.VideoActivity"
            android:theme="@style/Theme.AppCompat.NoActionBar"
            android:exported="false" />
    </application>
</manifest>
XML;
}

function infoPlist(string $entries = ''): string
{
    return <<<PLIST
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
	<key>CFBundleName</key>
	<string>App</string>
{$entries}</dict>
</plist>
PLIST;
}

MediaMacros::register();

uses()->beforeEach(fn () => bridge(new FakeBridge))->in(__DIR__);
