<?php

namespace Protendai\Media\Commands;

use Native\Mobile\Plugins\Commands\NativePluginHookCommand;

/**
 * post_compile hook: NativePHP's manifest compiler only writes a fixed set of
 * activity attributes, so switch on picture-in-picture for VideoActivity in
 * the generated AndroidManifest.xml. Safe to run repeatedly.
 */
class EnablePictureInPicture extends NativePluginHookCommand
{
    protected $signature = 'nativephp:media:enable-pip';

    protected $description = 'Enable picture-in-picture for the protendai/nativephp-media video activity';

    private const ATTRIBUTES = [
        'android:supportsPictureInPicture' => 'true',
        'android:resizeableActivity' => 'true',
    ];

    public function handle(): int
    {
        if (! $this->isAndroid()) {
            return self::SUCCESS;
        }

        $manifest = rtrim($this->buildPath(), '/').'/app/src/main/AndroidManifest.xml';

        if (! is_file($manifest)) {
            $this->warn("AndroidManifest.xml not found at {$manifest}; picture-in-picture not enabled.");

            return self::SUCCESS;
        }

        $xml = (string) file_get_contents($manifest);

        $patched = preg_replace_callback(
            '/<activity\b[^>]*android:name="[^"]*VideoActivity"[^>]*?(\/?>)/s',
            function (array $match): string {
                $tag = $match[0];
                $close = $match[1];
                $body = substr($tag, 0, -strlen($close));

                foreach (self::ATTRIBUTES as $name => $value) {
                    if (! str_contains($body, $name.'=')) {
                        $body = rtrim($body)."\n            {$name}=\"{$value}\"";
                    }
                }

                return $body.$close;
            },
            $xml,
            -1,
            $count,
        );

        if ($patched === null || $count === 0) {
            $this->warn('VideoActivity not found in AndroidManifest.xml; picture-in-picture not enabled.');

            return self::SUCCESS;
        }

        if ($patched !== $xml) {
            file_put_contents($manifest, $patched);
            $this->info('Picture-in-picture enabled for VideoActivity.');
        }

        return self::SUCCESS;
    }
}
