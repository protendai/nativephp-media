package com.protendai.media

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject

/**
 * Headless fragment that runs one runtime-permission request and removes
 * itself. A plugin can't override the host activity's
 * onRequestPermissionsResult, so the Activity Result API lives here instead.
 *
 * Everything it needs is in its arguments, so a request survives the
 * activity being recreated mid-dialog; if the request is lost anyway, it
 * still answers with a PermissionResult carrying the current state.
 */
class PermissionFragment : Fragment() {

    private var launched = false
    private var answered = false

    private val launcher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        answer()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        launched = savedInstanceState?.getBoolean(KEY_LAUNCHED) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_LAUNCHED, launched)
    }

    override fun onStart() {
        super.onStart()
        if (launched) return
        launched = true

        val permissions = requireArguments().getStringArray(ARG_PERMISSIONS)
        if (permissions.isNullOrEmpty()) {
            answer()
            return
        }
        launcher.launch(permissions)
    }

    override fun onDestroy() {
        // Removed or lost before the system answered (e.g. the activity went away
        // for good): still tell PHP, with whatever access the app has now.
        if (!answered && activity?.isChangingConfigurations != true) answer()
        super.onDestroy()
    }

    private fun answer() {
        if (answered) return
        answered = true
        val type = arguments?.getString(ARG_TYPE) ?: "audio"
        val context = context ?: MediaManager.context()
        if (context != null) MediaPermissions.dispatchResult(context, type)
        if (isAdded) parentFragmentManager.beginTransaction().remove(this).commitAllowingStateLoss()
    }

    companion object {
        private const val TAG = "protendai-media-permission"
        private const val ARG_TYPE = "type"
        private const val ARG_PERMISSIONS = "permissions"
        private const val KEY_LAUNCHED = "launched"

        fun request(activity: FragmentActivity, type: String, permissions: Array<String>) {
            activity.runOnUiThread {
                if (activity.isFinishing || activity.isDestroyed) {
                    MediaPermissions.dispatchResult(activity, type)
                    return@runOnUiThread
                }
                val fragment = PermissionFragment().apply {
                    arguments = Bundle().apply {
                        putString(ARG_TYPE, type)
                        putStringArray(ARG_PERMISSIONS, permissions)
                    }
                }
                activity.supportFragmentManager.beginTransaction().add(fragment, TAG).commitAllowingStateLoss()
            }
        }
    }
}

/**
 * Which runtime permissions the device library needs, and what the app holds.
 *
 * - Android 13+: READ_MEDIA_AUDIO for audio; READ_MEDIA_VIDEO for video, plus
 *   READ_MEDIA_VISUAL_USER_SELECTED on Android 14+ ("Select videos": partial
 *   access, reported as granted with limited = true).
 * - Android 12 and older: READ_EXTERNAL_STORAGE.
 *
 * The plugin only declares these when the app opts in to library access
 * (config nativephp-media.library); without them every request is denied
 * at once, without a dialog.
 */
internal object MediaPermissions {

    private const val VISUAL_USER_SELECTED = "android.permission.READ_MEDIA_VISUAL_USER_SELECTED"

    fun forType(type: String): Array<String> = when {
        Build.VERSION.SDK_INT < 33 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        type == "video" && Build.VERSION.SDK_INT >= 34 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO, VISUAL_USER_SELECTED)
        type == "video" -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
    }

    private fun has(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /** Full access to this type's media. */
    fun fullyGranted(context: Context, type: String): Boolean = has(context, forType(type).first())

    /** Android 14+ partial video access ("Select videos"). */
    fun limited(context: Context, type: String): Boolean =
        type == "video" && Build.VERSION.SDK_INT >= 34 &&
            !has(context, Manifest.permission.READ_MEDIA_VIDEO) && has(context, VISUAL_USER_SELECTED)

    fun granted(context: Context, type: String): Boolean = fullyGranted(context, type) || limited(context, type)

    /** Whether the app's manifest declares the permissions (library access enabled at build time). */
    fun declared(context: Context, type: String): Boolean {
        val requested = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions
        }.getOrNull() ?: return false
        return requested.contains(forType(type).first())
    }

    fun dispatchResult(context: Context, type: String) {
        MediaManager.dispatch(
            "PermissionResult",
            JSONObject()
                .put("granted", granted(context, type))
                .put("type", type)
                .put("limited", limited(context, type)),
        )
    }

    fun warnNotDeclared(type: String) {
        Log.w(
            MediaManager.TAG,
            "Device $type library access is not enabled: set NATIVEPHP_MEDIA_LIBRARY in .env (see the plugin README) and rebuild.",
        )
    }
}
