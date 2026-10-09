package com.protendai.media

import android.Manifest
import android.content.ContentUris
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.nativephp.mobile.bridge.BridgeFunction
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Bridge functions for protendai/nativephp-media. Namespace: "Media.*".
 *
 * Playback commands go to MediaManager's controller (main thread, queued
 * until connected) and return at once; the resulting state arrives as
 * StateChanged / TrackChanged events and through Media.State.
 */
object MediaFunctions {

    class SetQueue(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            MediaManager.attach(activity)
            val items = jsonArray(parameters["items"]) ?: return mapOf("ok" to false)
            MediaManager.setQueue(
                items,
                int(parameters["startIndex"], 0),
                long(parameters["startPosition"], -1L),
                bool(parameters["play"], true),
            )
            return mapOf("ok" to true)
        }
    }

    class Play(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = command(activity) { it.play() }
    }

    class Pause(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = command(activity) { it.pause() }
    }

    class Toggle(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = command(activity) {
            if (it.isPlaying) it.pause() else {
                // Replay a finished queue from the top instead of doing nothing.
                if (it.playbackState == androidx.media3.common.Player.STATE_ENDED) it.seekToDefaultPosition(0)
                it.play()
            }
        }
    }

    class Next(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = command(activity) { it.seekToNextMediaItem() }
    }

    /** Media3's "previous": restarts the track if past ~3 s, otherwise goes back one. */
    class Previous(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = command(activity) { it.seekToPrevious() }
    }

    class SkipTo(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val index = int(parameters["index"], -1)
            return command(activity) { if (index in 0 until it.mediaItemCount) it.seekToDefaultPosition(index) }
        }
    }

    class Seek(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val position = long(parameters["position"], 0L).coerceAtLeast(0L)
            return command(activity) { it.seekTo(position) }
        }
    }

    class SetShuffle(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val enabled = bool(parameters["enabled"], false)
            return command(activity) { it.shuffleModeEnabled = enabled }
        }
    }

    class SetRepeat(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val mode = MediaManager.repeatMode(parameters["mode"] as? String)
            return command(activity) { it.repeatMode = mode }
        }
    }

    class SetSpeed(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val speed = ((parameters["speed"] as? Number)?.toFloat() ?: 1f).coerceIn(0.25f, 3f)
            return command(activity) { it.setPlaybackSpeed(speed) }
        }
    }

    class Stop(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>) = command(activity) {
            it.stop()
            it.clearMediaItems()
        }
    }

    /** The cached state object; never blocks on the player. */
    class State(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            MediaManager.attach(activity)
            return toMap(MediaManager.stateJson())
        }
    }

    /** Queue the items and show them in the full-screen video player. */
    class OpenVideo(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            MediaManager.attach(activity)
            val items = jsonArray(parameters["items"]) ?: return mapOf("ok" to false)
            MediaManager.setQueue(items, int(parameters["startIndex"], 0), long(parameters["startPosition"], -1L), true)

            activity.runOnUiThread {
                // By name: VideoActivity lives in its own file and is declared in the manifest.
                activity.startActivity(Intent().setClassName(activity, "com.protendai.media.VideoActivity"))
            }
            return mapOf("ok" to true)
        }
    }

    /**
     * Read tags and duration, and optionally write a JPEG preview (embedded
     * album art for audio, a frame for video) to artworkDest.
     */
    class Probe(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val uri = parameters["uri"] as? String ?: return mapOf("ok" to false)
            val artworkDest = (parameters["artworkDest"] as? String)?.takeIf { it.isNotBlank() }
            val retriever = MediaMetadataRetriever()

            return try {
                when {
                    uri.startsWith("/") -> retriever.setDataSource(uri)
                    uri.startsWith("http://") || uri.startsWith("https://") -> retriever.setDataSource(uri, HashMap<String, String>())
                    else -> retriever.setDataSource(activity, Uri.parse(uri))
                }

                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
                val hasArtwork = artworkDest != null && writeArtwork(retriever, hasVideo, artworkDest)

                mapOf(
                    "ok" to true,
                    "title" to (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: JSONObject.NULL),
                    "artist" to (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: JSONObject.NULL),
                    "album" to (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM) ?: JSONObject.NULL),
                    "durationMs" to (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L),
                    "width" to (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0),
                    "height" to (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0),
                    "hasArtwork" to hasArtwork,
                )
            } catch (e: Exception) {
                mapOf("ok" to false, "error" to (e.message ?: "unreadable"))
            } finally {
                runCatching { retriever.release() }
            }
        }

        private fun writeArtwork(retriever: MediaMetadataRetriever, hasVideo: Boolean, dest: String): Boolean {
            val bitmap = retriever.embeddedPicture?.let(::decodeScaled)
                ?: if (hasVideo) retriever.getScaledFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, MAX_ART, MAX_ART) else null
            bitmap ?: return false

            return runCatching {
                File(dest).parentFile?.mkdirs()
                File(dest).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }
                bitmap.recycle()
                true
            }.getOrDefault(false)
        }

        /** Decode embedded art no larger than MAX_ART on its long edge. */
        private fun decodeScaled(bytes: ByteArray): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_ART) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val longEdge = maxOf(decoded.width, decoded.height)
            if (longEdge <= MAX_ART) return decoded
            val scale = MAX_ART.toFloat() / longEdge
            return Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt(), (decoded.height * scale).toInt(), true)
                .also { if (it != decoded) decoded.recycle() }
        }
    }

    /**
     * Device media from MediaStore, newest first. Without the READ_MEDIA_*
     * permission Android still returns the files this app created itself.
     */
    class Library(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val video = parameters["type"] == "video"
            val limit = int(parameters["limit"], 500).coerceAtLeast(1)
            val offset = int(parameters["offset"], 0).coerceAtLeast(0)
            val collection = if (video) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

            val projection = buildList {
                add(MediaStore.MediaColumns._ID)
                add(MediaStore.MediaColumns.TITLE)
                add(MediaStore.MediaColumns.DURATION)
                add(MediaStore.MediaColumns.SIZE)
                add(MediaStore.MediaColumns.DATE_ADDED)
                add(MediaStore.MediaColumns.RELATIVE_PATH)
                add(MediaStore.MediaColumns.MIME_TYPE)
                if (!video) {
                    add(MediaStore.Audio.AudioColumns.ARTIST)
                    add(MediaStore.Audio.AudioColumns.ALBUM)
                }
            }.toTypedArray()
            val selection = if (video) null else "${MediaStore.Audio.AudioColumns.IS_MUSIC} != 0"

            val items = mutableListOf<Map<String, Any>>()
            var total = 0
            return try {
                activity.contentResolver.query(collection, projection, selection, null, "${MediaStore.MediaColumns.DATE_ADDED} DESC")?.use { cursor ->
                    total = cursor.count
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    // Page with offset/limit: libraries can hold thousands of tracks,
                    // and only one page should ever cross the bridge.
                    if (offset > 0 && !cursor.moveToPosition(offset - 1)) return@use
                    while (cursor.moveToNext() && items.size < limit) {
                        val id = cursor.getLong(idCol)
                        items += mapOf(
                            "id" to "${if (video) "video" else "audio"}:$id",
                            "uri" to ContentUris.withAppendedId(collection, id).toString(),
                            "title" to cursor.string(MediaStore.MediaColumns.TITLE),
                            "artist" to (if (video) JSONObject.NULL else cursor.string(MediaStore.Audio.AudioColumns.ARTIST)),
                            "album" to (if (video) JSONObject.NULL else cursor.string(MediaStore.Audio.AudioColumns.ALBUM)),
                            "durationMs" to cursor.long(MediaStore.MediaColumns.DURATION),
                            "size" to cursor.long(MediaStore.MediaColumns.SIZE),
                            "dateAdded" to cursor.long(MediaStore.MediaColumns.DATE_ADDED),
                            "folder" to cursor.string(MediaStore.MediaColumns.RELATIVE_PATH),
                            "mime" to cursor.string(MediaStore.MediaColumns.MIME_TYPE),
                        )
                    }
                }
                mapOf("ok" to true, "items" to items, "total" to total, "offset" to offset)
            } catch (e: Exception) {
                mapOf("ok" to false, "items" to emptyList<Any>(), "total" to 0, "offset" to offset, "error" to (e.message ?: "query failed"))
            }
        }

        private fun android.database.Cursor.string(column: String): Any =
            getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getString(it) } ?: JSONObject.NULL

        private fun android.database.Cursor.long(column: String): Long =
            getColumnIndex(column).takeIf { it >= 0 && !isNull(it) }?.let { getLong(it) } ?: 0L
    }

    /** Ask for READ_MEDIA_AUDIO / READ_MEDIA_VIDEO. Result: PermissionResult. */
    class RequestPermission(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val type = if (parameters["type"] == "video") "video" else "audio"
            val permissions = permissionsFor(type)

            if (granted(activity, permissions)) {
                MediaManager.attach(activity)
                MediaManager.dispatch("PermissionResult", JSONObject().put("granted", true).put("type", type))
                return mapOf("ok" to true, "granted" to true)
            }

            MediaManager.attach(activity)
            PermissionFragment.request(activity, permissions) { ok ->
                MediaManager.dispatch("PermissionResult", JSONObject().put("granted", ok).put("type", type))
            }
            return mapOf("ok" to true)
        }
    }

    class HasPermission(private val activity: FragmentActivity) : BridgeFunction {
        override fun execute(parameters: Map<String, Any>): Map<String, Any> {
            val type = if (parameters["type"] == "video") "video" else "audio"
            return mapOf("granted" to granted(activity, permissionsFor(type)))
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private const val MAX_ART = 512

    private fun permissionsFor(type: String): Array<String> = when {
        android.os.Build.VERSION.SDK_INT < 33 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        type == "video" -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    }

    private fun granted(activity: FragmentActivity, permissions: Array<String>): Boolean =
        permissions.all { ContextCompat.checkSelfPermission(activity, it) == PackageManager.PERMISSION_GRANTED }

    /** Fire a controller command and refresh the cached state right after. */
    private fun command(activity: FragmentActivity, block: (androidx.media3.session.MediaController) -> Unit): Map<String, Any> {
        MediaManager.attach(activity)
        MediaManager.withController {
            block(it)
            MediaManager.refreshSoon()
        }
        return mapOf("ok" to true)
    }

    /** Nested bridge params arrive as JSONArray; tolerate a List or a JSON string too. */
    private fun jsonArray(value: Any?): JSONArray? = when (value) {
        is JSONArray -> value
        is List<*> -> JSONArray(value.map { if (it is Map<*, *>) JSONObject(it) else it })
        is String -> runCatching { JSONArray(value) }.getOrNull()
        else -> null
    }

    private fun int(value: Any?, default: Int): Int = (value as? Number)?.toInt() ?: default

    private fun long(value: Any?, default: Long): Long = (value as? Number)?.toLong() ?: default

    private fun bool(value: Any?, default: Boolean): Boolean = value as? Boolean ?: default

    private fun toMap(json: JSONObject): Map<String, Any> =
        json.keys().asSequence().associateWith { json.get(it) }
}
