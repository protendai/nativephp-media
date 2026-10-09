<?php

namespace Protendai\Media;

use Illuminate\Support\ServiceProvider;
use Native\Mobile\Testing\FakeBridge;
use Protendai\Media\Commands\EnablePictureInPicture;

class MediaServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton('protendai.media', fn (): Media => new Media);
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->commands([EnablePictureInPicture::class]);
        }

        $this->registerFakeBridgeMacros();
    }

    /**
     * Test helper for screens that use the plugin:
     *
     *   Native::fakeBridge()->withMedia(['playing' => true, 'index' => 0, 'title' => 'Song']);
     *   Native::fakeBridge()->withMedia(library: [['id' => '1', 'uri' => 'content://...', 'title' => 'Song']]);
     */
    private function registerFakeBridgeMacros(): void
    {
        if (! class_exists(FakeBridge::class) || FakeBridge::hasMacro('withMedia')) {
            return;
        }

        FakeBridge::macro('withMedia', function (array $state = [], array $library = [], bool $permission = true): FakeBridge {
            /** @var FakeBridge $this */
            $ok = ['ok' => true];

            foreach (['SetQueue', 'Play', 'Pause', 'Toggle', 'Next', 'Previous', 'SkipTo', 'Seek', 'SetShuffle', 'SetRepeat', 'SetSpeed', 'Stop', 'OpenVideo', 'RequestPermission'] as $function) {
                $this->respondTo('Media.'.$function, $ok);
            }

            return $this
                ->respondTo('Media.State', array_merge(Media::IDLE_STATE, $state))
                ->respondTo('Media.Library', ['ok' => true, 'items' => array_values($library)])
                ->respondTo('Media.HasPermission', ['granted' => $permission])
                ->respondTo('Media.Probe', [
                    'ok' => true, 'title' => null, 'artist' => null, 'album' => null,
                    'durationMs' => 0, 'width' => 0, 'height' => 0, 'hasArtwork' => false,
                ]);
        });
    }
}
