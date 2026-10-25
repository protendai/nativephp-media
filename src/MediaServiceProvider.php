<?php

namespace Protendai\Media;

use Illuminate\Support\ServiceProvider;
use Native\Mobile\Testing\FakeBridge;
use Protendai\Media\Commands\ConfigureNativeProject;
use Protendai\Media\Testing\MediaMacros;

class MediaServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->mergeConfigFrom(__DIR__.'/../config/nativephp-media.php', 'nativephp-media');

        $this->app->singleton('protendai.media', fn (): Media => new Media);
        $this->app->alias('protendai.media', Media::class);
    }

    public function boot(): void
    {
        if ($this->app->runningInConsole()) {
            $this->commands([ConfigureNativeProject::class]);

            $this->publishes([
                __DIR__.'/../config/nativephp-media.php' => config_path('nativephp-media.php'),
            ], 'nativephp-media-config');
        }

        // Test vocabulary for apps (withMedia(), assertMediaQueued(), ...): only on a
        // core whose FakeBridge is macroable.
        if (class_exists(FakeBridge::class) && method_exists(FakeBridge::class, 'macro')) {
            MediaMacros::register();
        }
    }
}
