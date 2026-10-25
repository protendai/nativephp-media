package com.protendai.media

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import org.json.JSONArray
import org.json.JSONObject

/**
 * Owns the app's single ExoPlayer inside a MediaSessionService, so playback
 * outlives the activity (and the PHP runtime's attention). Media3 posts the
 * media notification, which also drives lock-screen and Bluetooth controls,
 * and promotes the service to the foreground while playing.
 *
 * Everything else (PHP bridge, VideoActivity) talks to this player through a
 * MediaController, never directly.
 *
 * The service is exported (system UI, Bluetooth and other media controllers
 * must be able to bind), so [SessionCallback] limits what other packages may
 * do: play/pause/seek/skip only, never load their own media items.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus = */ true, // pause for calls, duck for notifications
            )
            .setHandleAudioBecomingNoisy(true) // pause when headphones are unplugged
            .setWakeMode(C.WAKE_MODE_NETWORK)  // keep CPU + Wi-Fi up while playing in the background
            .build()

        player.addListener(ResumeRecorder(applicationContext, player))

        val builder = MediaSession.Builder(this, player).setCallback(SessionCallback(packageName, applicationContext))

        // Tapping the notification reopens the app.
        packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
            builder.setSessionActivity(
                PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        }

        session = builder.build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from recents: keep playing if music is on, otherwise shut down. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        session?.run {
            ResumeStore.savePosition(applicationContext, player)
            player.release()
            release()
        }
        session = null
        super.onDestroy()
    }

    /**
     * Connection policy and media-item resolution.
     *
     * - This app's own controllers (MediaManager, VideoActivity, Media3's
     *   notification controller) get every command.
     * - Any other package (system UI media controls, Bluetooth / headsets,
     *   Wear, Auto, other apps) gets playback commands only: no
     *   COMMAND_SET_MEDIA_ITEM / COMMAND_CHANGE_MEDIA_ITEMS, so it cannot make
     *   this app's player open a URI of its choosing (including app-private
     *   files the other app couldn't read itself).
     */
    private class SessionCallback(
        private val ownPackage: String,
        private val context: Context,
    ) : MediaSession.Callback {

        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult {
            if (controller.packageName == ownPackage) {
                return super.onConnect(session, controller)
            }

            val playerCommands = MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS.buildUpon()
                .remove(Player.COMMAND_SET_MEDIA_ITEM)
                .remove(Player.COMMAND_CHANGE_MEDIA_ITEMS)
                .build()

            return MediaSession.ConnectionResult.accept(
                MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS,
                playerCommands,
            )
        }

        /**
         * The URI (local configuration) doesn't survive the controller →
         * session hop, so MediaManager also puts it in requestMetadata and it
         * is restored here, for this app's own controllers only.
         */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            if (controller.packageName != ownPackage) {
                return Futures.immediateFailedFuture(UnsupportedOperationException("Only this app can queue media"))
            }

            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri
                if (item.localConfiguration == null && uri != null) item.buildUpon().setUri(uri).build() else item
            }
            return Futures.immediateFuture(resolved.toMutableList())
        }

        /**
         * System UI's "resume" card (Android 13+) and a media button pressed
         * while nothing is loaded: bring back the last queue where it stopped.
         */
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val saved = ResumeStore.load(context)
                ?: return Futures.immediateFailedFuture(UnsupportedOperationException("Nothing to resume"))
            return Futures.immediateFuture(saved)
        }
    }

    /** Keeps [ResumeStore] in step with the player's queue and position. */
    private class ResumeRecorder(private val context: Context, private val player: Player) : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            if (events.contains(Player.EVENT_TIMELINE_CHANGED)) {
                ResumeStore.saveQueue(context, player)
            }
            if (events.containsAny(Player.EVENT_MEDIA_ITEM_TRANSITION, Player.EVENT_IS_PLAYING_CHANGED)) {
                ResumeStore.savePosition(context, player)
            }
        }
    }
}

/**
 * The last queue, for playback resumption after the app (or the phone) was
 * restarted. Separate from MediaManager's per-item resume positions.
 */
@OptIn(UnstableApi::class)
internal object ResumeStore {

    private const val PREFS = "protendai_media_resume"
    private const val KEY_QUEUE = "queue"
    private const val KEY_INDEX = "index"
    private const val KEY_POSITION = "position"
    private const val MAX_ITEMS = 500

    fun saveQueue(context: Context, player: Player) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val count = player.mediaItemCount
        if (count == 0) {
            prefs.edit().clear().apply()
            return
        }

        // Keep a window around the current item for very long queues.
        val current = player.currentMediaItemIndex.coerceIn(0, count - 1)
        val from = (current - MAX_ITEMS / 2).coerceAtLeast(0).coerceAtMost((count - MAX_ITEMS).coerceAtLeast(0))
        val until = minOf(count, from + MAX_ITEMS)

        val items = JSONArray()
        for (i in from until until) {
            val item = player.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri ?: item.requestMetadata.mediaUri ?: continue
            val meta = item.mediaMetadata
            items.put(
                JSONObject()
                    .put("id", item.mediaId)
                    .put("uri", uri.toString())
                    .put("title", meta.title?.toString())
                    .put("artist", meta.artist?.toString())
                    .put("album", meta.albumTitle?.toString())
                    .put("artwork", meta.artworkUri?.toString())
                    .put("type", meta.extras?.getString(MediaManager.EXTRA_TYPE) ?: "audio"),
            )
        }

        prefs.edit()
            .putString(KEY_QUEUE, JSONObject().put("from", from).put("items", items).toString())
            .putInt(KEY_INDEX, current)
            .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0))
            .apply()
    }

    fun savePosition(context: Context, player: Player) {
        if (player.mediaItemCount == 0) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(KEY_INDEX, player.currentMediaItemIndex)
            .putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0))
            .apply()
    }

    fun load(context: Context): MediaSession.MediaItemsWithStartPosition? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val queue = prefs.getString(KEY_QUEUE, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val from = queue.optInt("from", 0)
        val array = queue.optJSONArray("items") ?: return null

        val items = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.let(::toMediaItem) }
        if (items.isEmpty()) return null

        val index = (prefs.getInt(KEY_INDEX, from) - from).coerceIn(0, items.size - 1)
        val position = prefs.getLong(KEY_POSITION, 0L).coerceAtLeast(0L)
        return MediaSession.MediaItemsWithStartPosition(items, index, position)
    }

    private fun toMediaItem(json: JSONObject): MediaItem? {
        val uri = json.optString("uri").takeIf { it.isNotBlank() }?.let(Uri::parse) ?: return null
        val type = if (json.optString("type") == "video") "video" else "audio"
        fun text(key: String): String? = if (json.isNull(key)) null else json.optString(key).takeIf { it.isNotBlank() }

        val metadata = MediaMetadata.Builder()
            .setTitle(text("title"))
            .setArtist(text("artist"))
            .setAlbumTitle(text("album"))
            .setArtworkUri(text("artwork")?.let(Uri::parse))
            .setMediaType(if (type == "video") MediaMetadata.MEDIA_TYPE_VIDEO else MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(Bundle().apply { putString(MediaManager.EXTRA_TYPE, type) })
            .build()

        return MediaItem.Builder()
            .setMediaId(text("id") ?: uri.toString())
            .setUri(uri)
            .setMediaMetadata(metadata)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .build()
    }
}
