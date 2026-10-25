export interface QueueItem {
    /** Stable key, used for resume positions. */
    id: string;
    /** File path, content:// URI (Android), ipod-library:// or ph:// (iOS library), or http(s) URL. */
    uri: string;
    title?: string | null;
    artist?: string | null;
    album?: string | null;
    /** File path or URL. */
    artwork?: string | null;
    type?: 'audio' | 'video';
}

export interface MediaState {
    playing: boolean;
    buffering: boolean;
    /** -1 when nothing is loaded. */
    index: number;
    count: number;
    id: string | null;
    title: string | null;
    artist: string | null;
    artwork: string | null;
    type: 'audio' | 'video' | null;
    positionMs: number;
    durationMs: number;
    shuffle: boolean;
    repeat: 'off' | 'one' | 'all';
    speed: number;
    error: string | null;
}

export interface TrackChangedPayload {
    id: string | null;
    index: number;
    title: string | null;
    artist: string | null;
    type: 'audio' | 'video' | null;
}

export interface ProbeResult {
    ok: boolean;
    title: string | null;
    artist: string | null;
    album: string | null;
    durationMs: number;
    width: number;
    height: number;
    hasArtwork: boolean;
    error?: string;
}

export interface LibraryItem {
    id: string;
    uri: string;
    title: string | null;
    artist: string | null;
    album: string | null;
    durationMs: number;
    size: number;
    dateAdded: number;
    folder: string | null;
    mime: string | null;
}

export interface LibraryPage {
    ok: boolean;
    items: LibraryItem[];
    total: number;
    offset: number;
}

export interface Ok {
    ok: boolean;
}

export declare const Events: {
    readonly StateChanged: 'Protendai\\Media\\Events\\StateChanged';
    readonly TrackChanged: 'Protendai\\Media\\Events\\TrackChanged';
    readonly PlaybackError: 'Protendai\\Media\\Events\\PlaybackError';
    readonly QueueEnded: 'Protendai\\Media\\Events\\QueueEnded';
    readonly PermissionResult: 'Protendai\\Media\\Events\\PermissionResult';
};

export declare const IDLE_STATE: Readonly<MediaState>;

export function SetQueue(items: QueueItem[], options?: { startIndex?: number; startPosition?: number; play?: boolean }): Promise<Ok>;
export function Play(): Promise<Ok>;
export function Pause(): Promise<Ok>;
export function Toggle(): Promise<Ok>;
export function Next(): Promise<Ok>;
export function Previous(): Promise<Ok>;
export function SkipTo(index: number): Promise<Ok>;
export function Seek(position: number): Promise<Ok>;
export function SetShuffle(enabled: boolean): Promise<Ok>;
export function SetRepeat(mode: 'off' | 'one' | 'all'): Promise<Ok>;
export function SetSpeed(speed: number): Promise<Ok>;
export function Stop(): Promise<Ok>;
export function State(): Promise<MediaState>;
export function OpenVideo(items: QueueItem[], options?: { startIndex?: number; startPosition?: number }): Promise<Ok>;
export function Probe(uri: string, artworkDest?: string | null): Promise<ProbeResult>;
export function Library(type?: 'audio' | 'video', options?: { limit?: number; offset?: number }): Promise<LibraryPage>;
export function RequestPermission(type?: 'audio' | 'video'): Promise<Ok & { granted?: boolean; limited?: boolean; error?: string }>;
export function HasPermission(type?: 'audio' | 'video'): Promise<{ granted: boolean; limited: boolean }>;

export function onMediaEvent(event: (typeof Events)[keyof typeof Events], callback: (payload: Record<string, unknown>) => void): () => void;
export function OnStateChanged(callback: (state: MediaState) => void): () => void;
export function OnTrackChanged(callback: (track: TrackChangedPayload) => void): () => void;
export function OnPlaybackError(callback: (error: { id: string | null; message: string | null }) => void): () => void;
export function OnQueueEnded(callback: () => void): () => void;
export function OnPermissionResult(callback: (result: { granted: boolean; type: 'audio' | 'video'; limited?: boolean }) => void): () => void;

declare const Media: {
    SetQueue: typeof SetQueue; Play: typeof Play; Pause: typeof Pause; Toggle: typeof Toggle; Next: typeof Next;
    Previous: typeof Previous; SkipTo: typeof SkipTo; Seek: typeof Seek; SetShuffle: typeof SetShuffle;
    SetRepeat: typeof SetRepeat; SetSpeed: typeof SetSpeed; Stop: typeof Stop; State: typeof State;
    OpenVideo: typeof OpenVideo; Probe: typeof Probe; Library: typeof Library;
    RequestPermission: typeof RequestPermission; HasPermission: typeof HasPermission;
    Events: typeof Events; IDLE_STATE: typeof IDLE_STATE; onMediaEvent: typeof onMediaEvent;
    OnStateChanged: typeof OnStateChanged; OnTrackChanged: typeof OnTrackChanged; OnPlaybackError: typeof OnPlaybackError;
    OnQueueEnded: typeof OnQueueEnded; OnPermissionResult: typeof OnPermissionResult;
};
export default Media;
