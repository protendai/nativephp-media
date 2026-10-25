/**
 * protendai/nativephp-media — JavaScript API for Inertia (Vue / React) and
 * other SPA front ends of NativePHP Mobile apps.
 *
 * Every function calls the native bridge through NativePHP's
 * `/_native/api/call` endpoint (the same one `BridgeCall` from
 * `@nativephp/mobile` uses) and resolves with the native result object.
 * Asynchronous results arrive as events: see `Events` and `onMediaEvent()`.
 *
 * @module @protendai/nativephp-media
 */

const CALL_URL = '/_native/api/call';

/**
 * Event class names dispatched by the plugin.
 */
export const Events = Object.freeze({
    StateChanged: 'Protendai\\Media\\Events\\StateChanged',
    TrackChanged: 'Protendai\\Media\\Events\\TrackChanged',
    PlaybackError: 'Protendai\\Media\\Events\\PlaybackError',
    QueueEnded: 'Protendai\\Media\\Events\\QueueEnded',
    PermissionResult: 'Protendai\\Media\\Events\\PermissionResult',
});

/**
 * The state snapshot when nothing is loaded.
 * @type {Readonly<import('./index').MediaState>}
 */
export const IDLE_STATE = Object.freeze({
    playing: false, buffering: false, index: -1, count: 0, id: null, title: null, artist: null,
    artwork: null, type: null, positionMs: 0, durationMs: 0, shuffle: false, repeat: 'off', speed: 1.0, error: null,
});

/**
 * Call a Media.* bridge function.
 *
 * @param {string} method  e.g. 'Media.SetQueue'
 * @param {object} [params]
 * @returns {Promise<object>} the native result
 */
async function bridgeCall(method, params = {}) {
    const response = await fetch(CALL_URL, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest',
            'X-CSRF-TOKEN': (typeof document !== 'undefined' && document.querySelector('meta[name="csrf-token"]')?.content) || '',
        },
        body: JSON.stringify({ method, params }),
    });

    const result = await response.json();

    if (result.status === 'error') {
        throw new Error(result.message || `${method} failed`);
    }

    return result.data ?? {};
}

const clamp = (value, min, max) => Math.min(max, Math.max(min, value));

/**
 * Replace the queue and (by default) start playing. → TrackChanged, StateChanged
 *
 * @param {import('./index').QueueItem[]} items
 * @param {{startIndex?: number, startPosition?: number, play?: boolean}} [options]
 *   startPosition in ms; -1 (default) resumes the start item's saved position.
 * @returns {Promise<{ok: boolean}>}
 */
export async function SetQueue(items, { startIndex = 0, startPosition = -1, play = true } = {}) {
    return bridgeCall('Media.SetQueue', { items: Array.from(items), startIndex, startPosition, play });
}

/**
 * Resume playback (a finished queue starts again). → StateChanged
 * @returns {Promise<{ok: boolean}>}
 */
export async function Play() {
    return bridgeCall('Media.Play');
}

/** Pause. → StateChanged @returns {Promise<{ok: boolean}>} */
export async function Pause() {
    return bridgeCall('Media.Pause');
}

/** Play or pause. → StateChanged @returns {Promise<{ok: boolean}>} */
export async function Toggle() {
    return bridgeCall('Media.Toggle');
}

/** Next item, from its start. → TrackChanged @returns {Promise<{ok: boolean}>} */
export async function Next() {
    return bridgeCall('Media.Next');
}

/** Restart the item when past 3 s, otherwise the previous item. → TrackChanged @returns {Promise<{ok: boolean}>} */
export async function Previous() {
    return bridgeCall('Media.Previous');
}

/**
 * Jump to a queue index and play it, resuming its saved position. → TrackChanged
 * @param {number} index
 * @returns {Promise<{ok: boolean}>}
 */
export async function SkipTo(index) {
    return bridgeCall('Media.SkipTo', { index });
}

/**
 * Seek within the current item.
 * @param {number} position  milliseconds
 * @returns {Promise<{ok: boolean}>}
 */
export async function Seek(position) {
    return bridgeCall('Media.Seek', { position: Math.max(0, Math.round(position)) });
}

/**
 * @param {boolean} enabled
 * @returns {Promise<{ok: boolean}>}
 */
export async function SetShuffle(enabled) {
    return bridgeCall('Media.SetShuffle', { enabled: Boolean(enabled) });
}

/**
 * @param {'off'|'one'|'all'} mode
 * @returns {Promise<{ok: boolean}>}
 */
export async function SetRepeat(mode) {
    return bridgeCall('Media.SetRepeat', { mode: ['off', 'one', 'all'].includes(mode) ? mode : 'off' });
}

/**
 * @param {number} speed  0.25 – 3.0
 * @returns {Promise<{ok: boolean}>}
 */
