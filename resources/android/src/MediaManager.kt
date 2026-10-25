package com.protendai.media

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.nativephp.mobile.utils.NativeActionCoordinator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference

/**
 * The PHP-facing side of the player: a MediaController connected to
 * PlaybackService, a state cache PHP can read from any thread, event
 * dispatch, and per-item resume positions.
 *
 * All controller calls happen on the main thread (Media3 requires it);
 * commands issued before the controller connects are queued (bounded).
 *
 * Events go to PHP through the NativePHP host activity. That activity is
 * held weakly and re-adopted automatically when the app's UI is recreated
 * (music keeps playing in the service after the activity finishes); events
 * raised while no UI exists are buffered and delivered when it comes back
 * (see [deliver]).
 */
@OptIn(UnstableApi::class)
object MediaManager {

    const val TAG = "ProtendaiMedia"
    const val EXTRA_TYPE = "protendai_type"
    private const val EVENT_NAMESPACE = "Protendai\\Media\\Events\\"
    private const val PREFS = "protendai_media_positions"
    private const val TICK_MS = 500L
    private const val SAVE_EVERY_MS = 5_000L
    private const val END_MARGIN_MS = 5_000L
    private const val MAX_PENDING_COMMANDS = 32
    private const val MAX_BUFFERED_EVENTS = 16

    @Volatile private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    // Main thread only from here on.
    private var activityRef: WeakReference<FragmentActivity>? = null
    private var hostClass: String? = null
    private var lifecycleRegistered = false
    private val bufferedEvents = ArrayDeque<Pair<String, String>>()
    private var bufferedState: String? = null

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()

    @Volatile private var state: JSONObject = idleState()
    private var lastSignature: String? = null
    private var lastSaveAt = 0L
    private var currentId: String? = null
    private var durationId: String? = null
    private var durationMs = 0L
    private var lastError: String? = null

    // ── Connection ───────────────────────────────────────────────

    /**
     * Called by every bridge function with the NativePHP host activity: it
     * becomes the event target, and the controller connects if needed.
     */
    fun attach(activity: FragmentActivity) {
        if (appContext == null) appContext = activity.applicationContext
        main.post {
            registerLifecycle(activity.application)
            adopt(activity)
            flushBuffered()
            connect()
        }
    }

    /** Run [block] on the main thread with a connected controller (queued until connected). */
    fun withController(block: (MediaController) -> Unit) {
        main.post {
            val c = controller
            if (c != null) {
                block(c)
            } else {
                // Bounded: if the service never comes up, don't replay a pile of stale taps later.
                while (pending.size >= MAX_PENDING_COMMANDS) pending.removeFirst()
                pending.addLast(block)
                connect()
            }
        }
    }

