package com.protendai.media

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.util.Rational
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.ui.PlayerView

/**
 * Full-screen video player. It never owns a player: it binds a Media3
 * PlayerView to MediaManager's MediaController, so playback runs through
 * PlaybackService (notification, lock screen, resume) like audio does.
 *
 * Launched by Media.OpenVideo after the queue is set. Gestures live in
 * [VideoGestureOverlay]; the top bar (back, title, speed, PiP) follows the
 * PlayerView controller's visibility. Picture-in-picture needs
 * android:supportsPictureInPicture on this activity, which the plugin's
 * post_compile hook (nativephp:media:enable-pip) adds to the manifest.
 */
@OptIn(UnstableApi::class)
class VideoActivity : AppCompatActivity(), VideoGestureOverlay.Host {

    private lateinit var root: VideoGestureOverlay
    private lateinit var playerView: PlayerView
    private lateinit var topBar: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var speedView: TextView

    private var controller: MediaController? = null

    /** Queue ids this window opened (Media.OpenVideo); see [ownsPlayback]. */
    private var openedIds: Set<String> = emptySet()

    override val player: Player? get() = controller

    private val speeds = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    private val listener = object : Player.Listener {
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            applyOrientation(videoSize)
            updatePipParams()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePipParams()
        }

        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            titleView.text = mediaMetadata.title ?: ""
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            speedView.text = formatSpeed(playbackParameters.speed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openedIds = intent.getStringArrayListExtra(EXTRA_IDS)?.toSet() ?: emptySet()
        window.decorView.setBackgroundColor(Color.BLACK)
        enterImmersive()

        root = VideoGestureOverlay(this, this).apply { setBackgroundColor(Color.BLACK) }

        playerView = PlayerView(this).apply {
            useController = true
            controllerShowTimeoutMs = 3500
            controllerAutoShow = true
            setKeepContentOnPlayerReset(true)
            setShowNextButton(true)
            setShowPreviousButton(true)
            setBackgroundColor(Color.BLACK)
            setControllerVisibilityListener(PlayerView.ControllerVisibilityListener { visibility ->
                if (::topBar.isInitialized && !isInPictureInPictureMode) topBar.visibility = visibility
            })
        }
        root.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        topBar = buildTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        root.attachBubble()

        // Keep the top bar clear of a notch / cutout in landscape.
        ViewCompat.setOnApplyWindowInsetsListener(topBar) { view, insets ->
            val cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.systemBars())
            val pad = dp(8f).toInt()
            view.setPadding(cutout.left + pad, cutout.top + pad, cutout.right + pad, pad)
            insets
        }

        setContentView(root)

