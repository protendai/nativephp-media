<?php

namespace Protendai\Media\Support;

/**
 * Which parts of the device media library an app opted in to
 * (config('nativephp-media.library')).
 */
final class LibraryAccess
{
    public function __construct(
        public readonly bool $audio = false,
        public readonly bool $video = false,
    ) {}

    /**
     * Accepts true/false, "true"/"false"/"1"/"0"/"all"/"none", "audio",
     * "video", "audio,video", or a list of those words.
     */
    public static function from(mixed $value): self
    {
        if (is_bool($value)) {
            return new self($value, $value);
        }

        if (is_int($value)) {
            return new self($value > 0, $value > 0);
        }

        $words = is_array($value)
            ? array_map(fn ($word) => strtolower(trim((string) $word)), $value)
            : array_map('trim', explode(',', strtolower(trim((string) $value))));

        if (array_intersect($words, ['true', '1', 'yes', 'on', 'all', 'both']) !== []) {
            return new self(true, true);
        }

        return new self(in_array('audio', $words, true), in_array('video', $words, true));
    }

    public function any(): bool
    {
        return $this->audio || $this->video;
    }
}
