<?php

namespace Protendai\Media\Commands;

use Native\Mobile\Plugins\Commands\NativePluginHookCommand;
use Protendai\Media\Support\LibraryAccess;
use Protendai\Media\Support\NativeProjectPatcher;

/**
 * post_compile hook. Runs after NativePHP has merged the plugin manifest:
 *
 * - Android: switches on picture-in-picture for VideoActivity (the manifest
 *   compiler only writes a fixed set of activity attributes).
 * - Both: declares the device media library permissions only when the app
 *   opted in with config('nativephp-media.library'), and removes the ones
 *   this hook added before when it opts out again.
 *
 * Safe to run repeatedly.
 */
class ConfigureNativeProject extends NativePluginHookCommand
{
    protected $signature = 'nativephp:media:configure';

    protected $description = 'Configure the native project for protendai/nativephp-media (picture-in-picture, opt-in media library permissions)';

    public function handle(): int
    {
        $access = LibraryAccess::from(config('nativephp-media.library', false));

        if ($this->isAndroid()) {
            $this->configureAndroid($access);
        } elseif ($this->isIos()) {
            $this->configureIos($access);
        }

        return self::SUCCESS;
    }

    private function configureAndroid(LibraryAccess $access): void
    {
        $manifest = rtrim($this->buildPath(), '/').'/app/src/main/AndroidManifest.xml';

        if (! is_file($manifest)) {
            $this->warn("AndroidManifest.xml not found at {$manifest}; media plugin not configured.");

            return;
        }

        $original = (string) file_get_contents($manifest);
        $xml = $original;

        $pip = NativeProjectPatcher::enablePictureInPicture($xml);
        if ($pip === null) {
            $this->warn('VideoActivity not found in AndroidManifest.xml; picture-in-picture not enabled.');
        } else {
            $xml = $pip;
        }

        $xml = NativeProjectPatcher::applyAndroidLibrary($xml, $access, $this->otherPluginsAndroidPermissions());

        if ($xml !== $original) {
            file_put_contents($manifest, $xml);
        }

        $this->info('Media plugin: picture-in-picture on; device library '.$this->describe($access).'.');
    }

    private function configureIos(LibraryAccess $access): void
    {
        $root = rtrim($this->buildPath(), '/');
        $descriptions = (array) config('nativephp-media.usage_descriptions', []);

        foreach ([$root.'/NativePHP/Info.plist', $root.'/NativePHP-simulator-Info.plist'] as $path) {
            if (! is_file($path)) {
                continue;
            }

            $original = (string) file_get_contents($path);
            $patched = NativeProjectPatcher::applyIosLibrary($original, $access, $descriptions);

            if ($patched !== $original) {
                file_put_contents($path, $patched);
            }
        }

        $this->info('Media plugin: device library '.$this->describe($access).'.');
    }

    /**
     * Permission names every other registered plugin declares, so turning the
     * library off never removes a permission someone else relies on.
     *
     * @return list<string>
     */
    private function otherPluginsAndroidPermissions(): array
    {
        $names = [];

        foreach ($this->allPlugins() as $plugin) {
            if (($plugin['name'] ?? null) === 'protendai/nativephp-media') {
                continue;
            }

            foreach ($plugin['manifest']['android']['permissions'] ?? [] as $permission) {
                if (! is_string($permission)) {
                    continue;
                }
                $names[] = preg_match('/android:name="([^"]+)"/', $permission, $m) ? $m[1] : trim($permission);
            }
        }

        return array_values(array_unique($names));
    }

    private function describe(LibraryAccess $access): string
    {
        return match (true) {
            $access->audio && $access->video => 'access on (audio, video)',
            $access->audio => 'access on (audio)',
            $access->video => 'access on (video)',
            default => 'access off',
        };
    }
}
