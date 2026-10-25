<?php

return [

    /*
    |--------------------------------------------------------------------------
    | Device media library access
    |--------------------------------------------------------------------------
    |
    | Media::library(), libraryPage(), hasPermission() and requestPermission()
    | read the phone's own music and videos. That needs READ_MEDIA_AUDIO /
    | READ_MEDIA_VIDEO on Android (Google Play asks you to justify those) and
    | the Music / Photos usage descriptions on iOS, so it is OFF by default:
    | apps that only play their own files or URLs never declare them.
    |
    | true / "all"     audio and video
    | "audio"          the music library only
    | "video"          videos only
    | false            neither (default)
    |
    | Read at build time by the plugin's post_compile hook, so rebuild the app
    | after changing it.
    |
    */

    'library' => env('NATIVEPHP_MEDIA_LIBRARY', false),

    /*
    |--------------------------------------------------------------------------
    | iOS permission prompts
    |--------------------------------------------------------------------------
    |
    | The text iOS shows when the app asks for the Music library (audio) or
    | the Photos library (video). Only used when library access is on. A value
    | set in config('nativephp.permissions') for the same Info.plist key wins.
    |
    */

    'usage_descriptions' => [
        'audio' => env('NATIVEPHP_MEDIA_AUDIO_USAGE', 'Lets you play the music saved on this device.'),
        'video' => env('NATIVEPHP_MEDIA_VIDEO_USAGE', 'Lets you play the videos in your photo library.'),
    ],

];