export async function SetSpeed(speed) {
    return bridgeCall('Media.SetSpeed', { speed: clamp(Number(speed) || 1, 0.25, 3) });
}

/** Stop, clear the queue and remove the notification. → StateChanged @returns {Promise<{ok: boolean}>} */
export async function Stop() {
    return bridgeCall('Media.Stop');
}

/**
 * The current playback snapshot.
 * @returns {Promise<import('./index').MediaState>}
 */
export async function State() {
    return { ...IDLE_STATE, ...(await bridgeCall('Media.State')) };
}

/**
 * Open the full-screen video player on these items (picture-in-picture).
 *
 * @param {import('./index').QueueItem[]} items
 * @param {{startIndex?: number, startPosition?: number}} [options]
 * @returns {Promise<{ok: boolean}>}
 */
export async function OpenVideo(items, { startIndex = 0, startPosition = -1 } = {}) {
    return bridgeCall('Media.OpenVideo', {
        items: Array.from(items, (item) => ({ type: 'video', ...item })),
        startIndex,
        startPosition,
    });
}

/**
 * Read tags, duration and dimensions; with artworkDest, write cover art or a video frame there as JPEG.
 *
 * @param {string} uri
 * @param {string|null} [artworkDest]
 * @returns {Promise<import('./index').ProbeResult>}
 */
export async function Probe(uri, artworkDest = null) {
    return bridgeCall('Media.Probe', { uri, artworkDest });
}

/**
 * One page of the device library, newest first. Needs library access enabled and permission.
 *
 * @param {'audio'|'video'} [type]
 * @param {{limit?: number, offset?: number}} [options]
 * @returns {Promise<import('./index').LibraryPage>}
 */
export async function Library(type = 'audio', { limit = 60, offset = 0 } = {}) {
    const result = await bridgeCall('Media.Library', {
        type: type === 'video' ? 'video' : 'audio',
        limit: Math.max(1, limit),
        offset: Math.max(0, offset),
    });

    return { ok: Boolean(result.ok), items: result.items ?? [], total: result.total ?? 0, offset: result.offset ?? offset };
}

/**
 * Ask for device library access. → PermissionResult
 * @param {'audio'|'video'} [type]
 * @returns {Promise<{ok: boolean, granted?: boolean, limited?: boolean, error?: string}>}
 */
export async function RequestPermission(type = 'audio') {
    return bridgeCall('Media.RequestPermission', { type: type === 'video' ? 'video' : 'audio' });
}

/**
 * @param {'audio'|'video'} [type]
 * @returns {Promise<{granted: boolean, limited: boolean}>}
 */
export async function HasPermission(type = 'audio') {
    const result = await bridgeCall('Media.HasPermission', { type: type === 'video' ? 'video' : 'audio' });

    return { granted: Boolean(result.granted), limited: Boolean(result.granted && result.limited) };
}

/**
 * Listen for one of the plugin's events (a value of `Events`).
 * NativePHP dispatches native events as a `native-event` DOM event.
 *
 * @param {string} event  e.g. Events.TrackChanged
 * @param {(payload: object) => void} callback
 * @returns {() => void} call to stop listening
 */
export function onMediaEvent(event, callback) {
    const handler = (e) => {
        const name = String(e.detail?.event ?? '').replace(/^\\+/, '').replace(/\\\\/g, '\\');
        if (name === event) {
            callback(e.detail?.payload ?? {});
        }
    };

    document.addEventListener('native-event', handler);

    return () => document.removeEventListener('native-event', handler);
}

/** @param {(state: import('./index').MediaState) => void} callback @returns {() => void} */
export const OnStateChanged = (callback) => onMediaEvent(Events.StateChanged, (p) => callback({ ...IDLE_STATE, ...p }));

/** @param {(track: import('./index').TrackChangedPayload) => void} callback @returns {() => void} */
export const OnTrackChanged = (callback) => onMediaEvent(Events.TrackChanged, callback);

/** @param {(error: {id: ?string, message: ?string}) => void} callback @returns {() => void} */
export const OnPlaybackError = (callback) => onMediaEvent(Events.PlaybackError, callback);

/** @param {() => void} callback @returns {() => void} */
export const OnQueueEnded = (callback) => onMediaEvent(Events.QueueEnded, () => callback());

/** @param {(result: {granted: boolean, type: string, limited?: boolean}) => void} callback @returns {() => void} */
export const OnPermissionResult = (callback) => onMediaEvent(Events.PermissionResult, callback);

export default {
    SetQueue, Play, Pause, Toggle, Next, Previous, SkipTo, Seek, SetShuffle, SetRepeat, SetSpeed, Stop,
    State, OpenVideo, Probe, Library, RequestPermission, HasPermission,
    Events, IDLE_STATE, onMediaEvent, OnStateChanged, OnTrackChanged, OnPlaybackError, OnQueueEnded, OnPermissionResult,
};