    private fun connect() {
        if (controller != null || controllerFuture != null) return
        val context = appContext ?: return

        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token)
            .setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    main.removeCallbacks(tick)
                    this@MediaManager.controller = null
                    controllerFuture = null
                }
            })
            .buildAsync()
        controllerFuture = future

        future.addListener({
            val c = runCatching { future.get() }.getOrElse {
                Log.w(TAG, "Could not connect to the playback service")
                controllerFuture = null
                // The queued commands can't run; drop them rather than replaying them much later.
                pending.clear()
                dispatch(
                    "PlaybackError",
                    JSONObject().put("id", JSONObject.NULL).put("message", "The playback service could not be started"),
                )
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            currentId = c.currentMediaItem?.mediaId
            refreshState()
            // Reconnecting while music already plays (app reopened): resume the tick, once.
            main.removeCallbacks(tick)
            if (c.isPlaying) main.postDelayed(tick, TICK_MS)
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))
    }

    // ── Queue ────────────────────────────────────────────────────

    /**
     * Replace the queue. [startPositionMs] < 0 resumes the start item from its
     * saved position (0 when there is none). Returns false when no item is usable.
     */
    fun setQueue(items: JSONArray, startIndex: Int, startPositionMs: Long, play: Boolean): Boolean {
        val mediaItems = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::toMediaItem) }
        if (mediaItems.isEmpty()) return false

        val index = startIndex.coerceIn(0, mediaItems.size - 1)

        withController { c ->
            // Remember where the outgoing item was before it is replaced
            // (it may be the same id as the new start item).
            savePosition(c)
            val position = if (startPositionMs >= 0) startPositionMs else savedPosition(mediaItems[index].mediaId)
            lastError = null
            c.setMediaItems(mediaItems, index, position)
            c.prepare()
            if (play) c.play()
            refreshState()
        }
        return true
    }

    /** Jump to queue [index] and play it, resuming its saved position (same rule as setQueue). */
    fun skipTo(c: MediaController, index: Int) {
        if (index !in 0 until c.mediaItemCount) return
        savePosition(c)
        c.seekTo(index, savedPosition(c.getMediaItemAt(index).mediaId))
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    /** Play; a finished queue starts again from its first item. */
    fun play(c: MediaController) {
        if (c.playbackState == Player.STATE_ENDED && !c.currentTimeline.isEmpty) {
            c.seekToDefaultPosition(c.currentTimeline.getFirstWindowIndex(c.shuffleModeEnabled))
        }
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    private fun toMediaItem(json: JSONObject): MediaItem? {
        val rawUri = json.optNullableString("uri") ?: return null
        val uri = toUri(rawUri)
        val id = json.optNullableString("id") ?: rawUri
        val type = if (json.optString("type") == "video") "video" else "audio"

        val metadata = MediaMetadata.Builder()
            .setTitle(json.optNullableString("title") ?: File(uri.path ?: rawUri).nameWithoutExtension)
            .setArtist(json.optNullableString("artist"))
            .setAlbumTitle(json.optNullableString("album"))
            .setArtworkUri(json.optNullableString("artwork")?.let(::toUri))
            .setMediaType(if (type == "video") MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putString(EXTRA_TYPE, type) })
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setUri(uri)
            .setMediaMetadata(metadata)
            // Survives the controller → session hop even if the URI doesn't (see PlaybackService).
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
    }

    private fun toUri(value: String): Uri =
        if (value.startsWith("/")) Uri.fromFile(File(value)) else Uri.parse(value)

    // ── State ────────────────────────────────────────────────────

    /** A snapshot of the cached state; safe from any thread. */
    fun stateJson(): JSONObject = JSONObject(state.toString())

    private fun idleState(): JSONObject = JSONObject()
        .put("playing", false)
        .put("buffering", false)
        .put("index", -1)
        .put("count", 0)
        .put("id", JSONObject.NULL)
        .put("title", JSONObject.NULL)
        .put("artist", JSONObject.NULL)
        .put("artwork", JSONObject.NULL)
        .put("type", JSONObject.NULL)
        .put("positionMs", 0)
        .put("durationMs", 0)
        .put("shuffle", false)
        .put("repeat", "off")
        .put("speed", 1.0)
        .put("error", JSONObject.NULL)

    /** Rebuild the cache from the controller (main thread) and announce real changes. */
    private fun refreshState() {
        val c = controller ?: return
        val item = c.currentMediaItem
        val meta = item?.mediaMetadata

        if (item != null && c.duration > 0) {
            durationId = item.mediaId
            durationMs = c.duration
        }

        val next = JSONObject()
            .put("playing", c.isPlaying)
            .put("buffering", c.playbackState == Player.STATE_BUFFERING)
            .put("index", if (item == null) -1 else c.currentMediaItemIndex)
            .put("count", c.mediaItemCount)
            .put("id", item?.mediaId ?: JSONObject.NULL)
            .put("title", meta?.title?.toString() ?: JSONObject.NULL)
            .put("artist", meta?.artist?.toString() ?: JSONObject.NULL)
            .put("artwork", meta?.artworkUri?.let { if (it.scheme == "file") it.path else it.toString() } ?: JSONObject.NULL)
            .put("type", meta?.extras?.getString(EXTRA_TYPE) ?: (if (item == null) JSONObject.NULL else "audio"))
            .put("positionMs", c.currentPosition.coerceAtLeast(0))
            .put("durationMs", c.duration.takeIf { it > 0 } ?: 0)
            .put("shuffle", c.shuffleModeEnabled)
            .put("repeat", repeatName(c.repeatMode))
            .put("speed", c.playbackParameters.speed.toDouble())
            .put("error", lastError ?: JSONObject.NULL)
        state = next

        // Position moves every tick; only the rest counts as a change worth an event.
        val signature = JSONObject(next.toString()).apply { remove("positionMs") }.toString()
        if (signature != lastSignature) {
            lastSignature = signature
            dispatch("StateChanged", next)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val c = controller ?: return
            refreshState()
            if (System.currentTimeMillis() - lastSaveAt >= SAVE_EVERY_MS) savePosition(c)
            main.removeCallbacks(this)
            if (c.isPlaying) main.postDelayed(this, TICK_MS)
        }
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            main.removeCallbacks(tick)
            if (isPlaying) {
                main.postDelayed(tick, TICK_MS)
            } else {
                controller?.let(::savePosition)
            }
        }

        /**
         * A seek that lands on another item (next, previous, skipTo, or a
         * notification / Bluetooth control): save where the outgoing item was.
         * Media3 reports the old position here, before anything else moves.
         */
        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason != Player.DISCONTINUITY_REASON_SEEK) return
            if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex) return
            val id = oldPosition.mediaItem?.mediaId ?: return
            savePositionFor(id, oldPosition.positionMs, if (id == durationId) durationMs else 0L)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Leaving an item that played to its end: forget its resume point.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO || reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) {
                currentId?.let(::clearPosition)
            }
            currentId = mediaItem?.mediaId
            lastError = null

            val meta = mediaItem?.mediaMetadata
            dispatch(
                "TrackChanged",
                JSONObject()
                    .put("id", mediaItem?.mediaId ?: JSONObject.NULL)
                    .put("index", controller?.currentMediaItemIndex ?: -1)
                    .put("title", meta?.title?.toString() ?: JSONObject.NULL)
                    .put("artist", meta?.artist?.toString() ?: JSONObject.NULL)
                    .put("type", meta?.extras?.getString(EXTRA_TYPE) ?: JSONObject.NULL),
            )
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                currentId?.let(::clearPosition)
                dispatch("QueueEnded", JSONObject())
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            // The message can contain the file path or URL: give PHP the error, keep logs free of it.
            lastError = error.message ?: error.errorCodeName
            Log.w(TAG, "Playback error ${error.errorCodeName}")
            dispatch(
                "PlaybackError",
                JSONObject()
                    .put("id", controller?.currentMediaItem?.mediaId ?: JSONObject.NULL)
                    .put("message", lastError),
            )
        }

        override fun onEvents(player: Player, events: Player.Events) {
            refreshState()
        }
    }

    // ── Resume positions ─────────────────────────────────────────

    private fun prefs() = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedPosition(id: String): Long = prefs()?.getLong(id, 0L) ?: 0L

    /** Save the current item's position (main thread). */
    fun savePosition(c: MediaController) {
        lastSaveAt = System.currentTimeMillis()
        val id = c.currentMediaItem?.mediaId ?: return
        savePositionFor(id, c.currentPosition, c.duration)
    }

    private fun savePositionFor(id: String, positionMs: Long, durationMs: Long) {
        // Near the end counts as finished: next time starts from the top.
        if (durationMs > 0 && durationMs - positionMs <= END_MARGIN_MS) {
            clearPosition(id)
        } else if (positionMs > 0) {
            prefs()?.edit()?.putLong(id, positionMs)?.apply()
        }
    }

    private fun clearPosition(id: String) {
        prefs()?.edit()?.remove(id)?.apply()
    }

    // ── Helpers ──────────────────────────────────────────────────

    fun repeatName(mode: Int): String = when (mode) {
        Player.REPEAT_MODE_ONE -> "one"
        Player.REPEAT_MODE_ALL -> "all"
        else -> "off"
    }

    fun repeatMode(name: String?): Int = when (name) {
        "one" -> Player.REPEAT_MODE_ONE
        "all" -> Player.REPEAT_MODE_ALL
        else -> Player.REPEAT_MODE_OFF
    }

    /** Called by bridge commands after mutating the player, so State() is fresh at once. */
    fun refreshSoon() {
        main.post { refreshState() }
    }

    fun context(): Context? = appContext

    // ── Events ───────────────────────────────────────────────────

    /** Send a Protendai\Media\Events\<event> to PHP (any thread). */
    fun dispatch(event: String, payload: JSONObject) {
        val json = payload.toString()
        main.post { deliver(event, json) }
    }

    /**
     * Deliver to the live host activity. Without one (the activity finished
     * while music plays on, or is being recreated) the event is buffered:
     * the latest StateChanged plus up to [MAX_BUFFERED_EVENTS] others, sent
     * in order once the activity is back.
     */
    private fun deliver(event: String, json: String) {
        val activity = liveActivity()
        if (activity != null && runCatching {
                NativeActionCoordinator.dispatchEvent(activity, EVENT_NAMESPACE + event, json)
            }.isSuccess
        ) {
            return
        }

        if (event == "StateChanged") {
            bufferedState = json
        } else {
            while (bufferedEvents.size >= MAX_BUFFERED_EVENTS) bufferedEvents.removeFirst()
            bufferedEvents.addLast(event to json)
        }
    }

    private fun flushBuffered() {
        if (liveActivity() == null) return
        val events = bufferedEvents.toList()
        val stateJson = bufferedState
        bufferedEvents.clear()
        bufferedState = null
        events.forEach { (event, json) -> deliver(event, json) }
        // Always end on the freshest state.
        if (stateJson != null) deliver("StateChanged", state.toString())
    }

    private fun liveActivity(): FragmentActivity? =
        activityRef?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }

    private fun adopt(activity: FragmentActivity) {
        hostClass = activity.javaClass.name
        if (activityRef?.get() !== activity) activityRef = WeakReference(activity)
    }

    /**
     * Follow the host activity across recreation: a new instance of the
     * activity class that called the bridge becomes the event target as soon
     * as it is resumed, without waiting for PHP to call the plugin again.
     */
    private fun registerLifecycle(application: Application) {
        if (lifecycleRegistered) return
        lifecycleRegistered = true
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {
                if (activity is FragmentActivity && activity.javaClass.name == hostClass) {
                    adopt(activity)
                    // Let the activity finish resuming before touching its fragments.
                    main.post { flushBuffered() }
                }
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (activityRef?.get() === activity) activityRef = null
            }
        })
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
