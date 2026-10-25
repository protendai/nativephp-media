<?php

namespace Protendai\Media\Tests;

use Orchestra\Testbench\TestCase as Orchestra;
use Protendai\Media\MediaServiceProvider;

/**
 * A Laravel app with only this plugin's provider, for the service provider
 * and the post_compile hook command.
 */
abstract class TestCase extends Orchestra
{
    protected function getPackageProviders($app): array
    {
        return [MediaServiceProvider::class];
    }
}
