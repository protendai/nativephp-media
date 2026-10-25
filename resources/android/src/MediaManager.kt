package com.protendai.media

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

/**
 * The PHP-facing side of the player: a MediaController connected to
 * PlaybackService, a state cache PHP can read from any thread, event
 * dispatch, and per-item resume positions.
 *
 * All controller calls happen on the main thread (Media3 requires it);
 * commands issued before the controller connects are queued.
 */
@OptIn(UnstableApi::class)
object MediaManager {

    const val TAG = "ProtendaiMedia"
    private const val EVENT_NAMESPACE = "Protendai\\Media\\Events\\"
    private const val PREFS = "protendai_media_positions"
    private const val TICK_MS = 500L
    private const val SAVE_EVERY_MS = 5_000L
    private const val END_MARGIN_MS = 5_000L
    private const val EXTRA_TYPE = "protendai_type"

    private var appContext: Context? = null
    private var activityRef: FragmentActivity? = null
    private val main = Handler(Looper.getMainLooper())

    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private val pending = mutableListOf<(MediaController) -> Unit>() // main thread only

    @Volatile private var state: JSONObject = idleState()
    private var lastSignature: String? = null
    private var lastSaveAt = 0L
    private var currentId: String? = null
    private var lastError: String? = null

    // ── Connection ───────────────────────────────────────────────

    fun attach(activity: FragmentActivity) {
        activityRef = activity
        if (appContext == null) appContext = activity.applicationContext
        main.post { connect() }
    }

    /** Run [block] on the main thread with a connected controller (queued until connected). */
    fun withController(block: (MediaController) -> Unit) {
        main.post {
            val c = controller
            if (c != null) {
                block(c)
            } else {
                pending.add(block)
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
                    Log.w(TAG, "controller disconnected")
                    this@MediaManager.controller = null
                    controllerFuture = null
                }
            })
            .buildAsync()
        controllerFuture = future

        future.addListener({
            val c = runCatching { future.get() }.getOrElse {
                Log.e(TAG, "controller connection failed", it)
                controllerFuture = null
                return@addListener
            }
            controller = c
            c.addListener(playerListener)
            refreshState()
            // Reconnecting while music already plays (app reopened): resume the tick.
            if (c.isPlaying) main.postDelayed(tick, TICK_MS)
            val queued = pending.toList()
            pending.clear()
            queued.forEach { it(c) }
        }, ContextCompat.getMainExecutor(context))
    }

    // ── Queue ────────────────────────────────────────────────────

    /**
     * Replace the queue. [startPositionMs] < 0 resumes the start item from its
     * saved position (0 when there is none).
     */
    fun setQueue(items: JSONArray, startIndex: Int, startPositionMs: Long, play: Boolean) {
        val mediaItems = (0 until items.length()).mapNotNull { items.optJSONObject(it)?.let(::toMediaItem) }
        if (mediaItems.isEmpty()) return

        val index = startIndex.coerceIn(0, mediaItems.size - 1)
        val position = if (startPositionMs >= 0) startPositionMs else savedPosition(mediaItems[index].mediaId)

        withController { c ->
            lastError = null
            c.setMediaItems(mediaItems, index, position)
            c.prepare()
            if (play) c.play()
            refreshState()
        }
    }

    private fun toMediaItem(json: JSONObject): MediaItem? {
        val rawUri = json.optString("uri").takeIf { it.isNotBlank() } ?: return null
        val uri = toUri(rawUri)
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: rawUri
        val type = json.optString("type").takeIf { it == "video" } ?: "audio"

        val metadata = MediaMetadata.Builder()
            .setTitle(json.optNullableString("title") ?: File(rawUri).nameWithoutExtension)
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

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // Leaving an item that played to its end: forget its resume point.
            if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) currentId?.let(::clearPosition)
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
            lastError = error.message ?: error.errorCodeName
            Log.w(TAG, "playback error: $lastError", error)
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

    private fun savedPosition(id: String): Long = prefs()?.getLong(id, 0L) ?: 0L

    private fun savePosition(c: MediaController) {
        lastSaveAt = System.currentTimeMillis()
        val id = c.currentMediaItem?.mediaId ?: return
        val position = c.currentPosition
        val duration = c.duration

        // Near the end counts as finished: next time starts from the top.
        if (duration > 0 && duration - position <= END_MARGIN_MS) {
            clearPosition(id)
        } else if (position > 0) {
            prefs()?.edit()?.putLong(id, position)?.apply()
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

    fun dispatch(event: String, payload: JSONObject) {
        val activity = activityRef ?: return
        activity.runOnUiThread {
            NativeActionCoordinator.dispatchEvent(activity, EVENT_NAMESPACE + event, payload.toString())
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}
