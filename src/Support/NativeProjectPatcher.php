<?php

namespace Protendai\Media\Support;

/**
 * Text patches the post_compile hook applies to the generated native
 * projects. Pure string in, string out, so they are unit-tested.
 *
 * Every patch is idempotent, and the library patches also undo themselves
 * when library access is switched off again (NativePHP's own merges are
 * additive, so a permission it once wrote would otherwise stay forever).
 * Only lines this plugin wrote (marked) or values it owns are removed.
 */
final class NativeProjectPatcher
{
    public const VIDEO_ACTIVITY = 'com.protendai.media.VideoActivity';

    public const MARKER = '<!-- nativephp-media:library -->';

    public const AUDIO_PLIST_KEY = 'NSAppleMusicUsageDescription';

    public const VIDEO_PLIST_KEY = 'NSPhotoLibraryUsageDescription';

    /** Usage strings earlier plugin versions declared; treated as the plugin's own. */
    public const LEGACY_DESCRIPTIONS = [
        self::AUDIO_PLIST_KEY => ['Lets you play the music saved on this iPhone.'],
        self::VIDEO_PLIST_KEY => [],
    ];

    private const PIP_ATTRIBUTES = [
        'android:supportsPictureInPicture' => 'true',
        'android:resizeableActivity' => 'true',
    ];

    /**
     * Add supportsPictureInPicture / resizeableActivity to the plugin's
     * VideoActivity (NativePHP's manifest compiler writes a fixed set of
     * activity attributes). Returns null when the activity isn't there.
     */
    public static function enablePictureInPicture(string $manifest): ?string
    {
        $pattern = '/<activity\b[^>]*?android:name="'.preg_quote(self::VIDEO_ACTIVITY, '/').'"[^>]*?(\/?>)/s';

        $patched = preg_replace_callback($pattern, function (array $match): string {
            $close = $match[1];
            $body = substr($match[0], 0, -strlen($close));

            foreach (self::PIP_ATTRIBUTES as $name => $value) {
                if (! str_contains($body, $name.'=')) {
                    $body = rtrim($body)."\n            {$name}=\"{$value}\"";
                }
            }

            return $body.$close;
        }, $manifest, 1, $count);

        return ($patched === null || $count === 0) ? null : $patched;
    }

    /**
     * The Android permissions library access needs.
     *
     * @return array<string, string> permission name => <uses-permission> element
     */
    public static function androidPermissions(LibraryAccess $access): array
    {
        $permissions = [];

        if ($access->audio) {
            $permissions['android.permission.READ_MEDIA_AUDIO'] = '<uses-permission android:name="android.permission.READ_MEDIA_AUDIO" />';
        }

        if ($access->video) {
            $permissions['android.permission.READ_MEDIA_VIDEO'] = '<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />';
            // Android 14+ "Select videos" (partial access).
            $permissions['android.permission.READ_MEDIA_VISUAL_USER_SELECTED'] = '<uses-permission android:name="android.permission.READ_MEDIA_VISUAL_USER_SELECTED" />';
        }

        if ($access->any()) {
            // Android 12 and older.
            $permissions['android.permission.READ_EXTERNAL_STORAGE'] = '<uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" android:maxSdkVersion="32" />';
        }

        return $permissions;
    }

    /**
     * Bring the manifest's library permissions in line with $access.
     *
     * @param  list<string>  $keep  Permission names other plugins declare: never removed.
     */
    public static function applyAndroidLibrary(string $manifest, LibraryAccess $access, array $keep = []): string
    {
        $wanted = self::androidPermissions($access);

        // Take out what this plugin wrote last time (a permission another plugin
        // also declares stays, just without the marker).
        $manifest = (string) preg_replace_callback(
            '/^([ \t]*)(<uses-permission\b[^>]*?android:name="([^"]+)"[^>]*\/>)[ \t]*'.preg_quote(self::MARKER, '/').'[ \t]*(\r?\n)?/m',
            fn (array $m): string => in_array($m[3], $keep, true) && ! isset($wanted[$m[3]]) ? $m[1].$m[2].($m[4] ?? '') : '',
            $manifest,
        );

        $lines = '';
        foreach ($wanted as $name => $element) {
            // Declared already (by the app, another plugin, or an older build): leave it.
            if (str_contains($manifest, 'android:name="'.$name.'"')) {
                continue;
            }
            $lines .= "    {$element} ".self::MARKER."\n";
        }

        if ($lines === '') {
            return $manifest;
        }

        return (string) preg_replace('/^([ \t]*<application\b)/m', $lines.'$1', $manifest, 1);
    }

    /**
     * Bring an Info.plist's library usage descriptions in line with $access.
     *
     * A key that exists with someone else's text (the app's
     * config('nativephp.permissions'), another plugin) is never touched.
     *
     * @param  array{audio?: ?string, video?: ?string}  $descriptions
     */
    public static function applyIosLibrary(string $plist, LibraryAccess $access, array $descriptions): string
    {
        $keys = [
            self::AUDIO_PLIST_KEY => [$access->audio, (string) ($descriptions['audio'] ?? '')],
            self::VIDEO_PLIST_KEY => [$access->video, (string) ($descriptions['video'] ?? '')],
        ];

        foreach ($keys as $key => [$enabled, $text]) {
            $existing = self::plistString($plist, $key);
            $ours = array_filter([$text, ...self::LEGACY_DESCRIPTIONS[$key], ...self::defaultDescriptions($key)]);
            $owned = $existing !== null && in_array($existing, $ours, true);

            if ($enabled && $text !== '') {
                if ($existing === null) {
                    $plist = self::insertPlistString($plist, $key, $text);
                } elseif ($owned && $existing !== $text) {
                    $plist = self::replacePlistString($plist, $key, $text);
                }
            } elseif (! $enabled && $owned) {
                $plist = self::removePlistKey($plist, $key);
            }
        }

        return $plist;
    }

    /**
     * @return list<string>
     */
    private static function defaultDescriptions(string $key): array
    {
        return match ($key) {
            self::AUDIO_PLIST_KEY => ['Lets you play the music saved on this device.'],
            self::VIDEO_PLIST_KEY => ['Lets you play the videos in your photo library.'],
            default => [],
        };
    }

    public static function plistString(string $plist, string $key): ?string
    {
        if (! preg_match('/<key>'.preg_quote($key, '/').'<\/key>\s*<string>(.*?)<\/string>/s', $plist, $m)) {
            return null;
        }

        return html_entity_decode($m[1], ENT_XML1 | ENT_QUOTES, 'UTF-8');
    }

    private static function insertPlistString(string $plist, string $key, string $value): string
    {
        $entry = "\t<key>{$key}</key>\n\t<string>".self::escape($value)."</string>\n";

        return (string) preg_replace('/(<\/dict>\s*<\/plist>\s*)$/s', $entry.'$1', $plist, 1);
    }

    private static function replacePlistString(string $plist, string $key, string $value): string
    {
        return (string) preg_replace_callback(
            '/(<key>'.preg_quote($key, '/').'<\/key>\s*<string>)(.*?)(<\/string>)/s',
            fn (array $m): string => $m[1].self::escape($value).$m[3],
            $plist,
            1,
        );
    }

    private static function removePlistKey(string $plist, string $key): string
    {
        return (string) preg_replace(
            '/[ \t]*<key>'.preg_quote($key, '/').'<\/key>\s*<string>.*?<\/string>[ \t]*\r?\n?/s',
            '',
            $plist,
            1,
        );
    }

    private static function escape(string $value): string
    {
        return htmlspecialchars($value, ENT_XML1 | ENT_QUOTES, 'UTF-8');
    }
}