        // The main (NativePHP) activity already attached MediaManager when it
        // called Media.OpenVideo; attaching here would redirect PHP events to
        // this activity, so only borrow the connected controller.
        MediaManager.withController { connected ->
            if (isDestroyed) return@withController
            controller = connected
            playerView.player = connected
            connected.addListener(listener)
            titleView.text = connected.mediaMetadata.title ?: ""
            speedView.text = formatSpeed(connected.playbackParameters.speed)
            applyOrientation(connected.videoSize)
            if (connected.isPlaying) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            updatePipParams()
        }
    }

    /** singleTask: a second Media.OpenVideo reuses this window (and leaves PiP). */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openedIds = intent.getStringArrayListExtra(EXTRA_IDS)?.toSet() ?: openedIds
    }

    /**
     * Whether the shared player still plays something this window opened.
     * Music started from the app while the video sat in PiP replaces the
     * queue; closing the video window must not stop that music.
     */
    private fun ownsPlayback(): Boolean {
        val id = controller?.currentMediaItem?.mediaId ?: return false
        return openedIds.isEmpty() || id in openedIds
    }

    // ── Top bar ──────────────────────────────────────────────────

    private fun buildTopBar(): LinearLayout {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.argb(170, 0, 0, 0), Color.TRANSPARENT),
            )
            val pad = dp(8f).toInt()
            setPadding(pad, pad, pad, pad)
        }

        val back = glyphButton("←", "Back") { finish() }
        titleView = TextView(this).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(dp(8f).toInt(), 0, dp(8f).toInt(), 0)
        }
        speedView = glyphButton("1×", "Playback speed") { cycleSpeed() }
        val pip = glyphButton("⧉", "Picture in picture") { enterPip() }

        bar.addView(back)
        bar.addView(titleView, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        bar.addView(speedView)
        bar.addView(pip)
        return bar
    }

    private fun glyphButton(glyph: String, label: String, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = glyph
            contentDescription = label
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            gravity = Gravity.CENTER
            minWidth = dp(48f).toInt()
            minHeight = dp(48f).toInt()
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }

    private fun cycleSpeed() {
        val player = controller ?: return
        val current = player.playbackParameters.speed
        val next = speeds.firstOrNull { it > current + 0.01f } ?: speeds.first()
        player.setPlaybackSpeed(next)
        speedView.text = formatSpeed(next)
        root.flash(formatSpeed(next))
    }

    private fun formatSpeed(speed: Float): String =
        if (speed % 1f == 0f) "${speed.toInt()}×" else "${"%.2f".format(speed).trimEnd('0').trimEnd('.')}×"

    // ── Picture-in-picture ───────────────────────────────────────

    private fun pipParams(): PictureInPictureParams {
        val size = controller?.videoSize
        val builder = PictureInPictureParams.Builder()
        if (size != null && size.width > 0 && size.height > 0) {
            val ratio = (size.width * size.pixelWidthHeightRatio / size.height).coerceIn(0.42f, 2.39f)
            builder.setAspectRatio(Rational((ratio * 1000).toInt(), 1000))
        }
        return builder.build()
    }

    private fun updatePipParams() {
        runCatching { setPictureInPictureParams(pipParams()) }
    }

    private fun enterPip() {
        if (isInPictureInPictureMode) return
        runCatching { enterPictureInPictureMode(pipParams()) }
            .onFailure { Log.w(TAG, "PiP unavailable (is supportsPictureInPicture set?)", it) }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (controller?.isPlaying == true) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        if (isInPictureInPictureMode) {
            playerView.hideController()
            playerView.useController = false
            topBar.visibility = View.GONE
            root.bubble.visibility = View.GONE
        } else {
            // Leaving PiP while already stopped means the window was dismissed, not expanded
            // (some Android versions report the mode change before onStop, some after).
            if (lifecycle.currentState == Lifecycle.State.CREATED) {
                if (ownsPlayback()) controller?.pause()
                finish()
                return
            }
            playerView.useController = true
            enterImmersive()
        }
    }

    // ── Gesture host ─────────────────────────────────────────────

    override fun currentBrightness(): Float {
        val current = window.attributes.screenBrightness
        if (current >= 0f) return current
        val system = runCatching { Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        return (system / 255f).coerceIn(0.01f, 1f)
    }

    override fun setBrightness(value: Float) {
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    override fun controllerFullyVisible(): Boolean = playerView.isControllerFullyVisible

    // ── Window ───────────────────────────────────────────────────

    private fun applyOrientation(size: VideoSize) {
        if (size.width <= 0 || size.height <= 0 || isInPictureInPictureMode) return
        val wide = size.width * size.pixelWidthHeightRatio > size.height
        requestedOrientation = if (wide) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    private fun enterImmersive() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isInPictureInPictureMode) enterImmersive()
    }

    override fun onStop() {
        super.onStop()
        if (isInPictureInPictureMode) {
            // A PiP window only stops when the user dismisses it (swipe away / ✕):
            // pause, and close this activity so it doesn't linger in recents.
            if (ownsPlayback()) controller?.pause()
            finish()
        } else if (ownsPlayback()) {
            // Left the full-screen player without PiP: pause; the session keeps the position.
            controller?.pause()
        }
    }

    override fun onDestroy() {
        // Closing the video (back, or dismissing the PiP window) ends its playback so no
        // paused media notification lingers. Pause first so the resume position is saved.
        // Only if the shared player still holds this window's video.
        if (!isChangingConfigurations && ownsPlayback()) {
            controller?.pause()
            controller?.stop()
        }
        controller?.removeListener(listener)
        playerView.player = null
        controller = null
        super.onDestroy()
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val TAG = "ProtendaiMedia"
        const val EXTRA_IDS = "com.protendai.media.VIDEO_IDS"
    }
}
