package com.protendai.media

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.Player
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Wraps the PlayerView and adds MX-Player-style gestures on top of it,
 * without stealing taps from the built-in controls:
 *
 * - double-tap left/right third: −/+10 s (middle third: play/pause)
 * - horizontal drag: scrub, seeking on release
 * - vertical drag, left half: window brightness; right half: media volume
 *
 * Drags are only intercepted once they pass the touch slop, so plain taps
 * still reach the PlayerView (which toggles its controller) and the seek bar
 * keeps its own drags (DefaultTimeBar disallows parent interception).
 */
@SuppressLint("ViewConstructor")
class VideoGestureOverlay(context: Context, private val host: Host) : FrameLayout(context) {

    interface Host {
        val player: Player?
        fun currentBrightness(): Float
        fun setBrightness(value: Float)
        fun controllerFullyVisible(): Boolean
    }

    private enum class Mode { NONE, SEEK, BRIGHTNESS, VOLUME }

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val edgeGuard = dp(40f)

    private var mode = Mode.NONE
    private var eligible = false
    private var downX = 0f
    private var downY = 0f

    private var seekStart = 0L
    private var seekTarget = 0L
    private var seekDuration = 0L
    private var brightnessStart = 0.5f
    private var volumeStart = 0
    private var volumeMax = 1

    /** Centered feedback bubble shared by every gesture. Not clickable, so it never eats touches. */
    val bubble: TextView = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        gravity = Gravity.CENTER
        val padH = dp(18f).toInt()
        val padV = dp(10f).toInt()
        setPadding(padH, padV, padH, padV)
        background = GradientDrawable().apply {
            cornerRadius = dp(14f)
            setColor(Color.argb(170, 0, 0, 0))
        }
        visibility = View.GONE
        isClickable = false
        isFocusable = false
    }

    private val hideBubble = Runnable { bubble.visibility = View.GONE }

    private val doubleTaps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val player = host.player ?: return false
            val third = width / 3f
            when {
                e.x < third -> {
                    player.seekTo((player.currentPosition - 10_000L).coerceAtLeast(0L))
                    flash("−10s")
                }
                e.x > third * 2 -> {
                    val duration = player.duration
                    val target = player.currentPosition + 10_000L
                    player.seekTo(if (duration != C.TIME_UNSET && duration > 0) target.coerceAtMost(duration) else target)
                    flash("+10s")
                }
                else -> {
                    if (player.isPlaying) {
                        player.pause()
                        flash("⏸")
                    } else {
                        player.play()
                        flash("▶")
                    }
                }
            }
            return true
        }
    })

    fun attachBubble() {
        addView(bubble, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    /** Show the bubble with [text] briefly. */
    fun flash(text: String, durationMs: Long = 700L) {
        removeCallbacks(hideBubble)
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubble.bringToFront()
        postDelayed(hideBubble, durationMs)
    }

    private fun show(text: String) {
        removeCallbacks(hideBubble)
        bubble.text = text
        bubble.visibility = View.VISIBLE
        bubble.bringToFront()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // Observe only: double taps still also reach the PlayerView, which
        // toggles its controller twice (net no change).
        doubleTaps.onTouchEvent(ev)
        return super.dispatchTouchEvent(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                mode = Mode.NONE
                eligible = isEligibleStart(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!eligible || mode != Mode.NONE || ev.pointerCount > 1) return false
                val dx = ev.x - downX
                val dy = ev.y - downY
                if (abs(dx) > slop || abs(dy) > slop) {
                    mode = when {
                        abs(dx) > abs(dy) -> Mode.SEEK
                        downX < width / 2f -> Mode.BRIGHTNESS
                        else -> Mode.VOLUME
                    }
                    if (!beginDrag()) {
                        mode = Mode.NONE
                        eligible = false
                        return false
                    }
                    return true
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> eligible = false
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (mode == Mode.NONE) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> updateDrag(ev.x - downX, ev.y - downY)
            MotionEvent.ACTION_UP -> endDrag(commit = true)
            MotionEvent.ACTION_CANCEL -> endDrag(commit = false)
        }
        return true
    }

    /** Ignore drags from the system-gesture edges and, with controls showing, from the seek bar strip. */
    private fun isEligibleStart(ev: MotionEvent): Boolean {
        if (ev.y < edgeGuard || ev.y > height - edgeGuard) return false
        if (ev.x < edgeGuard / 2 || ev.x > width - edgeGuard / 2) return false
        if (host.controllerFullyVisible() && ev.y > height * 0.82f) return false
        return true
    }

    private fun beginDrag(): Boolean {
        when (mode) {
            Mode.SEEK -> {
                val player = host.player ?: return false
                val duration = player.duration
                if (duration == C.TIME_UNSET || duration <= 0) return false
                seekDuration = duration
                seekStart = player.currentPosition
                seekTarget = seekStart
            }
            Mode.BRIGHTNESS -> brightnessStart = host.currentBrightness()
            Mode.VOLUME -> {
                volumeMax = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                volumeStart = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            Mode.NONE -> return false
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    private fun updateDrag(dx: Float, dy: Float) {
        when (mode) {
            Mode.SEEK -> {
                // Full width scrubs 90 s (or the whole clip if shorter).
                val span = seekDuration.coerceAtMost(90_000L).toFloat()
                val delta = (dx / width.coerceAtLeast(1) * span).toLong()
                seekTarget = (seekStart + delta).coerceIn(0L, seekDuration)
                val sign = if (seekTarget >= seekStart) "+" else "−"
                show("${formatTime(seekTarget)} / ${formatTime(seekDuration)}\n$sign${formatTime(abs(seekTarget - seekStart))}")
            }
            Mode.BRIGHTNESS -> {
                val value = (brightnessStart - dy / (height * 0.8f)).coerceIn(0.01f, 1f)
                host.setBrightness(value)
                show("☀ ${(value * 100).roundToInt()}%")
            }
            Mode.VOLUME -> {
                val value = (volumeStart - dy / (height * 0.8f) * volumeMax).roundToInt().coerceIn(0, volumeMax)
                audio.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
                show("🔊 ${value * 100 / volumeMax}%")
            }
            Mode.NONE -> Unit
        }
    }

    private fun endDrag(commit: Boolean) {
        if (commit && mode == Mode.SEEK) {
            host.player?.seekTo(seekTarget)
        }
        mode = Mode.NONE
        eligible = false
        postDelayed(hideBubble, 500L)
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        fun formatTime(ms: Long): String {
            val totalSeconds = (ms.coerceAtLeast(0L) / 1000L)
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
            else "%d:%02d".format(minutes, seconds)
        }
    }
}
